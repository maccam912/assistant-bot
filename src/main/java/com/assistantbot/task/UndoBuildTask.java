package com.assistantbot.task;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

/** Restores the one-shot snapshot captured by the most recently started build. */
public final class UndoBuildTask implements BotTask {
    private final List<BuildUndoSnapshot.Entry> entries;
    private final BuildUndoSnapshot snapshot;
    private final BuildRateLimiter rateLimiter = new BuildRateLimiter();
    private int restored;

    public UndoBuildTask(BuildUndoSnapshot snapshot) {
        this.snapshot = snapshot;
        this.entries = snapshot.reverseEntries();
    }

    @Override
    public void onStart(AssistantBot bot) {
        AssistantMod.LOGGER.info("Undoing latest build: restoring {} changed positions", entries.size());
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        int budget = Math.max(1, rateLimiter.takeBlockBudget(bot.getBuildSpeedBlocksPerSecond()));
        for (int i = 0; i < budget && restored < entries.size(); i++) {
            BuildUndoSnapshot.Entry entry = entries.get(restored++);
            // Restore the recorded state directly. UPDATE_CLIENTS keeps the exact
            // old state while still making the rollback immediately visible.
            snapshot.world().setBlock(entry.pos(), entry.state(), Block.UPDATE_CLIENTS);
        }

        if (restored < entries.size()) return TickResult.CONTINUE;

        AssistantMod.LOGGER.info("Build undo complete: restored {} positions", restored);
        ServerPlayer owner = bot.getOwnerPlayer();
        if (owner != null && !owner.hasDisconnected()) {
            owner.sendSystemMessage(Component.literal(
                    "§a[Assistant] Undo complete — restored " + restored + " blocks to their pre-build state."));
        }
        return TickResult.COMPLETE;
    }

    @Override
    public String getStatusString() {
        return "undoing build: " + restored + "/" + entries.size() + " blocks restored";
    }
}
