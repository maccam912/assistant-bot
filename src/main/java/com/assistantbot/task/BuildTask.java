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
 *   REQUESTING → VALIDATING → SORTING → CLEARING → PLACING → DONE
 *
 * Before placing blocks, the CLEARING phase replaces everything in the
 * structure's bounding box with air so the build doesn't merge with
 * existing terrain, trees, etc.
 *
 * Uses server-enforced block placement (world.setBlockState) regardless
 * of bot distance. The bot walks toward each block for visual realism
 * but placement is not gated on reach distance.
 */
public class BuildTask implements BotTask {

    private enum BuildPhase { REQUESTING, VALIDATING, SORTING, CLEARING, PLACING, DONE }

    private static final int MAX_APPROACH_TICKS = 20; // ~1 second, then place anyway
    private static final int MAX_RETRIES_PER_BLOCK = 3;
    private static final int LLM_WAIT_LOG_INTERVAL = 24; // log every ~12 seconds (24 ticks * 5 game ticks = 120 game ticks)

    private final String description;
    private final BlockPos originPos;

    private BuildPhase phase;
    private CompletableFuture<BuildStructure> llmFuture;
    private final LlmClient llmClient;
    private int llmWaitTicks; // how many task-ticks we've been waiting for the LLM

    // Validation state
    private BuildStructure structure; // stored between REQUESTING and VALIDATING
    private CompletableFuture<Map<String, String>> correctionFuture;
    private int validationWaitTicks;
    private boolean correctionAttempted; // true after one correction round

    // Placement state
    private List<BlockEntry> sortedBlocks;
    private int currentBlockIndex;
    private int approachTicksRemaining;
    private Map<Integer, Integer> retryCount; // block index -> retry count
    private List<Integer> retryQueue; // block indices to retry
    private int totalPlaced;
    private int totalSkipped;

    // Clearing state — iterate through bounding box one Y-layer at a time
    private BlockPos clearMin; // world-space min corner of bounding box
    private BlockPos clearMax; // world-space max corner of bounding box
    private int clearCurrentY; // current Y layer being cleared
    private int totalCleared;

    public BuildTask(String description, BlockPos originPos) {
        this.description = description;
        this.originPos = originPos;
        this.phase = BuildPhase.REQUESTING;
        this.llmClient = new LlmClient();
        this.retryCount = new HashMap<>();
        this.retryQueue = new ArrayList<>();
        this.llmWaitTicks = 0;
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
        if (correctionFuture != null && !correctionFuture.isDone()) {
            correctionFuture.cancel(true);
            AssistantMod.LOGGER.info("BuildTask cancelled, block correction request aborted");
        }
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        return switch (phase) {
            case REQUESTING -> tickRequesting(bot);
            case VALIDATING -> tickValidating(bot);
            case SORTING -> tickSorting(bot);
            case CLEARING -> tickClearing(bot);
            case PLACING -> tickPlacing(bot);
            case DONE -> TickResult.COMPLETE;
        };
    }

    // --- Phase: REQUESTING ---

    private TickResult tickRequesting(AssistantBot bot) {
        if (!llmFuture.isDone()) {
            llmWaitTicks++;
            if (llmWaitTicks % LLM_WAIT_LOG_INTERVAL == 0) {
                int waitSeconds = llmWaitTicks * 5 / 20; // task ticks * 5 game ticks / 20 tps
                AssistantMod.LOGGER.info("Still waiting for LLM response... ({}s elapsed, description: \"{}\")",
                        waitSeconds, description);
            }
            return TickResult.CONTINUE; // still waiting
        }

        try {
            structure = llmFuture.join();
            AssistantMod.LOGGER.info("LLM returned {} blocks for \"{}\"",
                    structure.getBlocks().size(), description);

            if (structure.getBlocks().isEmpty()) {
                AssistantMod.LOGGER.warn("LLM returned empty structure");
                return TickResult.FAILED;
            }

            phase = BuildPhase.VALIDATING;
            validationWaitTicks = 0;
            return TickResult.CONTINUE;
        } catch (Exception e) {
            AssistantMod.LOGGER.error("LLM request failed: {}", e.getMessage());
            return TickResult.FAILED;
        }
    }

    // --- Phase: VALIDATING ---

