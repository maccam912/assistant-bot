package com.assistantbot.task;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.llm.BuildPlanRegistry;
import com.assistantbot.llm.BuildStructure;
import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.LlmClient;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Plan phase of the build pipeline: requests a structure from the LLM,
 * validates block IDs, BFS-sorts for placement order, and stores the
 * result in BuildPlanRegistry. Does NOT place any blocks.
 *
 * Phases: REQUESTING -> SORTING -> STORING -> DONE
 */
public class PlanTask implements BotTask {

    private enum PlanPhase { REQUESTING, SORTING, STORING, DONE }

    private static final int LLM_WAIT_LOG_INTERVAL = 24;

    private final String description;
    private final ServerPlayerEntity commandSource;
    private final String creatorName;

    private PlanPhase phase;
    private CompletableFuture<BuildStructure> llmFuture;
    private final LlmClient llmClient;
    private int llmWaitTicks;

    // Structure state
    private BuildStructure structure;

    // Sorting result
    private List<BlockEntry> sortedBlocks;

    // Output
    private int lastPlanId = -1;
    private boolean autoExecute = false;

    public PlanTask(String description, ServerPlayerEntity commandSource) {
        this.description = description;
        this.commandSource = commandSource;
        this.creatorName = commandSource.getName().getString();
        this.phase = PlanPhase.REQUESTING;
        this.llmClient = new LlmClient();
        this.llmWaitTicks = 0;
    }

    @Override
    public void onStart(AssistantBot bot) {
        AssistantMod.LOGGER.info("PlanTask starting: \"{}\" (creator: {})", description, creatorName);
        llmFuture = llmClient.requestStructureAsync(description);
    }

    @Override
    public void onStop(AssistantBot bot) {
        if (llmFuture != null && !llmFuture.isDone()) {
            llmFuture.cancel(true);
            AssistantMod.LOGGER.info("PlanTask cancelled, LLM request aborted");
        }
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        return switch (phase) {
            case REQUESTING -> tickRequesting();
            case SORTING -> tickSorting();
            case STORING -> tickStoring();
            case DONE -> TickResult.COMPLETE;
        };
    }

    // --- Phase: REQUESTING ---

    private TickResult tickRequesting() {
        if (!llmFuture.isDone()) {
            llmWaitTicks++;
            if (llmWaitTicks % LLM_WAIT_LOG_INTERVAL == 0) {
                int waitSeconds = llmWaitTicks * 5 / 20;
                AssistantMod.LOGGER.info("Still waiting for LLM response... ({}s elapsed, description: \"{}\")",
                        waitSeconds, description);
            }
            return TickResult.CONTINUE;
        }

        try {
            structure = llmFuture.join();
            AssistantMod.LOGGER.info("LLM returned {} blocks for \"{}\"",
                    structure.getBlocks().size(), description);

            if (structure.getBlocks().isEmpty()) {
                AssistantMod.LOGGER.warn("LLM returned empty structure");
                sendMessage("§c[Assistant] Plan failed: LLM returned empty structure.");
                return TickResult.FAILED;
            }

            // Print any architectural warnings to the player
            if (structure.getDiagnostics() != null && structure.getDiagnostics().hasWarnings()) {
                sendMessage("§e[Assistant] Warnings in plan layout:");
                for (var d : structure.getDiagnostics().getWarnings()) {
                    sendMessage("§e  - " + d.message() + (d.lineNum() != null ? " (Line " + d.lineNum() + ")" : ""));
                }
            }

            return transitionToSorting();
        } catch (Exception e) {
            String causeMsg = e.getMessage();
            if (e.getCause() != null && e.getCause().getMessage() != null) {
                causeMsg = e.getCause().getMessage();
            }
            AssistantMod.LOGGER.error("LLM request failed: {}", causeMsg);
            if (causeMsg != null && causeMsg.contains("\n")) {
                for (String line : causeMsg.split("\n")) {
                    sendMessage("§c" + line);
                }
            } else {
                sendMessage("§c[Assistant] Plan failed: " + (causeMsg != null ? causeMsg : "unknown error"));
            }
            return TickResult.FAILED;
        }
    }



    private TickResult transitionToSorting() {
        sortedBlocks = BuildStructure.sortBlocksBFS(structure.getBlocks());
        phase = PlanPhase.SORTING;
        return TickResult.CONTINUE;
    }

    // --- Phase: SORTING (instant transition to STORING) ---

    private TickResult tickSorting() {
        // Sorting already done in transitionToSorting. Store the plan.
        phase = PlanPhase.STORING;
        return TickResult.CONTINUE;
    }

    // --- Phase: STORING ---

    private TickResult tickStoring() {
        lastPlanId = BuildPlanRegistry.getInstance().store(description, creatorName, sortedBlocks);
        AssistantMod.LOGGER.info("Plan stored with ID {} ({} blocks, description: \"{}\")",
                lastPlanId, sortedBlocks.size(), description);
        sendMessage("§a[Assistant] Plan ready! ID: " + lastPlanId + " (" + description
                + " — " + sortedBlocks.size() + " blocks)");
        phase = PlanPhase.DONE;
        return TickResult.COMPLETE;
    }

    // --- Helpers ---

    private void sendMessage(String message) {
        if (commandSource != null && !commandSource.isDisconnected()) {
            commandSource.sendMessage(Text.literal(message));
        }
    }

    // --- Accessors ---

    public int getLastPlanId() { return lastPlanId; }
    public boolean isAutoExecute() { return autoExecute; }
    public void setAutoExecute(boolean autoExecute) { this.autoExecute = autoExecute; }

    @Override
    public String getStatusString() {
        return switch (phase) {
            case REQUESTING -> {
                int waitSeconds = llmWaitTicks * 5 / 20;
                yield "planning: waiting for LLM... (" + waitSeconds + "s, " + description + ")";
            }
            case SORTING -> "planning: sorting placement order...";
            case STORING -> "planning: storing plan...";
            case DONE -> "planning: complete (plan #" + lastPlanId + ")";
        };
    }
}
