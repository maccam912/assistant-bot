package com.assistantbot.util;

import com.assistantbot.bot.AssistantBot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Block breaking and placement primitives.
 */
public final class BlockHelper {
    public static final double REACH_DISTANCE = 4.5;

    private BlockHelper() {}

    public static boolean breakBlock(AssistantBot bot, BlockPos pos) {
        ServerLevel world = bot.getWorld();
        ServerPlayer player = bot.getFakePlayer();

        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;

        // Fire break event, drop loot, remove block
        state.getBlock().playerWillDestroy(world, pos, state, player);
        boolean removed = world.removeBlock(pos, false);
        if (removed) {
            state.getBlock().destroy(world, pos, state);
            state.getBlock().playerDestroy(world, player, pos, state,
                    world.getBlockEntity(pos), player.getMainHandItem().copy());
        }
        return removed;
    }

    public static boolean placeBlock(AssistantBot bot, BlockPos pos) {
        ServerPlayer player = bot.getFakePlayer();
        ItemStack heldItem = player.getMainHandItem();

        if (heldItem.isEmpty() || !(heldItem.getItem() instanceof BlockItem)) {
            return false;
        }

        Direction placeFace = findPlacementFace(bot.getWorld(), pos);
        if (placeFace == null) return false;

        BlockPos supportPos = pos.relative(placeFace);
        Vec3 hitPos = Vec3.atCenterOf(supportPos)
                .add(Vec3.atLowerCornerOf(placeFace.getOpposite().getUnitVec3i()).scale(0.5));

        BlockHitResult hitResult = new BlockHitResult(
                hitPos, placeFace.getOpposite(), supportPos, false
        );

        UseOnContext context = new UseOnContext(player, InteractionHand.MAIN_HAND, hitResult);
        InteractionResult result = heldItem.useOn(context);
        return result.consumesAction();
    }

    public static int calculateBreakTicks(ServerPlayer player, BlockState state, float hardness) {
        if (hardness < 0) return Integer.MAX_VALUE; // unbreakable
        if (hardness == 0) return 1;

        float speed = player.getMainHandItem().getDestroySpeed(state);
        float damage = speed / hardness / 30.0f;
        return Math.max(1, (int) Math.ceil(1.0f / damage));
    }

    private static Direction findPlacementFace(ServerLevel world, BlockPos targetPos) {
        // Prefer solid adjacent block as support surface
        for (Direction dir : Direction.values()) {
            BlockPos supportPos = targetPos.relative(dir);
            BlockState supportState = world.getBlockState(supportPos);
            if (!supportState.isAir() && supportState.isRedstoneConductor(world, supportPos)) {
                return dir;
            }
        }
        return Direction.DOWN;
    }
}
