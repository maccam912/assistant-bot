package com.assistantbot.think;

import java.util.Locale;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;

/** Server-thread polling only: workers return data and never touch a bot or world. */
public final class ThinkLoop {
    private final TypesafeConfig config;
    private final Function<ThinkProtocol.Request, CompletableFuture<ThinkProtocol.Evaluation>> evaluate;
    private CompletableFuture<ThinkProtocol.Evaluation> pending;
    private ThinkProtocol.Decision decision = new ThinkProtocol.Decision(
            ThinkProtocol.Goal.FOLLOW, ThinkProtocol.Action.WAIT, "NONE", 0);
    private long intervalMs;
    private long nextRequestMs = Long.MIN_VALUE;
    private long sentAtMs;
    private long pendingRevision;
    private long consumedRevision;
    private long goalVersion;
    private long decisionVersion;
    private String reply = "NONE";
    private JsonArray goalInstructions = new JsonArray();
    private JsonArray pendingInstructions = new JsonArray();
    private JsonElement goalFocus = JsonNull.INSTANCE;
    private JsonElement pendingFocus = JsonNull.INSTANCE;
    private boolean pendingHasInstructions;
    private long decisionAtMs = Long.MIN_VALUE;
    private long latencyMs;
    private int failures;
    private boolean stopped;
    private boolean paused;
    private String status = "waiting for first decision";

    public ThinkLoop(TypesafeConfig config,
                     Function<ThinkProtocol.Request, CompletableFuture<ThinkProtocol.Evaluation>> evaluate) {
        this.config = config;
        this.evaluate = evaluate;
        intervalMs = config.intervalMs();
    }

    public void tick(long nowMs, long chatRevision, Supplier<ThinkProtocol.Request> snapshot) {
        if (stopped || paused) return;
        // A new instruction or an expired action lease immediately stops the old action.
        if (chatRevision > consumedRevision || (decisionAtMs != Long.MIN_VALUE
                && nowMs - decisionAtMs > Math.max(config.maxAgeMs(), intervalMs * 2))) hold();
        if (pending != null && pending.isDone()) {
            var completed = pending;
            pending = null;
            try {
                ThinkProtocol.Evaluation evaluation = completed.join();
                if (nowMs - sentAtMs > config.maxAgeMs() || pendingRevision != chatRevision) {
                    hold();
                    status = "discarded stale decision";
                } else {
                    // New goals require owner input; existing work goals may complete or be released.
                    if (!pendingHasInstructions) {
                        evaluation = new ThinkProtocol.Evaluation(new ThinkProtocol.Choice("KEEP", 1),
                                evaluation.actions(), evaluation.target(), evaluation.threats(),
                                new ThinkProtocol.Choice("NONE", 1), evaluation.wood(), evaluation.project());
                    } else if (!evaluation.goal().value().equals("KEEP")
                            && evaluation.goal().confidence() >= config.confidence()) {
                        goalVersion++;
                        if (!evaluation.goal().value().equals(decision.goal().name())) goalInstructions = new JsonArray();
                        for (var message : pendingInstructions) {
                            var saved = message.deepCopy().getAsJsonObject();
                            saved.add("focus_when_requested", pendingFocus.deepCopy());
                            goalInstructions.add(saved);
                        }
                        // Keep the original request and seven latest refinements even after chat expires.
                        while (goalInstructions.size() > 8) goalInstructions.remove(1);
                        goalFocus = pendingFocus.deepCopy();
                    }
                    if (pendingHasInstructions && evaluation.reply().confidence() >= config.confidence()) reply = evaluation.reply().value();
                    decision = ThinkProtocol.decide(decision.goal(), evaluation, config);
                    decisionVersion++;
                    if (decision.action() == ThinkProtocol.Action.COMPLETE || decision.action() == ThinkProtocol.Action.RELEASE_GOAL) {
                        reply = decision.action() == ThinkProtocol.Action.COMPLETE ? "COMPLETE" : "RELEASED";
                        decision = new ThinkProtocol.Decision(ThinkProtocol.Goal.HOLD, ThinkProtocol.Action.WAIT, "NONE", decision.confidence());
                        goalInstructions = new JsonArray();
                        goalFocus = JsonNull.INSTANCE;
                        goalVersion++;
                    }
                    consumedRevision = pendingRevision;
                    decisionAtMs = nowMs;
                    latencyMs = nowMs - sentAtMs;
                    failures = 0;
                    status = "ready";
                }
            } catch (RuntimeException e) { fail(nowMs, e); }
        } else if (pending != null && nowMs - sentAtMs > config.maxAgeMs()) {
            pending.cancel(true);
            pending = null;
            fail(nowMs, new IllegalStateException("decision expired"));
        }
        if (!paused && pending == null && nowMs >= nextRequestMs) {
            try {
                ThinkProtocol.Request request = snapshot.get();
                var messages = request.body().getAsJsonObject("state").getAsJsonArray("new_owner_messages");
                pendingInstructions = messages == null ? new JsonArray() : messages.deepCopy();
                var focus = request.body().getAsJsonObject("state").get("owner_looking_at");
                pendingFocus = focus == null ? JsonNull.INSTANCE : focus.deepCopy();
                pendingHasInstructions = chatRevision > consumedRevision && messages != null && !messages.isEmpty();
                sentAtMs = nowMs;
                pendingRevision = chatRevision;
                nextRequestMs = nowMs + intervalMs;
                pending = evaluate.apply(request);
            } catch (RuntimeException e) { fail(nowMs, e); }
        }
    }

