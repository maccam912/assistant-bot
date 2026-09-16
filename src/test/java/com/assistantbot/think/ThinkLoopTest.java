package com.assistantbot.think;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ThinkLoopTest {
    private final ArrayList<CompletableFuture<ThinkProtocol.Evaluation>> requests = new ArrayList<>();
    private final AtomicInteger captures = new AtomicInteger();
    private final ThinkLoop loop = new ThinkLoop(ThinkFixtures.config(), request -> {
        var future = new CompletableFuture<ThinkProtocol.Evaluation>();
        requests.add(future);
        return future;
    });
    private void tick(long now, long revision) {
        loop.tick(now, revision, () -> { captures.incrementAndGet(); return ThinkFixtures.request(); });
    }

    @Test void cadenceAndSingleFlightDoNotBlockTicksOrQueueSnapshots() {
        tick(0, 1);
        tick(250, 1);
        tick(1000, 1);
        assertEquals(1, requests.size());
        assertEquals(1, captures.get());
        requests.getFirst().complete(ThinkFixtures.protect());
        tick(1250, 1);
        assertEquals(2, requests.size());
        assertEquals(ThinkProtocol.Action.ATTACK, loop.decision().action());
        tick(1500, 1);
        assertEquals(2, requests.size());
    }

    @Test void newerChatDiscardsOldInstructionAndStopsPriorAction() {
        tick(0, 1);
        requests.getFirst().complete(ThinkFixtures.protect());
        tick(250, 2);
        assertEquals(ThinkProtocol.Goal.FOLLOW, loop.decision().goal());
        assertEquals(0, loop.consumedRevision());
        tick(1000, 2);
        requests.get(1).complete(ThinkFixtures.protect());
        tick(1250, 2);
        assertEquals(2, loop.consumedRevision());
        assertEquals(ThinkProtocol.Action.ATTACK, loop.decision().action());
        tick(1500, 3);
        assertEquals(ThinkProtocol.Action.WAIT, loop.decision().action());
    }

    @Test void stopCancelsTransportAndLateCompletionCannotRestartThinking() {
        tick(0, 0);
        loop.stop();
        assertTrue(requests.getFirst().isCancelled());
        assertFalse(requests.getFirst().complete(ThinkFixtures.protect()));
        tick(10000, 0);
        assertEquals(1, requests.size());
        assertEquals(ThinkProtocol.Action.WAIT, loop.decision().action());
    }

    @Test void suspensionCancelsOutstandingWorkButAllowsFreshResume() {
        tick(0, 1);
        loop.suspend();
        assertTrue(requests.getFirst().isCancelled());
        tick(1000, 1);
        assertEquals(2, requests.size());
    }

    @Test void slowResponsesAreCancelledAndRetryUsesFreshSnapshotAfterBackoff() {
        tick(0, 0);
        tick(3250, 0);
        assertTrue(requests.getFirst().isCancelled());
        assertEquals(1, captures.get());
        tick(4000, 0);
        assertEquals(1, captures.get());
        tick(4250, 0);
        assertEquals(2, captures.get());
    }

    @Test void finishedButStaleResponsesCannotChangeGoal() {
        tick(0, 0);
        requests.getFirst().complete(ThinkFixtures.protect());
        tick(3250, 0);
        assertEquals(ThinkProtocol.Goal.FOLLOW, loop.decision().goal());
    }

    @Test void rateLimitHonorsRetryAfterAndAuthFailurePauses() {
        tick(0, 0);
        requests.getFirst().completeExceptionally(new TypesafeClient.ApiException(429, 5000));
        tick(250, 0);
        tick(5000, 0);
        assertEquals(1, requests.size());
        tick(5250, 0);
        requests.get(1).completeExceptionally(new TypesafeClient.ApiException(401, 0));
        tick(5500, 0);
        tick(60000, 0);
        assertEquals(2, requests.size());
        assertTrue(loop.status().contains("HTTP 401"));
    }

    @Test void liveIntervalUpdatesAndFailuresExpirePreviouslyAcceptedActions() {
        tick(0, 1);
        requests.getFirst().complete(ThinkFixtures.protect());
        tick(250, 1);
        assertEquals(ThinkProtocol.Action.ATTACK, loop.decision().action());
        loop.setIntervalMs(500);
        tick(500, 1);
        assertEquals(2, requests.size());
        requests.get(1).completeExceptionally(new IllegalStateException("private response body"));
        tick(750, 1);
        assertEquals(ThinkProtocol.Action.WAIT, loop.decision().action());
        assertFalse(loop.status().contains("private response body"));
        assertThrows(IllegalArgumentException.class, () -> loop.setIntervalMs(0));
    }

    @Test void worldStateCannotChangeGoalWithoutNewOwnerInput() {
        tick(0, 0);
        requests.getFirst().complete(ThinkFixtures.protect());
        tick(250, 0);
        assertEquals(ThinkProtocol.Goal.FOLLOW, loop.decision().goal());
        assertEquals(0, loop.goalVersion());
    }

    @Test void repeatedHoldInstructionRefreshesAnchorVersionButConsumedChatDoesNot() {
        var request = ThinkFixtures.request();
        var hold = ThinkProtocol.parse(ThinkFixtures.response(request, "HOLD", "WAIT", "NONE"), request);
        tick(0, 1);
        requests.getFirst().complete(hold);
        tick(250, 1);
        assertEquals(1, loop.goalVersion());
        tick(1000, 1);
        requests.get(1).complete(hold);
        tick(1250, 1);
        assertEquals(1, loop.goalVersion());
        tick(2000, 2);
        requests.get(2).complete(hold);
        tick(2250, 2);
        assertEquals(2, loop.goalVersion());
    }
}
