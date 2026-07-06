package com.assistantbot.util;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.bot.BotPlayer;
import com.assistantbot.nav.BotPathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Movement primitives for the fake player. Provides two levels of navigation:
 *
 * - {@link #moveToward} — Low-level: walk in a straight line toward a point.
 *   Sets persistent velocity; Minecraft's physics handles gravity/collision at 20Hz.
 *
 * - {@link #navigateTo} — High-level: A* pathfinding via BotPathfinder. Computes
 *   a path around obstacles and follows waypoints. Falls back to moveToward if
 *   pathfinding fails.
 *
 * Tasks should use navigateTo() for approach phases. moveToward() remains
 * available for short-range direct movement.
 */
public final class NavigationHelper {
    public static final double WALK_SPEED = 0.2;    // ~4 blocks/sec
    public static final double SPRINT_SPEED = 0.26;  // ~5.2 blocks/sec

    private static final double JUMP_VELOCITY = 0.42;

    private NavigationHelper() {}

    /**
     * Set the bot's velocity toward a target. Minecraft's entity physics
     * (running every tick in BotPlayer.tick -> super.tick) will apply the
     * velocity, handle gravity, friction, and collision resolution smoothly.
     *
     * This should be called from the task tick (~4Hz) to update direction;
     * the actual movement happens continuously at 20Hz between calls.
     */
    public static void moveToward(AssistantBot bot, Vec3 target, double speed) {
        // Apply downed-state speed penalty
        speed *= bot.getSpeedMultiplier();

        ServerPlayer player = bot.getFakePlayer();
        BotPlayer botPlayer = (BotPlayer) player;
        Vec3 currentPos = player.position();

        double dx = target.x - currentPos.x;
        double dy = target.y - currentPos.y;
        double dz = target.z - currentPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist < 0.1) {
            stopMoving(bot);
            return;
        }

        double velX = (dx / horizontalDist) * speed;
        double velZ = (dz / horizontalDist) * speed;

        // Set the persistent horizontal velocity — BotPlayer.tick() re-applies
        // this every tick so movement is smooth between task updates.
        botPlayer.setDesiredHorizontalVelocity(new Vec3(velX, 0, velZ));

        // Jump if needed: either waypoint is above us, or blocked ahead.
        boolean waypointAbove = dy > 0.5 && horizontalDist < 2.0;
        if (waypointAbove || shouldJump(bot, velX, velZ)) {
            if (player.onGround()) {
                player.setDeltaMovement(player.getDeltaMovement().add(0, JUMP_VELOCITY - player.getDeltaMovement().y, 0));
            }
        }
    }

    /**
     * Navigate to a target position using A* pathfinding. Computes a path
     * around obstacles and follows waypoints. Falls back to direct movement
     * if pathfinding fails (e.g., target is very close or unreachable).
     *
     * Call this from task tick (~4Hz) instead of moveToward() for smart navigation.
     *
     * @return true if actively navigating, false if at destination or no path
     */
    public static boolean navigateTo(AssistantBot bot, BlockPos target, double speed) {
        BotPathfinder pathfinder = bot.getPathfinder();
        Vec3 nextWaypoint = pathfinder.getNextWaypoint(target);

        if (nextWaypoint == null) {
            // No path or already at target — check if we're close enough
            // to just walk directly (handles the last-meter case)
            Vec3 targetVec = Vec3.atCenterOf(target);
            double dist = bot.getPos().distanceTo(targetVec);
            if (dist < 2.0) {
                AssistantMod.LOGGER.info("navigateTo: at destination (dist={}), stopping", String.format("%.1f", dist));
                stopMoving(bot);
                return false;
            }
            // Pathfinding failed — teleport near the target instead of dumb moveToward
            AssistantMod.LOGGER.info("navigateTo: A* failed, teleporting near target (dist={})", String.format("%.1f", dist));
            teleportNear(bot, targetVec);
            return true;
        }

        AssistantMod.LOGGER.info("navigateTo: following waypoint {}", nextWaypoint);
        moveToward(bot, nextWaypoint, speed);
        return true;
    }

    /**
     * Navigate to a Vec3 target using A* pathfinding. Converts to BlockPos
     * for path computation, walks toward exact waypoints.
     */
    public static boolean navigateTo(AssistantBot bot, Vec3 target, double speed) {
        return navigateTo(bot, BlockPos.containing(target), speed);
    }

    /**
     * Stop horizontal movement. Clears the persistent velocity so the bot
     * stands still. Vertical velocity continues to be handled by physics.
     */
    public static void stopMoving(AssistantBot bot) {
        BotPlayer botPlayer = (BotPlayer) bot.getFakePlayer();
        botPlayer.setDesiredHorizontalVelocity(null);
        // Zero out any remaining horizontal velocity immediately.
        Vec3 vel = botPlayer.getDeltaMovement();
        botPlayer.setDeltaMovement(0, vel.y, 0);
    }

    public static void teleportNear(AssistantBot bot, Vec3 target) {
        ServerPlayer player = bot.getFakePlayer();
        double angle = Math.random() * Math.PI * 2;
        player.snapTo(
                target.x + Math.cos(angle) * 2,
                target.y,
                target.z + Math.sin(angle) * 2,
                player.getYRot(), player.getXRot()
        );
    }

    public static boolean isNearby(AssistantBot bot, Vec3 target, double radius) {
        return bot.getPos().distanceTo(target) <= radius;
    }

    private static boolean shouldJump(AssistantBot bot, double moveX, double moveZ) {
        ServerPlayer player = bot.getFakePlayer();
        if (!player.onGround()) return false;

        Vec3 pos = player.position();
        BlockPos ahead = BlockPos.containing(
                pos.x + moveX * 2,
                pos.y,
                pos.z + moveZ * 2
        );

        boolean blockedAhead = !bot.getWorld().getBlockState(ahead).isAir();
        boolean canStepUp = bot.getWorld().getBlockState(ahead.above()).isAir();

        return blockedAhead && canStepUp;
    }
}