    private TickResult tickValidating(AssistantBot bot) {
        // If we're waiting for a correction response, poll it
        if (correctionFuture != null) {
            if (!correctionFuture.isDone()) {
                validationWaitTicks++;
                if (validationWaitTicks % LLM_WAIT_LOG_INTERVAL == 0) {
                    int waitSeconds = validationWaitTicks * 5 / 20;
                    AssistantMod.LOGGER.info("Still waiting for block correction response... ({}s elapsed)", waitSeconds);
                }
                return TickResult.CONTINUE;
            }

            // Process correction response
            try {
                Map<String, String> corrections = correctionFuture.join();
                applyCorrectionResults(corrections);
            } catch (Exception e) {
                AssistantMod.LOGGER.warn("Block correction request failed: {}", e.getMessage());
                AssistantMod.LOGGER.warn("Proceeding with build — invalid blocks will become air");
            }
            correctionFuture = null;

            // After applying corrections, validate again
            Set<String> stillInvalid = findInvalidBlockIds();
            if (!stillInvalid.isEmpty()) {
                AssistantMod.LOGGER.warn("After correction, {} block IDs are still invalid — they will become air: {}",
                        stillInvalid.size(), stillInvalid);
                for (String invalidId : stillInvalid) {
                    structure.replaceBlockId(invalidId, "minecraft:air");
                }
            }

            // Proceed to sorting
            return transitionToSorting();
        }

        // First entry: validate all block IDs
        Set<String> invalidIds = findInvalidBlockIds();

        if (invalidIds.isEmpty()) {
            AssistantMod.LOGGER.info("All block IDs validated successfully");
            return transitionToSorting();
        }

        AssistantMod.LOGGER.warn("Found {} invalid block IDs: {}", invalidIds.size(), invalidIds);

        if (correctionAttempted) {
            // Already tried once — skip invalid blocks
            AssistantMod.LOGGER.warn("Correction already attempted, proceeding with build — invalid blocks will become air");
            for (String id : invalidIds) {
                structure.replaceBlockId(id, "minecraft:air");
            }
            return transitionToSorting();
        }

        // Request corrections from LLM
        correctionAttempted = true;
        correctionFuture = llmClient.requestBlockCorrectionsAsync(invalidIds);
        validationWaitTicks = 0;
        AssistantMod.LOGGER.info("Requesting block corrections from LLM for {} invalid blocks", invalidIds.size());
        return TickResult.CONTINUE;
    }

    /**
     * Strips block state properties (e.g., [axis=y]) from a block ID string.
     */
    private static String stripBlockState(String blockId) {
        int bracketIdx = blockId.indexOf('[');
        return bracketIdx >= 0 ? blockId.substring(0, bracketIdx) : blockId;
    }

    /**
     * Find all block IDs in the structure that don't exist in the Minecraft registry.
     */
    private Set<String> findInvalidBlockIds() {
        Set<String> invalid = new HashSet<>();
        for (String blockId : structure.getUniqueBlockIds()) {
            // Strip block state properties like [axis=y] for registry lookup
            String baseId = stripBlockState(blockId);

            // Skip air — it's always valid
            if (baseId.equals("minecraft:air")) continue;

            Identifier id = Identifier.tryParse(baseId);
            if (id == null) {
                invalid.add(blockId);
                continue;
            }

            Block block = Registries.BLOCK.get(id);
            if (block == Blocks.AIR) {
                // Registry returns AIR for unknown IDs
                invalid.add(blockId);
            }
        }
        return invalid;
    }

    /**
     * Apply LLM-provided corrections to the structure, validating each replacement.
     */
    private void applyCorrectionResults(Map<String, String> corrections) {
        for (Map.Entry<String, String> entry : corrections.entrySet()) {
            String invalidId = entry.getKey();
            String replacement = entry.getValue();

            // Validate the replacement
            String baseReplacement = stripBlockState(replacement);
            Identifier id = Identifier.tryParse(baseReplacement);
            if (id == null) {
                AssistantMod.LOGGER.warn("LLM suggested invalid replacement ID: {} -> {}", invalidId, replacement);
                continue;
            }

            Block block = Registries.BLOCK.get(id);
            if (block == Blocks.AIR && !baseReplacement.equals("minecraft:air")) {
                AssistantMod.LOGGER.warn("LLM suggested unknown replacement block: {} -> {}", invalidId, replacement);
                continue;
            }

            AssistantMod.LOGGER.info("Replacing invalid block: {} -> {}", invalidId, replacement);
            structure.replaceBlockId(invalidId, replacement);
        }
    }

