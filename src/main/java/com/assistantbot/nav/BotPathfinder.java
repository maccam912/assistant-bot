package com.assistantbot.nav;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps Minecraft's built-in A* pathfinding (MobNavigation + LandPathNodeMaker)
 * for use with our bot. Uses a temporary invisible ZombieEntity as a dummy mob
 * because the pathfinding system requires a MobEntity.
 *
 * The dummy mob is never spawned into the world — it exists only in memory as a
 * calculation vessel for the A* algorithm. Its position is synced to the bot
 * before each path computation.
 *
 * Usage: tasks call NavigationHelper.navigateTo() which delegates to this class
 * for path computation and waypoint iteration.
 */
public class BotPathfinder {
    /** Max A* search range in blocks. Also set on the dummy mob's FOLLOW_RANGE attribute. */
    private static final int PATHFINDING_RANGE = 32;

    /** Acceptable distance (in blocks) from the target for the path endpoint. */
    private static final int PATH_TARGET_DISTANCE = 1;

    /** How often to recompute the path, in game ticks. ~1 second at 20tps. */
    private static final int RECOMPUTE_INTERVAL_TICKS = 20;

    /** Task tick interval in game ticks (tasks tick every 5 game ticks). */
    private static final int TASK_TICK_INTERVAL = 5;

    private final AssistantBot bot;
    private ZombieEntity dummyMob;
    private MobNavigation navigation;

    // Current path state
    private @Nullable List<Vec3d> currentWaypoints;
    private int currentWaypointIndex;
    private @Nullable BlockPos currentTarget;
    private int ticksSinceLastCompute;

    public BotPathfinder(AssistantBot bot) {
        this.bot = bot;
        initDummyMob();
    }

    private void initDummyMob() {
        ServerWorld world = bot.getWorld();
        this.dummyMob = new ZombieEntity(EntityType.ZOMBIE, world);

        // Make it invisible and non-interacting — it exists only for path math
        dummyMob.setInvisible(true);
        dummyMob.setAiDisabled(true);
        dummyMob.setSilent(true);
        dummyMob.setNoGravity(true);

        // Set follow range to control A* search radius (zombie default is ~35)
        var followRange = dummyMob.getAttributeInstance(EntityAttributes.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(PATHFINDING_RANGE);
        }

        // Do NOT spawn it into the world — just use it as a calculation vessel
        this.navigation = (MobNavigation) dummyMob.getNavigation();

        // Allow pathing through doors (players can open doors, zombies can't by default)
        navigation.setCanOpenDoors(true);
    }

    /**
     * Compute a path from the bot's current position to the target.
     * Returns a list of waypoint Vec3d positions (block-centered on XZ,
     * feet-level Y), or null if no path found.
     */
    public @Nullable List<Vec3d> computePath(BlockPos target) {
        Vec3d botPos = bot.getPos();

        // Sync dummy mob to bot's position
        dummyMob.setPosition(botPos);

        // Use MobNavigation to compute the A* path
        Path path = navigation.findPathTo(target, PATH_TARGET_DISTANCE);
        if (path == null) {
            AssistantMod.LOGGER.debug("No path found to {}", target);
            return null;
        }

        // Extract waypoints from the Path
        List<Vec3d> waypoints = new ArrayList<>();
        for (int i = 0; i < path.getLength(); i++) {
            PathNode node = path.getNode(i);
            // Center X/Z in the block, Y at foot level
            waypoints.add(new Vec3d(node.x + 0.5, node.y, node.z + 0.5));
        }

        return waypoints;
    }

    /**
     * Navigate to a target position. Call this every task tick (~4Hz).
     * Handles path computation, caching, recomputation when target moves,
     * and returns the next waypoint the bot should walk toward.
     *
     * @return the next waypoint to walk toward, or null if already at target / no path
     */
    public @Nullable Vec3d getNextWaypoint(BlockPos target) {
        ticksSinceLastCompute += TASK_TICK_INTERVAL;

        boolean needsRecompute = false;

        // Recompute if target changed
        if (currentTarget == null || !currentTarget.equals(target)) {
            needsRecompute = true;
        }

        // Recompute periodically (target may have moved, or we got stuck)
        if (ticksSinceLastCompute >= RECOMPUTE_INTERVAL_TICKS) {
            needsRecompute = true;
        }

        // Recompute if we finished or lost our path
        if (currentWaypoints == null || currentWaypointIndex >= currentWaypoints.size()) {
            needsRecompute = true;
        }

        if (needsRecompute) {
            currentWaypoints = computePath(target);
            currentWaypointIndex = 0;
            currentTarget = target;
            ticksSinceLastCompute = 0;

            if (currentWaypoints == null || currentWaypoints.isEmpty()) {
                return null;
            }
        }

        // Advance past waypoints we've already reached
        Vec3d botPos = bot.getPos();
        while (currentWaypointIndex < currentWaypoints.size()) {
            Vec3d waypoint = currentWaypoints.get(currentWaypointIndex);
            double horizontalDist = Math.sqrt(
                    Math.pow(botPos.x - waypoint.x, 2) +
                    Math.pow(botPos.z - waypoint.z, 2)
            );
            // Consider waypoint reached if within ~0.7 blocks horizontally
            if (horizontalDist < 0.7) {
                currentWaypointIndex++;
            } else {
                break;
            }
        }

        if (currentWaypointIndex >= currentWaypoints.size()) {
            return null; // reached end of path
        }

        return currentWaypoints.get(currentWaypointIndex);
    }

    /**
     * Clear any cached path. Call when switching tasks or stopping movement.
     */
    public void clearPath() {
        currentWaypoints = null;
        currentWaypointIndex = 0;
        currentTarget = null;
        ticksSinceLastCompute = 0;
    }

    /**
     * @return true if we have an active path with waypoints remaining
     */
    public boolean hasPath() {
        return currentWaypoints != null && currentWaypointIndex < currentWaypoints.size();
    }

    /**
     * Clean up the dummy mob. Call when the bot is destroyed.
     */
    public void destroy() {
        dummyMob = null;
        navigation = null;
    }
}
