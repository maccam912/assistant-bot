package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;
import com.assistantbot.util.BlockHelper;
import com.assistantbot.util.InventoryHelper;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * Place a specific block type at a target position. Phases:
 *   APPROACHING → walk within reach
 *   EQUIPPING   → find block item in inventory, equip it
 *   PLACING     → interact to place
 *   DONE        → success
 */
public class PlaceTask implements BotTask {
    private final BlockPos targetPos;
    private final String blockId;
    private PlacePhase phase;

    private enum PlacePhase { APPROACHING, EQUIPPING, PLACING, DONE }

    public PlaceTask(BlockPos targetPos, String blockId) {
        this.targetPos = targetPos;
        this.blockId = blockId;
        this.phase = PlacePhase.APPROACHING;
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        return switch (phase) {
            case APPROACHING -> tickApproaching(bot);
            case EQUIPPING   -> tickEquipping(bot);
            case PLACING     -> tickPlacing(bot);
            case DONE        -> TickResult.COMPLETE;
        };
    }

    private TickResult tickApproaching(AssistantBot bot) {
        Vec3 targetCenter = Vec3.atCenterOf(targetPos);
        double distance = bot.getPos().distanceTo(targetCenter);

        if (distance <= BlockHelper.REACH_DISTANCE) {
            NavigationHelper.stopMoving(bot);
            bot.getPathfinder().clearPath();
            phase = PlacePhase.EQUIPPING;
            return TickResult.CONTINUE;
        }

        LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
        NavigationHelper.navigateTo(bot, targetPos, NavigationHelper.WALK_SPEED);
        return TickResult.CONTINUE;
    }

    private TickResult tickEquipping(AssistantBot bot) {
        Identifier id = Identifier.parse(blockId);
        Item item = BuiltInRegistries.ITEM.getValue(id);

        boolean equipped = InventoryHelper.equipItem(bot.getFakePlayer(), item);
        if (!equipped) {
            return TickResult.FAILED;
        }

        phase = PlacePhase.PLACING;
        return TickResult.CONTINUE;
    }

    private TickResult tickPlacing(AssistantBot bot) {
        LookHelper.lookAt(bot.getFakePlayer(), Vec3.atCenterOf(targetPos));
        boolean placed = BlockHelper.placeBlock(bot, targetPos);

        if (placed) {
            phase = PlacePhase.DONE;
            return TickResult.COMPLETE;
        }

        return TickResult.FAILED;
    }

    @Override
    public String getStatusString() {
        return "placing " + blockId + " at " + targetPos.toShortString() + " (" + phase + ")";
    }
}
