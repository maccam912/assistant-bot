package com.assistantbot.command;

import com.assistantbot.AssistantManager;
import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.gui.BotActions;
import com.assistantbot.gui.BotMenu;
import com.assistantbot.gui.BotRemoteItem;
import com.assistantbot.llm.BlockIdResolver;
import com.assistantbot.llm.BuildPlan;
import com.assistantbot.llm.BuildPlanRegistry;
import com.assistantbot.llm.BuildStructure;
import com.assistantbot.task.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
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
 * Every subcommand may be prefixed with an optional online player name so the
 * command can be issued on someone else's behalf (e.g. from rcon/console, or to
 * help a player who has trouble typing). When the name is omitted, the command
 * acts as the executing player.
 *
 *   /assistant summon                   — spawn your personal bot
 *   /assistant <player> summon          — spawn <player>'s bot, for them
 *   /assistant <player> follow | come   — <player>'s bot follows <player>
 *   /assistant <player> build <desc>    — plan + auto-execute for <player>
 *   ... and so on for every subcommand below.
 *
 * Subcommands:
 *   summon                  — spawn the bot
 *   dismiss                 — remove the bot
 *   follow | come           — bot follows the target player
 *   stop                    — bot goes idle
 *   mine <pos>              — mine block at position
 *   place <block> <pos>    — place block
 *   deposit                 — deposit inventory into nearest container
 *   plan <description>      — generate a build plan (LLM), returns plan ID
 *   execute <id>           — execute a stored plan at bot's current position
 *   plans                   — list all available build plans
 *   import <url> <desc>    — import a VXB-1 plan from a URL
 *   build <description>    — plan + auto-execute (convenience)
 *   status                  — show current task and position
 */
