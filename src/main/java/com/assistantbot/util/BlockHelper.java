package com.assistantbot.util;

import com.assistantbot.bot.AssistantBot;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Block breaking and placement primitives.
 */
public final class BlockHelper {
    public static final double REACH_DISTANCE = 4.5;

    private BlockHelper() {}

    public static boolean breakBlock(AssistantBot bot, BlockPos pos) {
        ServerWorld world = bot.getWorld();
        FakePlayer player = bot.getFakePlayer();

        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;

        // Fire break event, drop loot, remove block
        state.getBlock().onBreak(world, pos, state, player);
        boolean removed = world.removeBlock(pos, false);
        if (removed) {
            state.getBlock().onBroken(world, pos, state);
            state.getBlock().afterBreak(world, player, pos, state,
                    world.getBlockEntity(pos), player.getMainHandStack().copy());
        }
        return removed;
    }

    public static boolean placeBlock(AssistantBot bot, BlockPos pos) {
        FakePlayer player = bot.getFakePlayer();
        ItemStack heldItem = player.getMainHandStack();

        if (heldItem.isEmpty() || !(heldItem.getItem() instanceof BlockItem)) {
            return false;
        }

        Direction placeFace = findPlacementFace(bot.getWorld(), pos);
        if (placeFace == null) return false;

        BlockPos supportPos = pos.offset(placeFace);
        Vec3d hitPos = Vec3d.ofCenter(supportPos)
                .add(Vec3d.of(placeFace.getOpposite().getVector()).multiply(0.5));

        BlockHitResult hitResult = new BlockHitResult(
                hitPos, placeFace.getOpposite(), supportPos, false
        );

        ItemUsageContext context = new ItemUsageContext(player, Hand.MAIN_HAND, hitResult);
        ActionResult result = heldItem.useOnBlock(context);
        return result.isAccepted();
    }

    public static int calculateBreakTicks(FakePlayer player, BlockState state, float hardness) {
        if (hardness < 0) return Integer.MAX_VALUE; // unbreakable
        if (hardness == 0) return 1;

        float speed = player.getMainHandStack().getMiningSpeedMultiplier(state);
        float damage = speed / hardness / 30.0f;
        return Math.max(1, (int) Math.ceil(1.0f / damage));
    }

    private static Direction findPlacementFace(ServerWorld world, BlockPos targetPos) {
        // Prefer solid adjacent block as support surface
        for (Direction dir : Direction.values()) {
            BlockPos supportPos = targetPos.offset(dir);
            BlockState supportState = world.getBlockState(supportPos);
            if (!supportState.isAir() && supportState.isSolidBlock(world, supportPos)) {
                return dir;
            }
        }
        return Direction.DOWN;
    }
}
