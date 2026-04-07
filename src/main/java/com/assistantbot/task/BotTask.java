package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;

/**
 * A discrete unit of bot behavior. Each task is a mini state machine
 * advanced by tick() calls every 5 game ticks (~250ms).
 */
public interface BotTask {
    TickResult tick(AssistantBot bot);
    default void onStart(AssistantBot bot) {}
    default void onStop(AssistantBot bot) {}
    default String getStatusString() { return "unknown"; }
}
