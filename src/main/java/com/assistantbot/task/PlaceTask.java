package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;
import com.assistantbot.util.BlockHelper;
import com.assistantbot.util.InventoryHelper;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

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
        Vec3d targetCenter = Vec3d.ofCenter(targetPos);
        double distance = bot.getPos().distanceTo(targetCenter);

        if (distance <= BlockHelper.REACH_DISTANCE) {
            phase = PlacePhase.EQUIPPING;
            return TickResult.CONTINUE;
        }

        LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
        NavigationHelper.moveToward(bot, targetCenter, NavigationHelper.WALK_SPEED);
        return TickResult.CONTINUE;
    }

    private TickResult tickEquipping(AssistantBot bot) {
        Identifier id = Identifier.of(blockId);
        Item item = Registries.ITEM.get(id);

        boolean equipped = InventoryHelper.equipItem(bot.getFakePlayer(), item);
        if (!equipped) {
            return TickResult.FAILED;
        }

        phase = PlacePhase.PLACING;
        return TickResult.CONTINUE;
    }

    private TickResult tickPlacing(AssistantBot bot) {
        LookHelper.lookAt(bot.getFakePlayer(), Vec3d.ofCenter(targetPos));
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