    private void fail(long nowMs, Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        hold();
        failures = Math.min(10, failures + 1);
        long delay = Math.max(intervalMs, Math.min(30000, 1000L << (failures - 1)));
        if (error instanceof TypesafeClient.ApiException api) {
            paused = api.permanent();
            delay = Math.max(delay, api.retryMs());
            status = api.getMessage();
        } else {
            // Never expose response bodies, URLs, headers, chat, or credential-bearing exceptions.
            status = "request failed or expired";
        }
        status += paused ? "; fix configuration and restart /assistant think" : "; retry in " + delay + "ms";
        nextRequestMs = nowMs + delay;
    }

    private void hold() {
        decision = new ThinkProtocol.Decision(decision.goal(), ThinkProtocol.Action.WAIT, "NONE", 0);
    }

    public void suspend() {
        if (pending != null) pending.cancel(true);
        pending = null;
        decisionAtMs = Long.MIN_VALUE;
        hold();
    }

    public void stop() { stopped = true; suspend(); }
    public void setIntervalMs(long value) {
        if (value < 250 || value > 60000) throw new IllegalArgumentException("Interval must be 0.25–60 seconds.");
        intervalMs = value;
        if (failures == 0) nextRequestMs = Long.MIN_VALUE;
    }
    public JsonArray goalInstructions() { return goalInstructions.deepCopy(); }
    public JsonElement goalFocus() { return goalFocus.deepCopy(); }
    public boolean canReact(long nowMs) {
        return !stopped && !paused && failures == 0 && decisionAtMs != Long.MIN_VALUE
                && nowMs - decisionAtMs <= Math.max(config.maxAgeMs(), intervalMs * 2);
    }
    public String takeReply() { String result = reply; reply = "NONE"; return result; }
    public long intervalMs() { return intervalMs; }
    public long consumedRevision() { return consumedRevision; }
    public long decisionVersion() { return decisionVersion; }
    public long goalVersion() { return goalVersion; }
    public ThinkProtocol.Decision decision() { return decision; }
    public String status() {
        return String.format(Locale.ROOT, "thinking: %s / %s (confidence %.2f, %dms, every %.2fs; %s%s)",
                decision.goal(), decision.action(), decision.confidence(), latencyMs, intervalMs / 1000.0,
                status, pending != null ? ", evaluating" : "");
    }
}
