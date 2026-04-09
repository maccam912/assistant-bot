package com.assistantbot.command;

import com.assistantbot.AssistantManager;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.task.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Brigadier command tree for /assistant.
 *
 * Commands:
 *   /assistant summon          — spawn your personal bot
 *   /assistant dismiss         — remove your bot
 *   /assistant follow | come   — bot follows you
 *   /assistant stop            — bot goes idle
 *   /assistant mine <pos>      — mine block at position
 *   /assistant place <block> <pos> — place block
 *   /assistant deposit         — deposit inventory into nearest container
 *   /assistant build <description> — ask LLM to build something
 *   /assistant status          — show current task and position
 */
public class AssistantCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("assistant")
                .then(CommandManager.literal("summon")
                    .executes(AssistantCommand::summon))
                .then(CommandManager.literal("dismiss")
                    .executes(AssistantCommand::dismiss))
                .then(CommandManager.literal("follow")
                    .executes(AssistantCommand::follow))
                .then(CommandManager.literal("come")
                    .executes(AssistantCommand::follow))
                .then(CommandManager.literal("stop")
                    .executes(AssistantCommand::stop))
                .then(CommandManager.literal("mine")
                    .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                        .executes(AssistantCommand::mine)))
                .then(CommandManager.literal("place")
                    .then(CommandManager.argument("block", StringArgumentType.word())
                        .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                            .executes(AssistantCommand::place))))
                .then(CommandManager.literal("deposit")
                    .executes(AssistantCommand::deposit))
                .then(CommandManager.literal("build")
                    .then(CommandManager.argument("description", StringArgumentType.greedyString())
                        .executes(AssistantCommand::build)))
                .then(CommandManager.literal("status")
                    .executes(AssistantCommand::status))
        );
    }

    private static int summon(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        AssistantManager.getInstance().summon(player);
        ctx.getSource().sendFeedback(() -> Text.literal("§a[Assistant] Bot summoned!"), false);
        return 1;
    }

    private static int dismiss(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        if (!AssistantManager.getInstance().hasBot(player.getUuid())) {
            ctx.getSource().sendFeedback(() -> Text.literal("§c[Assistant] No bot to dismiss."), false);
            return 0;
        }

        AssistantManager.getInstance().remove(player.getUuid());
        ctx.getSource().sendFeedback(() -> Text.literal("§a[Assistant] Bot dismissed."), false);
        return 1;
    }

    private static int follow(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        bot.setTask(new FollowTask());
        ctx.getSource().sendFeedback(() -> Text.literal("§a[Assistant] Following you!"), false);
        return 1;
    }

    private static int stop(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        bot.setTask(new IdleTask());
        ctx.getSource().sendFeedback(() -> Text.literal("§a[Assistant] Stopping."), false);
        return 1;
    }

    private static int mine(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        BlockPos pos = BlockPosArgumentType.getBlockPos(ctx, "pos");
        bot.setTask(new MineTask(pos));
        ctx.getSource().sendFeedback(
                () -> Text.literal("§a[Assistant] Mining at " + pos.toShortString()), false);
        return 1;
    }

    private static int place(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        String blockId = StringArgumentType.getString(ctx, "block");
        BlockPos pos = BlockPosArgumentType.getBlockPos(ctx, "pos");

        if (!blockId.contains(":")) {
            blockId = "minecraft:" + blockId;
        }

        String finalBlockId = blockId;
        bot.setTask(new PlaceTask(pos, finalBlockId));
        ctx.getSource().sendFeedback(
                () -> Text.literal("§a[Assistant] Placing " + finalBlockId + " at " + pos.toShortString()),
                false);
        return 1;
    }

    private static int deposit(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        bot.setTask(new DepositTask());
        ctx.getSource().sendFeedback(
                () -> Text.literal("§a[Assistant] Depositing items to nearest container..."), false);
        return 1;
    }

    private static int build(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        String description = StringArgumentType.getString(ctx, "description");
        BlockPos origin = bot.getBlockPos();

        bot.setTask(new BuildTask(description, origin));
        ctx.getSource().sendFeedback(
                () -> Text.literal("§a[Assistant] Building: " + description + " (asking LLM...)"),
                false);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        String status = bot.getStatusString();
        BlockPos pos = bot.getBlockPos();
        ctx.getSource().sendFeedback(
                () -> Text.literal("§b[Assistant] Status: " + status + " | Pos: " + pos.toShortString()),
                false);
        return 1;
    }

    private static AssistantBot requireBot(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return null;

        AssistantBot bot = AssistantManager.getInstance().getBot(player.getUuid());
        if (bot == null) {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("§c[Assistant] No bot summoned. Use /assistant summon first."),
                    false);
            return null;
        }
        return bot;
    }
}
