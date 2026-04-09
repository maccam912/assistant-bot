package com.assistantbot.task;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.llm.BuildStructure;
import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.LlmClient;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Build a structure from an LLM-generated plan. State machine phases:
 *   REQUESTING → SORTING → PLACING → DONE
 *
 * Uses server-enforced block placement (world.setBlockState) regardless
 * of bot distance. The bot walks toward each block for visual realism
 * but placement is not gated on reach distance.
 */
public class BuildTask implements BotTask {

    private enum BuildPhase { REQUESTING, SORTING, PLACING, DONE }

    private static final int MAX_APPROACH_TICKS = 20; // ~1 second, then place anyway
    private static final int MAX_RETRIES_PER_BLOCK = 3;

    private final String description;
    private final BlockPos originPos;

    private BuildPhase phase;
    private CompletableFuture<BuildStructure> llmFuture;
    private final LlmClient llmClient;

    // Placement state
    private List<BlockEntry> sortedBlocks;
    private int currentBlockIndex;
    private int approachTicksRemaining;
    private Map<Integer, Integer> retryCount; // block index -> retry count
    private List<Integer> retryQueue; // block indices to retry
    private int totalPlaced;
    private int totalSkipped;

    public BuildTask(String description, BlockPos originPos) {
        this.description = description;
        this.originPos = originPos;
        this.phase = BuildPhase.REQUESTING;
        this.llmClient = new LlmClient();
        this.retryCount = new HashMap<>();
        this.retryQueue = new ArrayList<>();
    }

    @Override
    public void onStart(AssistantBot bot) {
        AssistantMod.LOGGER.info("BuildTask starting: \"{}\" at {}", description, originPos);
        llmFuture = llmClient.requestStructureAsync(description);
    }

    @Override
    public void onStop(AssistantBot bot) {
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
        if (llmFuture != null && !llmFuture.isDone()) {
            llmFuture.cancel(true);
            AssistantMod.LOGGER.info("BuildTask cancelled, LLM request aborted");
        }
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        return switch (phase) {
            case REQUESTING -> tickRequesting(bot);
            case SORTING -> tickSorting(bot);
            case PLACING -> tickPlacing(bot);
            case DONE -> TickResult.COMPLETE;
        };
    }

    // --- Phase: REQUESTING ---

    private TickResult tickRequesting(AssistantBot bot) {
        if (!llmFuture.isDone()) {
            return TickResult.CONTINUE; // still waiting
        }

        try {
            BuildStructure structure = llmFuture.join();
            AssistantMod.LOGGER.info("LLM returned {} blocks for \"{}\"",
                    structure.getBlocks().size(), description);

            if (structure.getBlocks().isEmpty()) {
                AssistantMod.LOGGER.warn("LLM returned empty structure");
                return TickResult.FAILED;
            }

            sortedBlocks = sortBlocksBFS(structure.getBlocks());
            currentBlockIndex = 0;
            totalPlaced = 0;
            totalSkipped = 0;
            phase = BuildPhase.SORTING;
            return TickResult.CONTINUE;
        } catch (Exception e) {
            AssistantMod.LOGGER.error("LLM request failed: {}", e.getMessage());
            return TickResult.FAILED;
        }
    }

    // --- Phase: SORTING (instant transition) ---

    private TickResult tickSorting(AssistantBot bot) {
        // Sorting already done in tickRequesting. Transition immediately to placing.
        phase = BuildPhase.PLACING;
        approachTicksRemaining = MAX_APPROACH_TICKS;
        AssistantMod.LOGGER.info("Build plan sorted: {} blocks to place", sortedBlocks.size());
        return TickResult.CONTINUE;
    }

    // --- Phase: PLACING ---

    private TickResult tickPlacing(AssistantBot bot) {
        // Check if we're done with main queue and retry queue
        if (currentBlockIndex >= sortedBlocks.size()) {
            if (!retryQueue.isEmpty()) {
                return tickRetryQueue(bot);
            }
            phase = BuildPhase.DONE;
            AssistantMod.LOGGER.info("Build complete: {} placed, {} skipped",
                    totalPlaced, totalSkipped);
            return TickResult.COMPLETE;
        }

        BlockEntry entry = sortedBlocks.get(currentBlockIndex);
        BlockPos worldPos = originPos.add(new Vec3i(entry.x(), entry.y(), entry.z()));
        Vec3d targetCenter = Vec3d.ofCenter(worldPos);

        // Look at the target block
        LookHelper.lookAt(bot.getFakePlayer(), targetCenter);

        // Try to walk toward it (smoke and mirrors)
        double distance = bot.getPos().distanceTo(targetCenter);
        if (distance > 4.0 && approachTicksRemaining > 0) {
            NavigationHelper.navigateTo(bot, worldPos, NavigationHelper.WALK_SPEED);
            approachTicksRemaining -= 5; // we tick every 5 game ticks
            return TickResult.CONTINUE;
        }

        // Place the block
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
        boolean placed = placeBlockServerEnforced(bot.getWorld(), worldPos, entry.blockId());

        if (placed) {
            totalPlaced++;
        } else {
            // Queue for retry
            int retries = retryCount.getOrDefault(currentBlockIndex, 0);
            if (retries < MAX_RETRIES_PER_BLOCK) {
                retryCount.put(currentBlockIndex, retries + 1);
                retryQueue.add(currentBlockIndex);
            } else {
                totalSkipped++;
                AssistantMod.LOGGER.warn("Skipping block at {} after {} retries",
                        worldPos, MAX_RETRIES_PER_BLOCK);
            }
        }

        // Move to next block
        currentBlockIndex++;
        approachTicksRemaining = MAX_APPROACH_TICKS;
        return TickResult.CONTINUE;
    }

