package com.assistantbot.task;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.llm.BlockIdResolver;
import com.assistantbot.llm.BlockStateResolver;
import com.assistantbot.llm.BuildPlan;
import com.assistantbot.llm.BuildPlanRegistry;
import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Execute phase of the build pipeline: looks up a stored plan from
 * BuildPlanRegistry, waits 5 seconds, clears the build area, then
 * places blocks at the bot's configured blocks-per-second rate.
 *
 * Phases: WAITING -> CLEARING -> PLACING -> FINALIZING -> DONE
 *
 * The origin (bot's position at execute time) localizes the plan
 * so the same plan can be stamped at different locations.
 */
public class BuildTask implements BotTask {

    private enum BuildPhase { WAITING, CLEARING, PLACING, FINALIZING, DONE }

    private static final int MAX_RETRIES_PER_BLOCK = 3;
    private static final int WAIT_TICKS = 20; // 20 task-ticks * 5 game ticks = 100 game ticks = 5 seconds
    private static final double VISUAL_REPOSITION_START_DISTANCE = 2.0;
    private static final double VISUAL_REPOSITION_TELEPORT_DISTANCE = 6.0;
    private static final double VISUAL_SNAP_RADIUS = 1.5;
    private static final double BUILD_VISUAL_MOVE_SPEED = 0.26;

    private final int planId;
    private final BlockPos originPos;

    private BuildPhase phase;
    private BuildPlan plan; // resolved from registry in onStart

    // Wait state
    private int waitTicksRemaining;

    // Placement state
    private List<BlockEntry> sortedBlocks;
    private int currentBlockIndex;
    private Map<Integer, Integer> retryCount;
    private List<Integer> retryQueue;
    private int totalPlaced;
    private int totalSkipped;
    private int finalizeBlockIndex;
    private final BuildRateLimiter rateLimiter;

    // Clearing state
    private BlockPos clearMin;
    private BlockPos clearMax;
    private int clearCurrentY;
    private int totalCleared;

    public BuildTask(int planId, BlockPos originPos) {
        this.planId = planId;
        this.originPos = originPos;
        this.phase = BuildPhase.WAITING;
        this.retryCount = new HashMap<>();
        this.retryQueue = new ArrayList<>();
        this.waitTicksRemaining = WAIT_TICKS;
        this.rateLimiter = new BuildRateLimiter();
    }

    @Override
    public void onStart(AssistantBot bot) {
        plan = BuildPlanRegistry.getInstance().get(planId);
        if (plan == null) {
            AssistantMod.LOGGER.error("BuildTask started with invalid plan ID: {}", planId);
            sendMessage(bot, "§c[Assistant] No plan found with ID: " + planId);
            return;
        }

        sortedBlocks = plan.getSortedBlocks();
        currentBlockIndex = 0;
        totalPlaced = 0;
        totalSkipped = 0;

        AssistantMod.LOGGER.info("BuildTask starting: plan #{} \"{}\" ({} blocks) at {}",
                planId, plan.getDescription(), sortedBlocks.size(), originPos);
        sendMessage(bot, "§a[Assistant] Building plan #" + planId + " in 5 seconds... ("
                + plan.getDescription() + " — " + sortedBlocks.size() + " blocks at "
                + BuildRateLimiter.format(bot.getBuildSpeedBlocksPerSecond()) + " blocks/s)");
    }

    @Override
    public void onStop(AssistantBot bot) {
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        if (plan == null) {
            return TickResult.FAILED;
        }

        return switch (phase) {
            case WAITING -> tickWaiting(bot);
            case CLEARING -> tickClearing(bot);
            case PLACING -> tickPlacing(bot);
            case FINALIZING -> tickFinalizing(bot);
            case DONE -> TickResult.COMPLETE;
        };
    }

    // --- Phase: WAITING (5-second countdown) ---

    private TickResult tickWaiting(AssistantBot bot) {
        waitTicksRemaining--;
        if (waitTicksRemaining > 0) {
            return TickResult.CONTINUE;
        }

        // Transition to clearing
        computeClearBounds();
        clearCurrentY = clearMax.getY();
        totalCleared = 0;
        phase = BuildPhase.CLEARING;
        AssistantMod.LOGGER.info("Wait complete. Clearing area from {} to {} for plan #{}",
                clearMin, clearMax, planId);
        return TickResult.CONTINUE;
    }

    // --- Phase: CLEARING ---

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
        clearMin = originPos.offset(new Vec3i(minX, minY, minZ));
        clearMax = originPos.offset(new Vec3i(maxX, maxY, maxZ));
    }

    private TickResult tickClearing(AssistantBot bot) {
        if (clearCurrentY < clearMin.getY()) {
            AssistantMod.LOGGER.info("Clearing complete: {} blocks removed", totalCleared);
            phase = BuildPhase.PLACING;
            return TickResult.CONTINUE;
        }

        ServerLevel world = bot.getWorld();
        BlockState air = Blocks.AIR.defaultBlockState();

        for (int x = clearMin.getX(); x <= clearMax.getX(); x++) {
            for (int z = clearMin.getZ(); z <= clearMax.getZ(); z++) {
                BlockPos pos = new BlockPos(x, clearCurrentY, z);
                if (!world.getBlockState(pos).isAir()) {
                    world.setBlock(pos, air, Block.UPDATE_ALL);
                    totalCleared++;
                }
            }
        }

        Vec3 layerCenter = new Vec3(
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
        if (currentBlockIndex >= sortedBlocks.size() && retryQueue.isEmpty()) {
            beginFinalizing(bot);
            return TickResult.CONTINUE;
        }

        int blockBudget = rateLimiter.takeBlockBudget(bot.getBuildSpeedBlocksPerSecond());
        for (int i = 0; i < blockBudget; i++) {
            if (currentBlockIndex < sortedBlocks.size()) {
                attemptNextBlock(bot);
            } else if (!retryQueue.isEmpty()) {
                attemptNextRetry(bot);
            } else {
                beginFinalizing(bot);
                return TickResult.CONTINUE;
            }
        }
        return TickResult.CONTINUE;
    }

    private void attemptNextBlock(AssistantBot bot) {
        BlockEntry entry = sortedBlocks.get(currentBlockIndex);
        BlockPos worldPos = originPos.offset(new Vec3i(entry.x(), entry.y(), entry.z()));
        boolean placed = attemptPlacementThisTick(bot, entry);

        if (placed) {
            totalPlaced++;
        } else {
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

        currentBlockIndex++;
    }

    private void attemptNextRetry(AssistantBot bot) {
        int blockIdx = retryQueue.remove(0);
        BlockEntry entry = sortedBlocks.get(blockIdx);
        BlockPos worldPos = originPos.offset(new Vec3i(entry.x(), entry.y(), entry.z()));
        boolean placed = attemptPlacementThisTick(bot, entry);

        if (placed) {
            totalPlaced++;
        } else {
            int retries = retryCount.getOrDefault(blockIdx, 0);
            if (retries < MAX_RETRIES_PER_BLOCK) {
                retryCount.put(blockIdx, retries + 1);
                retryQueue.add(blockIdx);
            } else {
                totalSkipped++;
                AssistantMod.LOGGER.warn("Permanently skipping block at {}", worldPos);
            }
        }
    }

    private void beginFinalizing(AssistantBot bot) {
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
        finalizeBlockIndex = 0;
        phase = BuildPhase.FINALIZING;
    }

    /**
     * Reconcile neighbor-derived states against the completed build. Direct
     * server placement starts panes/fences/walls from their default state;
     * this pass gives every block a completed set of neighbors from which to
     * derive connections, stair corners, and other contextual shapes.
     */
    private TickResult tickFinalizing(AssistantBot bot) {
        ServerLevel world = bot.getWorld();
        int blockBudget = rateLimiter.takeBlockBudget(bot.getBuildSpeedBlocksPerSecond());
        for (int i = 0; i < blockBudget; i++) {
            if (finalizeBlockIndex >= sortedBlocks.size()) {
                phase = BuildPhase.DONE;
                AssistantMod.LOGGER.info("Build complete: {} placed, {} skipped (plan #{})",
                        totalPlaced, totalSkipped, planId);
                sendMessage(bot, "§a[Assistant] Build complete! " + totalPlaced + " blocks placed"
                        + (totalSkipped > 0 ? " (" + totalSkipped + " skipped)" : "")
                        + " — plan #" + planId);
                return TickResult.COMPLETE;
            }

            BlockEntry entry = sortedBlocks.get(finalizeBlockIndex++);
            BlockPos worldPos = originPos.offset(new Vec3i(entry.x(), entry.y(), entry.z()));
            BlockState current = world.getBlockState(worldPos);
            if (current.isAir()) {
                continue;
            }

            BlockState reconciled = Block.updateFromNeighbourShapes(current, world, worldPos);
            if (!reconciled.equals(current)) {
                world.setBlock(worldPos, reconciled, Block.UPDATE_ALL);
            }
        }
        return TickResult.CONTINUE;
    }

    private boolean attemptPlacementThisTick(AssistantBot bot, BlockEntry entry) {
        // Skip air blocks — volume is already cleared, no need to navigate or place
        if (entry.blockId().equals("minecraft:air") || entry.blockId().equals("air")) {
            return true;
        }
        BlockPos worldPos = originPos.offset(new Vec3i(entry.x(), entry.y(), entry.z()));
        Vec3 targetCenter = Vec3.atCenterOf(worldPos);
        double distance = bot.getPos().distanceTo(targetCenter);

        if (distance > VISUAL_REPOSITION_TELEPORT_DISTANCE) {
            NavigationHelper.teleportNear(bot, targetCenter);
            snapCloseToTarget(bot, targetCenter);
            NavigationHelper.moveToward(bot, targetCenter, BUILD_VISUAL_MOVE_SPEED);
        } else if (distance > VISUAL_REPOSITION_START_DISTANCE) {
            NavigationHelper.navigateTo(bot, worldPos, BUILD_VISUAL_MOVE_SPEED);
            snapCloseToTarget(bot, targetCenter);
        } else {
            NavigationHelper.moveToward(bot, targetCenter, BUILD_VISUAL_MOVE_SPEED);
        }

        LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
        return placeBlockServerEnforced(bot.getWorld(), worldPos, entry.blockId());
    }

    private void snapCloseToTarget(AssistantBot bot, Vec3 targetCenter) {
        var player = bot.getFakePlayer();
        Vec3 currentPos = player.position();
        Vec3 delta = targetCenter.subtract(currentPos);
        double distance = delta.length();

        if (distance <= VISUAL_SNAP_RADIUS) {
            return;
        }

        double step = Math.min(
                distance,
                Math.max(0.0, distance - VISUAL_SNAP_RADIUS)
        );
        if (step <= 0.0) {
            return;
        }

        Vec3 snapped = currentPos.add(delta.normalize().scale(step));
        Vec3 bounded = new Vec3(
                clamp(snapped.x, clearMin.getX() + 0.5, clearMax.getX() + 0.5),
                clamp(snapped.y, clearMin.getY(), clearMax.getY() + 1.0),
                clamp(snapped.z, clearMin.getZ() + 0.5, clearMax.getZ() + 0.5)
        );
        player.snapTo(
                bounded.x,
                bounded.y,
                bounded.z,
                player.getYRot(), player.getXRot()
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean placeBlockServerEnforced(ServerLevel world, BlockPos pos, String blockId) {
        Identifier id = Identifier.tryParse(BlockIdResolver.normalizeBaseId(blockId));
        if (id == null) {
            AssistantMod.LOGGER.warn("Invalid block ID: {}", blockId);
            return false;
        }

        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            AssistantMod.LOGGER.warn("Unknown block ID (LLM hallucination?): {}", blockId);
            return false;
        }

        Block block = BuiltInRegistries.BLOCK.getValue(id);
        if (block == Blocks.AIR && !id.toString().equals("minecraft:air")) {
            AssistantMod.LOGGER.warn("Unknown block ID (LLM hallucination?): {}", blockId);
            return false;
        }

        BlockState state;
        try {
            state = BlockStateResolver.resolve(block, blockId);
        } catch (IllegalArgumentException e) {
            AssistantMod.LOGGER.warn("Invalid block state '{}': {}", blockId, e.getMessage());
            return false;
        }
        boolean success = world.setBlock(pos, state, Block.UPDATE_ALL);

        if (success) {
            BlockState actual = world.getBlockState(pos);
            if (actual.getBlock() != block) {
                AssistantMod.LOGGER.warn("Block verification failed at {}: expected {}, got {}",
                        pos, blockId, BuiltInRegistries.BLOCK.getKey(actual.getBlock()));
                return false;
            }
        }

        return success;
    }

    // --- Helpers ---

    private void sendMessage(AssistantBot bot, String message) {
        ServerPlayer owner = bot.getOwnerPlayer();
        if (owner != null && !owner.hasDisconnected()) {
            owner.sendSystemMessage(Component.literal(message));
        }
    }

    @Override
    public String getStatusString() {
        if (plan == null) {
            return "building: invalid plan #" + planId;
        }
        return switch (phase) {
            case WAITING -> "building: starting in " + (waitTicksRemaining * 5 / 20) + "s (plan #" + planId + ")";
            case CLEARING -> {
                int layersTotal = clearMax.getY() - clearMin.getY() + 1;
                int layersCleared = clearMax.getY() - clearCurrentY;
                yield "building: clearing area " + layersCleared + "/" + layersTotal + " layers (" + totalCleared + " blocks removed)";
            }
            case PLACING -> {
                int total = sortedBlocks != null ? sortedBlocks.size() : 0;
                yield "building: " + totalPlaced + "/" + total + " blocks placed (plan #" + planId + ")";
            }
            case FINALIZING -> "building: finishing connections and orientations (plan #" + planId + ")";
            case DONE -> "building: complete (" + totalPlaced + " placed, " + totalSkipped + " skipped)";
        };
    }
}
