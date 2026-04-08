package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;
import com.assistantbot.util.InventoryHelper;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Find the nearest container and deposit all inventory into it. Phases:
 *   SEARCHING   → scan for containers within 16 blocks
 *   APPROACHING → walk to container
 *   DEPOSITING  → transfer items
 *   DONE        → complete
 */
public class DepositTask implements BotTask {
    private static final int SEARCH_RADIUS = 16;
    private BlockPos containerPos;
    private DepositPhase phase;

    private enum DepositPhase { SEARCHING, APPROACHING, DEPOSITING, DONE }

    public DepositTask() {
        this.phase = DepositPhase.SEARCHING;
    }

    public DepositTask(BlockPos containerPos) {
        this.containerPos = containerPos;
        this.phase = DepositPhase.APPROACHING;
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        return switch (phase) {
            case SEARCHING   -> tickSearching(bot);
            case APPROACHING -> tickApproaching(bot);
            case DEPOSITING  -> tickDepositing(bot);
            case DONE        -> TickResult.COMPLETE;
        };
    }

    private TickResult tickSearching(AssistantBot bot) {
        containerPos = findNearestContainer(bot);
        if (containerPos == null) {
            return TickResult.FAILED;
        }
        phase = DepositPhase.APPROACHING;
        return TickResult.CONTINUE;
    }

    private TickResult tickApproaching(AssistantBot bot) {
        Vec3d targetCenter = Vec3d.ofCenter(containerPos);
        double distance = bot.getPos().distanceTo(targetCenter);

        if (distance <= 3.0) {
            NavigationHelper.stopMoving(bot);
            phase = DepositPhase.DEPOSITING;
            return TickResult.CONTINUE;
        }

        LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
        NavigationHelper.moveToward(bot, targetCenter, NavigationHelper.WALK_SPEED);
        return TickResult.CONTINUE;
    }

    private TickResult tickDepositing(AssistantBot bot) {
        BlockEntity blockEntity = bot.getWorld().getBlockEntity(containerPos);
        if (!(blockEntity instanceof Inventory container)) {
            return TickResult.FAILED;
        }

        InventoryHelper.depositAll(bot.getFakePlayer(), container);
        phase = DepositPhase.DONE;
        return TickResult.COMPLETE;
    }

    private BlockPos findNearestContainer(AssistantBot bot) {
        BlockPos botPos = bot.getBlockPos();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS / 2; y <= SEARCH_RADIUS / 2; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    BlockPos pos = botPos.add(x, y, z);
                    BlockEntity be = bot.getWorld().getBlockEntity(pos);
                    if (be instanceof Inventory) {
                        double dist = botPos.getSquaredDistance(pos);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = pos;
                        }
                    }
                }
            }
        }

        return nearest;
    }

    @Override
    public String getStatusString() {
        return "depositing items (" + phase + ")";
    }
}