    private TickResult tickRetryQueue(AssistantBot bot) {
        if (retryQueue.isEmpty()) {
            phase = BuildPhase.DONE;
            return TickResult.COMPLETE;
        }

        int blockIdx = retryQueue.remove(0);
        BlockEntry entry = sortedBlocks.get(blockIdx);
        BlockPos worldPos = originPos.add(new Vec3i(entry.x(), entry.y(), entry.z()));

        LookHelper.lookAt(bot.getFakePlayer(), Vec3d.ofCenter(worldPos));
        boolean placed = placeBlockServerEnforced(bot.getWorld(), worldPos, entry.blockId());

        if (placed) {
            totalPlaced++;
        } else {
            int retries = retryCount.getOrDefault(blockIdx, 0);
            if (retries < MAX_RETRIES_PER_BLOCK) {
                retryCount.put(blockIdx, retries + 1);
                retryQueue.add(blockIdx); // re-add for another retry
            } else {
                totalSkipped++;
                AssistantMod.LOGGER.warn("Permanently skipping block at {}", worldPos);
            }
        }

        return TickResult.CONTINUE;
    }

    // --- Block placement ---

    /**
     * Place a block using world.setBlockState() directly. Server-enforced,
     * ignoring distance, inventory, and item checks.
     *
     * @return true if the block was placed successfully
     */
    private boolean placeBlockServerEnforced(ServerWorld world, BlockPos pos, String blockId) {
        // Resolve block ID to BlockState
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) {
            AssistantMod.LOGGER.warn("Invalid block ID: {}", blockId);
            return false;
        }

        Block block = Registries.BLOCK.get(id);
        if (block == Blocks.AIR) {
            // Registry returns AIR for unknown IDs — treat as hallucinated block name
            AssistantMod.LOGGER.warn("Unknown block ID (LLM hallucination?): {}", blockId);
            return false;
        }

        BlockState state = block.getDefaultState();
        boolean success = world.setBlockState(pos, state, Block.NOTIFY_ALL);

        // Verify placement
        if (success) {
            BlockState actual = world.getBlockState(pos);
            if (actual.getBlock() != block) {
                AssistantMod.LOGGER.warn("Block verification failed at {}: expected {}, got {}",
                        pos, blockId, Registries.BLOCK.getId(actual.getBlock()));
                return false;
            }
        }

        return success;
    }

    // --- BFS sort for realistic placement order ---

    /**
     * Sort blocks so each one is placed only when it has a face-adjacent
     * neighbor that's already placed (or is on the ground, y=0).
     *
     * This gives the visual appearance of building with structural support:
     * ground layer first, then blocks connected to already-placed blocks.
     * Orphan blocks (floating, no path to ground) are appended at the end
     * sorted by Y ascending.
     */
    private List<BlockEntry> sortBlocksBFS(List<BlockEntry> blocks) {
        // Build a lookup: position -> block entry
        Map<Long, BlockEntry> posMap = new HashMap<>();
        for (BlockEntry entry : blocks) {
            posMap.put(packPos(entry.x(), entry.y(), entry.z()), entry);
        }

        Set<Long> placed = new HashSet<>();
        List<BlockEntry> sorted = new ArrayList<>();
        Queue<BlockEntry> ready = new LinkedList<>();

        // Seed: all blocks at y=0 (ground-supported)
        for (BlockEntry entry : blocks) {
            if (entry.y() == 0) {
                long key = packPos(entry.x(), entry.y(), entry.z());
                if (placed.add(key)) {
                    ready.add(entry);
                }
            }
        }

        // BFS: place ready blocks, then check their neighbors
        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        while (!ready.isEmpty()) {
            BlockEntry current = ready.poll();
            sorted.add(current);

            // Check all 6 face-adjacent positions for unplaced blocks
            for (int[] dir : directions) {
                int nx = current.x() + dir[0];
                int ny = current.y() + dir[1];
                int nz = current.z() + dir[2];
                long nKey = packPos(nx, ny, nz);

                if (posMap.containsKey(nKey) && placed.add(nKey)) {
                    ready.add(posMap.get(nKey));
                }
            }
        }

        // Append orphans (floating blocks) sorted by Y ascending
        List<BlockEntry> orphans = new ArrayList<>();
        for (BlockEntry entry : blocks) {
            long key = packPos(entry.x(), entry.y(), entry.z());
            if (!placed.contains(key)) {
                orphans.add(entry);
            }
        }
        orphans.sort(Comparator.comparingInt(BlockEntry::y)
                .thenComparingInt(BlockEntry::x)
                .thenComparingInt(BlockEntry::z));
        sorted.addAll(orphans);

        return sorted;
    }

    /** Pack 3 ints into a long for HashMap key. */
    private long packPos(int x, int y, int z) {
        return ((long)(x & 0x1FFFFF) << 42) | ((long)(y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    @Override
    public String getStatusString() {
        return switch (phase) {
            case REQUESTING -> "building: thinking... (" + description + ")";
            case SORTING -> "building: planning placement order...";
            case PLACING -> {
                int total = sortedBlocks != null ? sortedBlocks.size() : 0;
                yield "building: " + totalPlaced + "/" + total + " blocks placed (" + description + ")";
            }
            case DONE -> "building: complete (" + totalPlaced + " placed, " + totalSkipped + " skipped)";
        };
    }
}
