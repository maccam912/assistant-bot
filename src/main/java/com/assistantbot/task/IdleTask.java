package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;

public class IdleTask implements BotTask {
    @Override
    public TickResult tick(AssistantBot bot) {
        return TickResult.CONTINUE;
    }

    @Override
    public String getStatusString() { return "idle"; }
}
