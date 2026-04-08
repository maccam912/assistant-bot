package com.assistantbot.util;

import com.assistantbot.bot.AssistantBot;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Simple movement primitives for the fake player. Uses Entity.move()
 * for collision-aware movement. No A* pathfinding yet — walks toward
 * target in a straight line, jumping over 1-block obstacles.
 */
public final class NavigationHelper {
    public static final double WALK_SPEED = 0.2;    // ~4 blocks/sec
    public static final double SPRINT_SPEED = 0.26;  // ~5.2 blocks/sec

    private static final double JUMP_VELOCITY = 0.42;

    private NavigationHelper() {}

    public static void moveToward(AssistantBot bot, Vec3d target, double speed) {
        FakePlayer player = bot.getFakePlayer();
        Vec3d currentPos = player.getEntityPos();

        double dx = target.x - currentPos.x;
        double dz = target.z - currentPos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist < 0.1) return;

        double moveX = (dx / horizontalDist) * speed;
        double moveZ = (dz / horizontalDist) * speed;
        double moveY = -0.08; // gravity

        if (shouldJump(bot, moveX, moveZ)) {
            moveY = JUMP_VELOCITY;
        }

        player.move(MovementType.SELF, new Vec3d(moveX, moveY, moveZ));
    }

    public static void teleportNear(AssistantBot bot, Vec3d target) {
        FakePlayer player = bot.getFakePlayer();
        double angle = Math.random() * Math.PI * 2;
        player.refreshPositionAndAngles(
                target.x + Math.cos(angle) * 2,
                target.y,
                target.z + Math.sin(angle) * 2,
                player.getYaw(), player.getPitch()
        );
    }

    public static boolean isNearby(AssistantBot bot, Vec3d target, double radius) {
        return bot.getPos().distanceTo(target) <= radius;
    }

    private static boolean shouldJump(AssistantBot bot, double moveX, double moveZ) {
        FakePlayer player = bot.getFakePlayer();
        if (!player.isOnGround()) return false;

        Vec3d pos = player.getEntityPos();
        BlockPos ahead = BlockPos.ofFloored(
                pos.x + moveX * 2,
                pos.y,
                pos.z + moveZ * 2
        );

        boolean blockedAhead = !bot.getWorld().getBlockState(ahead).isAir();
        boolean canStepUp = bot.getWorld().getBlockState(ahead.up()).isAir();

        return blockedAhead && canStepUp;
    }
}
