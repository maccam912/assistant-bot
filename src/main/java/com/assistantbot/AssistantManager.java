package com.assistantbot;

import com.assistantbot.bot.AssistantBot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages all active assistant bot instances. One bot per player.
 */
public class AssistantManager {
    private static final AssistantManager INSTANCE = new AssistantManager();
    private final Map<UUID, AssistantBot> bots = new HashMap<>();

    public static AssistantManager getInstance() {
        return INSTANCE;
    }

    public AssistantBot getBot(UUID ownerUuid) {
        return bots.get(ownerUuid);
    }

    public AssistantBot summon(ServerPlayer owner) {
        UUID ownerUuid = owner.getUUID();

        if (bots.containsKey(ownerUuid)) {
            remove(ownerUuid);
        }

        AssistantBot bot = new AssistantBot(owner);
        bots.put(ownerUuid, bot);
        return bot;
    }

    public void remove(UUID ownerUuid) {
        AssistantBot bot = bots.remove(ownerUuid);
        if (bot != null) {
            bot.destroy();
        }
    }

    public void removeAll() {
        bots.values().forEach(AssistantBot::destroy);
        bots.clear();
    }

    public void tick(MinecraftServer server) {
        if (server.getTickCount() % 5 != 0) return;

        for (AssistantBot bot : new ArrayList<>(bots.values())) {
            bot.tick();
        }
    }

    public boolean hasBot(UUID ownerUuid) {
        return bots.containsKey(ownerUuid);
    }
}
