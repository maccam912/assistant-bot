package com.assistantbot.think;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThinkProtocolTest {
    @Test void oneFanOutIncludesEveryGoalAndEveryCandidateThreat() {
        var request = ThinkProtocol.request(new JsonObject(), "jev-latest", List.of("a", "b", "c"));
        var questions = request.body().getAsJsonObject("questions");
        assertEquals(10, questions.size());
        for (var goal : ThinkProtocol.Goal.values()) assertTrue(questions.has("action_" + goal));
        assertTrue(questions.has("threat_c"));
        assertEquals(4, questions.getAsJsonObject("target").getAsJsonObject("criteria").size());
    }

    @Test void newGoalUsesItsSpeculativeActionInSameResponse() {
        var decision = ThinkProtocol.decide(ThinkProtocol.Goal.FOLLOW, ThinkFixtures.protect(), ThinkFixtures.config());
        assertEquals(ThinkProtocol.Goal.PROTECT, decision.goal());
        assertEquals(ThinkProtocol.Action.ATTACK, decision.action());
        assertEquals("zombie", decision.target());
    }

    @Test void protectRequiresThreatEvidenceButHuntCanClearNonAttackingHostiles() {
        var request = ThinkFixtures.request();
        var response = ThinkFixtures.response(request, "KEEP", "ATTACK", "zombie");
        response.getAsJsonObject("answers").getAsJsonObject("threat_zombie").addProperty("noul", 0.2);
        var evaluation = ThinkProtocol.parse(response, request);
        assertEquals(ThinkProtocol.Action.WAIT, ThinkProtocol.decide(ThinkProtocol.Goal.PROTECT, evaluation, ThinkFixtures.config()).action());
        assertEquals(ThinkProtocol.Action.ATTACK, ThinkProtocol.decide(ThinkProtocol.Goal.HUNT, evaluation, ThinkFixtures.config()).action());
    }

    @Test void uncertainGoalKeepsMemoryAndUncertainTargetCannotAttack() {
        var request = ThinkFixtures.request();
        var response = ThinkFixtures.response(request, "HUNT", "ATTACK", "zombie");
        var answers = response.getAsJsonObject("answers");
        answers.getAsJsonObject("goal").addProperty("confidence", 0.1);
        answers.getAsJsonObject("target").addProperty("confidence", 0.1);
        var decision = ThinkProtocol.decide(ThinkProtocol.Goal.PROTECT, ThinkProtocol.parse(response, request), ThinkFixtures.config());
        assertEquals(ThinkProtocol.Goal.PROTECT, decision.goal());
        assertEquals(ThinkProtocol.Action.WAIT, decision.action());
    }

    @Test void uncertainActionWaitsAndHoldCannotSelectAttack() {
        var request = ThinkFixtures.request();
        var response = ThinkFixtures.response(request, "PROTECT", "ATTACK", "zombie");
        response.getAsJsonObject("answers").getAsJsonObject("action_PROTECT").addProperty("confidence", 0.1);
        assertEquals(ThinkProtocol.Action.WAIT, ThinkProtocol.decide(ThinkProtocol.Goal.FOLLOW,
                ThinkProtocol.parse(response, request), ThinkFixtures.config()).action());
        assertFalse(request.body().getAsJsonObject("questions").getAsJsonObject("action_HOLD").getAsJsonObject("criteria").has("ATTACK"));
    }

    @Test void noHostilesNeedsNoTargetQuestion() {
        var request = ThinkProtocol.request(new JsonObject(), "jev-latest", List.of());
        var evaluation = ThinkProtocol.parse(ThinkFixtures.response(request, "PROTECT", "ATTACK", "NONE"), request);
        assertEquals(ThinkProtocol.Action.WAIT, ThinkProtocol.decide(ThinkProtocol.Goal.FOLLOW, evaluation, ThinkFixtures.config()).action());
    }

    @Test void invalidMissingOrOutOfRangeAnswersFailClosed() {
        var request = ThinkFixtures.request();
        for (String corruption : List.of("unknown_target", "missing", "bad_probability", "bad_confidence", "wrong_type")) {
            var response = ThinkFixtures.response(request, "PROTECT", "ATTACK", "zombie");
            var answers = response.getAsJsonObject("answers");
            switch (corruption) {
                case "unknown_target" -> answers.getAsJsonObject("target").addProperty("choice", "player");
                case "missing" -> answers.remove("goal");
                case "bad_probability" -> answers.getAsJsonObject("threat_zombie").addProperty("noul", 2);
                case "bad_confidence" -> answers.getAsJsonObject("goal").addProperty("confidence", "NaN");
                case "wrong_type" -> answers.getAsJsonObject("goal").addProperty("type", "noul");
            }
            assertThrows(IllegalArgumentException.class, () -> ThinkProtocol.parse(response, request), corruption);
        }
    }
}
