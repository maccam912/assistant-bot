package com.assistantbot.command;

import com.assistantbot.AssistantManager;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.llm.BuildPlan;
import com.assistantbot.llm.BuildPlanRegistry;
import com.assistantbot.task.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
 *   /assistant summon              — spawn your personal bot
 *   /assistant dismiss             — remove your bot
 *   /assistant follow | come       — bot follows you
 *   /assistant stop                — bot goes idle
 *   /assistant mine <pos>          — mine block at position
 *   /assistant place <block> <pos> — place block
 *   /assistant deposit             — deposit inventory into nearest container
 *   /assistant plan <description>  — generate a build plan (LLM), returns plan ID
 *   /assistant execute <id>        — execute a stored plan at bot's current position
 *   /assistant plans               — list all available build plans
 *   /assistant build <description> — plan + auto-execute (convenience)
 *   /assistant status              — show current task and position
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
                .then(CommandManager.literal("plan")
                    .then(CommandManager.argument("description", StringArgumentType.greedyString())
                        .executes(AssistantCommand::plan)))
                .then(CommandManager.literal("execute")
                    .then(CommandManager.argument("id", IntegerArgumentType.integer(1))
                        .executes(AssistantCommand::execute)))
                .then(CommandManager.literal("plans")
                    .executes(AssistantCommand::listPlans))
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

        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String description = StringArgumentType.getString(ctx, "description");

        PlanTask planTask = new PlanTask(description, player);
        planTask.setAutoExecute(true);
        bot.setTask(planTask);
        ctx.getSource().sendFeedback(
                () -> Text.literal("§a[Assistant] Planning and building: " + description + " (asking LLM...)"),
                false);
        return 1;
    }

    private static int plan(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String description = StringArgumentType.getString(ctx, "description");

        bot.setTask(new PlanTask(description, player));
        ctx.getSource().sendFeedback(
                () -> Text.literal("§a[Assistant] Planning: " + description + " (asking LLM...)"),
                false);
        return 1;
    }

    private static int execute(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        int planId = IntegerArgumentType.getInteger(ctx, "id");
        BuildPlan plan = BuildPlanRegistry.getInstance().get(planId);

        if (plan == null) {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("§c[Assistant] No plan found with ID: " + planId),
                    false);
            return 0;
        }

        BlockPos origin = bot.getBlockPos();
        bot.setTask(new BuildTask(planId, origin));
        ctx.getSource().sendFeedback(
                () -> Text.literal("§a[Assistant] Executing plan #" + planId + " (" + plan.getDescription()
                        + " — " + plan.getBlockCount() + " blocks) at " + origin.toShortString()),
                false);
        return 1;
    }

    private static int listPlans(CommandContext<ServerCommandSource> ctx) {
        var plans = BuildPlanRegistry.getInstance().getAll();

        if (plans.isEmpty()) {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("§e[Assistant] No plans available. Use /assistant plan <description> to create one."),
                    false);
            return 1;
        }

        StringBuilder sb = new StringBuilder("§b[Assistant] Available plans:");
        for (BuildPlan plan : plans) {
            sb.append("\n  §f#").append(plan.getId())
              .append(": \"").append(plan.getDescription())
              .append("\" — ").append(plan.getBlockCount())
              .append(" blocks (by ").append(plan.getCreatorName()).append(")");
        }

        String output = sb.toString();
        ctx.getSource().sendFeedback(() -> Text.literal(output), false);
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
