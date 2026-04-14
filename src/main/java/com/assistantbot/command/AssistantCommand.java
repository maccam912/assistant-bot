package com.assistantbot.command;

import com.assistantbot.AssistantManager;
import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.llm.BuildPlan;
import com.assistantbot.llm.BuildPlanRegistry;
import com.assistantbot.llm.BuildStructure;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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
 *   /assistant import <url> <desc> — import a VXB-1 plan from a URL
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
                .then(CommandManager.literal("import")
                    .then(CommandManager.argument("url", StringArgumentType.word())
                        .then(CommandManager.argument("description", StringArgumentType.greedyString())
                            .executes(AssistantCommand::importPlan))))
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

    private static int importPlan(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String url = StringArgumentType.getString(ctx, "url");
        String description = StringArgumentType.getString(ctx, "description");
        String creatorName = player.getName().getString();

        // Validate URL format
        URI uri;
        try {
            uri = URI.create(url);
            if (uri.getScheme() == null || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))) {
                ctx.getSource().sendFeedback(
                        () -> Text.literal("§c[Assistant] Invalid URL — must start with http:// or https://"),
                        false);
                return 0;
            }
        } catch (Exception e) {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("§c[Assistant] Invalid URL: " + e.getMessage()),
                    false);
            return 0;
        }

        ctx.getSource().sendFeedback(
                () -> Text.literal("§e[Assistant] Importing plan from URL..."),
                false);

        // Fetch, parse, sort, and store on a background thread
        var server = ctx.getSource().getServer();
        CompletableFuture.supplyAsync(() -> {
            try {
                HttpClient httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    return "§c[Assistant] Import failed: HTTP " + response.statusCode();
                }

                String content = response.body();
                BuildStructure structure = BuildStructure.parse(content);

                if (structure.getBlocks().isEmpty()) {
                    return "§c[Assistant] Import failed: parsed structure has no blocks";
                }

                var sortedBlocks = BuildStructure.sortBlocksBFS(structure.getBlocks());
                int planId = BuildPlanRegistry.getInstance().store(description, creatorName, sortedBlocks);

                AssistantMod.LOGGER.info("Imported plan #{} from URL ({} blocks, description: \"{}\")",
                        planId, sortedBlocks.size(), description);

                return "§a[Assistant] Plan imported! ID: " + planId + " (" + description
                        + " — " + sortedBlocks.size() + " blocks)";

            } catch (IllegalArgumentException parseError) {
                AssistantMod.LOGGER.warn("Import parse failed: {}", parseError.getMessage());
                return "§c[Assistant] Import failed — VXB-1 parse error: " + parseError.getMessage();
            } catch (Exception e) {
                AssistantMod.LOGGER.warn("Import fetch failed: {}", e.getMessage());
                return "§c[Assistant] Import failed: " + e.getMessage();
            }
        }).thenAccept(message -> {
            // Send result back on the server thread
            server.execute(() -> {
                if (!player.isDisconnected()) {
                    player.sendMessage(Text.literal(message));
                }
            });
        });

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
