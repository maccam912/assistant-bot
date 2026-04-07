package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;
import com.assistantbot.util.BlockHelper;
import com.assistantbot.util.InventoryHelper;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Mine a single block at a target position. Phases:
 *   APPROACHING → walk within reach
 *   EQUIPPING   → select best tool for the block
 *   BREAKING    → simulate break progress over ticks
 *   DONE        → block broken, task complete
 */
public class MineTask implements BotTask {
    private final BlockPos targetPos;
    private MinePhase phase;
    private int breakingTicks;
    private int breakingTicksRequired;

    private enum MinePhase { APPROACHING, EQUIPPING, BREAKING, DONE }

    public MineTask(BlockPos targetPos) {
        this.targetPos = targetPos;
        this.phase = MinePhase.APPROACHING;
    }

    @Override
    public void onStart(AssistantBot bot) {
        BlockState state = bot.getWorld().getBlockState(targetPos);
        if (state.isAir()) {
            phase = MinePhase.DONE;
        }
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        return switch (phase) {
            case APPROACHING -> tickApproaching(bot);
            case EQUIPPING   -> tickEquipping(bot);
            case BREAKING    -> tickBreaking(bot);
            case DONE        -> TickResult.COMPLETE;
        };
    }

    private TickResult tickApproaching(AssistantBot bot) {
        Vec3d targetCenter = Vec3d.ofCenter(targetPos);
        double distance = bot.getPos().distanceTo(targetCenter);

        if (distance <= BlockHelper.REACH_DISTANCE) {
            phase = MinePhase.EQUIPPING;
            return TickResult.CONTINUE;
        }

        LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
        NavigationHelper.moveToward(bot, targetCenter, NavigationHelper.WALK_SPEED);
        return TickResult.CONTINUE;
    }

    private TickResult tickEquipping(AssistantBot bot) {
        BlockState targetState = bot.getWorld().getBlockState(targetPos);
        InventoryHelper.equipBestTool(bot.getFakePlayer(), targetState);

        float hardness = targetState.getHardness(bot.getWorld(), targetPos);
        breakingTicksRequired = BlockHelper.calculateBreakTicks(bot.getFakePlayer(), targetState, hardness);
        breakingTicks = 0;
        phase = MinePhase.BREAKING;
        return TickResult.CONTINUE;
    }

    private TickResult tickBreaking(AssistantBot bot) {
        BlockState state = bot.getWorld().getBlockState(targetPos);
        if (state.isAir()) {
            phase = MinePhase.DONE;
            return TickResult.COMPLETE;
        }

        LookHelper.lookAt(bot.getFakePlayer(), Vec3d.ofCenter(targetPos));
        breakingTicks += 5; // we tick every 5 game ticks

        if (breakingTicks >= breakingTicksRequired) {
            BlockHelper.breakBlock(bot, targetPos);
            phase = MinePhase.DONE;
            return TickResult.COMPLETE;
        }

        return TickResult.CONTINUE;
    }

    @Override
    public String getStatusString() {
        return "mining at " + targetPos.toShortString() + " (" + phase + ")";
    }
}