    /**
     * Transition from VALIDATING to SORTING by extracting blocks and computing placement order.
     */
    private TickResult transitionToSorting() {
        sortedBlocks = sortBlocksBFS(structure.getBlocks());
        currentBlockIndex = 0;
        totalPlaced = 0;
        totalSkipped = 0;
        phase = BuildPhase.SORTING;
        return TickResult.CONTINUE;
    }

    // --- Phase: SORTING (instant transition) ---

    private TickResult tickSorting(AssistantBot bot) {
        // Sorting already done in transitionToSorting. Compute bounding box and start clearing.
        computeClearBounds();
        clearCurrentY = clearMax.getY(); // clear top-down so trees/leaves fall naturally
        totalCleared = 0;
        phase = BuildPhase.CLEARING;
        AssistantMod.LOGGER.info("Build plan sorted: {} blocks to place. Clearing area from {} to {}",
                sortedBlocks.size(), clearMin, clearMax);
        return TickResult.CONTINUE;
    }

    /**
     * Compute the world-space bounding box of the structure (exact bounds,
     * no padding). Used by the CLEARING phase to replace everything with air.
     */
    private void computeClearBounds() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockEntry entry : sortedBlocks) {
            minX = Math.min(minX, entry.x());
            minY = Math.min(minY, entry.y());
            minZ = Math.min(minZ, entry.z());
            maxX = Math.max(maxX, entry.x());
            maxY = Math.max(maxY, entry.y());
            maxZ = Math.max(maxZ, entry.z());
        }
        clearMin = originPos.add(new Vec3i(minX, minY, minZ));
        clearMax = originPos.add(new Vec3i(maxX, maxY, maxZ));
    }

    // --- Phase: CLEARING ---

    /**
     * Clear the build area one Y-layer per tick (top-down) by replacing
     * all non-air blocks with air. This removes terrain, trees, and other
     * obstacles so the structure doesn't merge with existing world geometry.
     */
    private TickResult tickClearing(AssistantBot bot) {
        if (clearCurrentY < clearMin.getY()) {
            // Done clearing
            AssistantMod.LOGGER.info("Clearing complete: {} blocks removed", totalCleared);
            phase = BuildPhase.PLACING;
            approachTicksRemaining = MAX_APPROACH_TICKS;
            return TickResult.CONTINUE;
        }

        ServerWorld world = bot.getWorld();
        BlockState air = Blocks.AIR.getDefaultState();

        // Clear one full Y-layer
        for (int x = clearMin.getX(); x <= clearMax.getX(); x++) {
            for (int z = clearMin.getZ(); z <= clearMax.getZ(); z++) {
                BlockPos pos = new BlockPos(x, clearCurrentY, z);
                if (!world.getBlockState(pos).isAir()) {
                    world.setBlockState(pos, air, Block.NOTIFY_ALL);
                    totalCleared++;
                }
            }
        }

        // Look at the center of the layer being cleared for visual effect
        Vec3d layerCenter = new Vec3d(
                (clearMin.getX() + clearMax.getX()) / 2.0 + 0.5,
                clearCurrentY + 0.5,
                (clearMin.getZ() + clearMax.getZ()) / 2.0 + 0.5
        );
        LookHelper.lookAt(bot.getFakePlayer(), layerCenter);

        clearCurrentY--;
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
            case REQUESTING -> {
                int waitSeconds = llmWaitTicks * 5 / 20;
                yield "building: waiting for LLM... (" + waitSeconds + "s, " + description + ")";
            }
            case VALIDATING -> {
                if (correctionFuture != null) {
                    int waitSeconds = validationWaitTicks * 5 / 20;
                    yield "building: waiting for block corrections... (" + waitSeconds + "s)";
                }
                yield "building: validating block IDs...";
            }
            case SORTING -> "building: planning placement order...";
            case CLEARING -> {
                int layersTotal = clearMax.getY() - clearMin.getY() + 1;
                int layersCleared = clearMax.getY() - clearCurrentY;
                yield "building: clearing area " + layersCleared + "/" + layersTotal + " layers (" + totalCleared + " blocks removed)";
            }
            case PLACING -> {
                int total = sortedBlocks != null ? sortedBlocks.size() : 0;
                yield "building: " + totalPlaced + "/" + total + " blocks placed (" + description + ")";
            }
            case DONE -> "building: complete (" + totalPlaced + " placed, " + totalSkipped + " skipped)";
        };
    }
}
