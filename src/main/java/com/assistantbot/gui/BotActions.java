package com.assistantbot.gui;

import com.assistantbot.AssistantManager;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.task.FollowTask;
import com.assistantbot.task.IdleTask;
import com.assistantbot.task.PlanTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shared bot action logic so the GUI menu and the {@code /assistant} commands
 * have a single source of truth. Each method sends the same chat feedback the
 * commands do, addressed to the acting player.
 */
public final class BotActions {
    private BotActions() {}

    public static void summon(ServerPlayer player) {
        AssistantManager.getInstance().summon(player);
        BotRemoteItem.giveTo(player);
        player.sendSystemMessage(Component.literal("§a[Assistant] Bot summoned!"), false);
    }

    public static void dismiss(ServerPlayer player) {
        if (!AssistantManager.getInstance().hasBot(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§c[Assistant] No bot to dismiss."), false);
            return;
        }
        AssistantManager.getInstance().remove(player.getUUID());
        player.sendSystemMessage(Component.literal("§a[Assistant] Bot dismissed."), false);
    }

    public static void follow(ServerPlayer player) {
        AssistantBot bot = requireBot(player);
        if (bot == null) return;
        bot.setTask(new FollowTask());
        player.sendSystemMessage(Component.literal("§a[Assistant] Following you!"), false);
    }

    public static void stop(ServerPlayer player) {
        AssistantBot bot = requireBot(player);
        if (bot == null) return;
        bot.setTask(new IdleTask());
        player.sendSystemMessage(Component.literal("§a[Assistant] Stopping."), false);
    }

    /** Plan + auto-execute a build, mirroring {@code /assistant build}. */
    public static void build(ServerPlayer player, String description) {
        AssistantBot bot = requireBot(player);
        if (bot == null) return;
        PlanTask planTask = new PlanTask(description, player);
        planTask.setAutoExecute(true);
        bot.setTask(planTask);
        player.sendSystemMessage(
                Component.literal("§a[Assistant] Planning and building: " + description + " (asking LLM...)"),
                false);
    }

    public static void status(ServerPlayer player) {
        AssistantBot bot = requireBot(player);
        if (bot == null) return;
        String status = bot.getStatusString();
        BlockPos pos = bot.getBlockPos();
        player.sendSystemMessage(
                Component.literal("§b[Assistant] Status: " + status + " | Pos: " + pos.toShortString()),
                false);
    }

    /**
     * Returns the player's bot, or null after sending the standard
     * "no bot summoned" message.
     */
    public static AssistantBot requireBot(ServerPlayer player) {
        AssistantBot bot = AssistantManager.getInstance().getBot(player.getUUID());
        if (bot == null) {
            player.sendSystemMessage(
                    Component.literal("§c[Assistant] No bot summoned. Use /assistant summon first."),
                    false);
        }
        return bot;
    }
}
