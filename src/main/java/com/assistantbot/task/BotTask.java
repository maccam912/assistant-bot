package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;
import com.assistantbot.util.NavigationHelper;

/**
 * A discrete unit of bot behavior. Each task is a mini state machine
 * advanced by tick() calls every 5 game ticks (~250ms).
 */
public interface BotTask {
    TickResult tick(AssistantBot bot);
    default void onStart(AssistantBot bot) {}

    /**
     * Called when this task is being replaced or the bot is destroyed.
     * Stops movement by default to prevent the bot from drifting after
     * a task ends.
     */
    default void onStop(AssistantBot bot) {
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
    }

    default String getStatusString() { return "unknown"; }
}
