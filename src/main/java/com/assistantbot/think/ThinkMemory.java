package com.assistantbot.think;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;

/** Bounded, owner-only chat history. All access is on the server thread. */
public final class ThinkMemory {
    private record Message(long id, long timeMs, String text) { }
    private final ArrayDeque<Message> messages = new ArrayDeque<>();
    private long revision;

    public void add(String text, long nowMs) {
        if (text == null || text.isBlank()) return;
        messages.addLast(new Message(++revision, nowMs, text.substring(0, Math.min(512, text.length()))));
        while (messages.size() > 12) messages.removeFirst();
        prune(nowMs);
    }

    public long revision() { return revision; }

    public JsonArray snapshot(long nowMs, long afterId) {
        prune(nowMs);
        JsonArray result = new JsonArray();
        for (Message message : messages) {
            if (message.id <= afterId) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("id", message.id);
            entry.addProperty("age_seconds", Math.max(0, nowMs - message.timeMs) / 1000.0);
            entry.addProperty("text", message.text);
            result.add(entry);
        }
        return result;
    }

    private void prune(long nowMs) {
        while (!messages.isEmpty() && nowMs - messages.getFirst().timeMs > 120000) messages.removeFirst();
    }
}