public class AssistantCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // The subcommand tree is attached twice: once directly under /assistant
        // (acts as the executing player) and once behind an optional leading
        // player-name argument (acts on that player's behalf).
        dispatcher.register(
            subcommands(CommandManager.literal("assistant"))
                .then(subcommands(CommandManager.argument("player", EntityArgumentType.player())))
        );
    }

    /**
     * Attaches every /assistant subcommand to the given builder and returns it,
     * so the same tree can hang off both the root literal and the optional
     * {@code <player>} argument node.
     */
    private static <T extends ArgumentBuilder<ServerCommandSource, T>> T subcommands(T parent) {
        return parent
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
                    .then(CommandManager.argument("url", StringArgumentType.string())
                        .then(CommandManager.argument("description", StringArgumentType.greedyString())
                            .executes(AssistantCommand::importPlan))))
                .then(CommandManager.literal("status")
                    .executes(AssistantCommand::status))
                .then(CommandManager.literal("menu")
                    .executes(AssistantCommand::menu))
                .then(CommandManager.literal("remote")
                    .executes(AssistantCommand::remote));
    }

    /**
     * Resolves the player a command should act on. If a {@code <player>} name was
     * given it must match a single online player; otherwise the command acts as
     * the executing player. Returns {@code null} (after sending an error) when the
     * named player can't be found, or when no name was given and there is no
     * executing player (e.g. run from rcon/console).
     */
    private static ServerPlayerEntity resolveTarget(CommandContext<ServerCommandSource> ctx) {
        try {
            return EntityArgumentType.getPlayer(ctx, "player");
        } catch (IllegalArgumentException noNameGiven) {
            // No <player> argument on this path — act as the executor.
            ServerPlayerEntity self = ctx.getSource().getPlayer();
            if (self == null) {
                ctx.getSource().sendError(Text.literal(
                        "[Assistant] No player context. From console/rcon, name a player: "
                        + "/assistant <player> <command>"));
            }
            return self;
        } catch (CommandSyntaxException notFound) {
            ctx.getSource().sendError(Text.literal("[Assistant] " + notFound.getMessage()));
            return null;
        }
    }

    private static int summon(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = resolveTarget(ctx);
        if (player == null) return 0;

        BotActions.summon(player);
        return 1;
    }

    private static int dismiss(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = resolveTarget(ctx);
        if (player == null) return 0;

        BotActions.dismiss(player);
        return 1;
    }

    private static int follow(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = resolveTarget(ctx);
        if (player == null) return 0;

        BotActions.follow(player);
        return 1;
    }

    private static int stop(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = resolveTarget(ctx);
        if (player == null) return 0;

        BotActions.stop(player);
        return 1;
    }

    private static int menu(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = resolveTarget(ctx);
        if (player == null) return 0;

        BotMenu.open(player);
        return 1;
    }

    private static int remote(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = resolveTarget(ctx);
        if (player == null) return 0;

        BotRemoteItem.giveTo(player);
        ctx.getSource().sendFeedback(() -> Text.literal("§a[Assistant] Bot Remote added to your inventory."), false);
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

        ServerPlayerEntity player = resolveTarget(ctx);
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

        ServerPlayerEntity player = resolveTarget(ctx);
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
        ServerPlayerEntity player = resolveTarget(ctx);
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
                var diagResult = com.assistantbot.llm.VxbDiagnostics.run(content);
                if (hasNonMechanicalBlockers(diagResult)) {
                    return "§c[Assistant] Import failed — VXB-1 blocker errors:\n" + diagResult.getLlmReport();
                }

                StringBuilder resultMsg = new StringBuilder();
                if (diagResult.hasWarnings()) {
                    resultMsg.append("§e[Assistant] Warnings in imported plan:\n");
                    for (var d : diagResult.getWarnings()) {
                        resultMsg.append("§e  - ").append(d.message()).append(d.lineNum() != null ? " (Line " + d.lineNum() + ")" : "").append("\n");
                    }
                }

                BuildStructure structure = BuildStructure.parse(content);
                var replacements = BlockIdResolver.mechanicalReplacements(structure.getUniqueBlockIds());
                for (var replacement : replacements.entrySet()) {
                    AssistantMod.LOGGER.warn("Mechanically replacing invalid imported block ID: {} -> {}",
                            replacement.getKey(), replacement.getValue());
                    structure.replaceBlockId(replacement.getKey(), replacement.getValue());
                }

                if (structure.getBlocks().isEmpty()) {
                    return "§c[Assistant] Import failed: parsed structure has no blocks";
                }

                var sortedBlocks = BuildStructure.sortBlocksBFS(structure.getBlocks());
                int planId = BuildPlanRegistry.getInstance().store(description, creatorName, sortedBlocks);

                AssistantMod.LOGGER.info("Imported plan #{} from URL ({} blocks, description: \"{}\")",
                        planId, sortedBlocks.size(), description);

                resultMsg.append("§a[Assistant] Plan imported! ID: ").append(planId).append(" (").append(description)
                        .append(" — ").append(sortedBlocks.size()).append(" blocks)");
                if (!replacements.isEmpty()) {
                    resultMsg.append("\n§e[Assistant] Corrected ").append(replacements.size())
                            .append(" invalid block ID(s) by closest Levenshtein match.");
                }
                return resultMsg.toString();

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
                    if (message.contains("\n")) {
                        for (String line : message.split("\n")) {
                            player.sendMessage(Text.literal(line));
                        }
                    } else {
                        player.sendMessage(Text.literal(message));
                    }
                }
            });
        });

        return 1;
    }

    private static boolean hasNonMechanicalBlockers(com.assistantbot.llm.VxbDiagnostics.DiagnosticResult result) {
        for (var diagnostic : result.getBlockers()) {
            if (!diagnostic.checkName().equals("Invalid Minecraft Block ID")) {
                return true;
            }
        }
        return false;
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = resolveTarget(ctx);
        if (player == null) return 0;

        BotActions.status(player);
        return 1;
    }

    private static AssistantBot requireBot(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = resolveTarget(ctx);
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
