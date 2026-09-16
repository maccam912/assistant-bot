package com.assistantbot.think;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThinkProjectTest {
    private ThinkProtocol.Request project(String instruction) {
        JsonObject state = new JsonObject();
        JsonArray messages = new JsonArray();
        if (instruction != null) {
            JsonObject text = new JsonObject();
            text.addProperty("text", instruction);
            messages.add(text);
        }
        state.add("new_owner_messages", messages);
        JsonObject focus = new JsonObject();
        focus.addProperty("x", -123);
        state.add("owner_looking_at", focus);
        JsonObject materials = new JsonObject();
        materials.addProperty("minecraft:oak_planks", 2);
        state.add("placeable_materials", materials);
        return ThinkProtocol.request(state, "jev-latest", List.of(), Map.of(
                "position", new ThinkProtocol.Work("place", -123, 71, 42, "selected material"),
                "resource", new ThinkProtocol.Work("dig", -125, 70, 40, "minecraft:stone"),
                "tree", new ThinkProtocol.Work("chop", -126, 70, 40, "minecraft:oak_log"),
                "craft", new ThinkProtocol.Work("craft", 0, 0, 0, "minecraft:oak_log")));
    }

    private ThinkProtocol.Evaluation evaluation(ThinkProtocol.Request request, String goal, String action, String work) {
        JsonObject response = ThinkFixtures.response(request, goal, action, "NONE");
        ThinkFixtures.choose(response, "work_project", work, 0.9);
        ThinkFixtures.choose(response, "material", "minecraft:oak_planks", 0.9);
        return ThinkProtocol.parse(response, request);
    }

    @Test void freeformPlacementCombinesOnlyOfferedPositionAndInventoryMaterial() {
        var request = project("Build a tall wooden arch over that path");
        var decision = ThinkProtocol.decide(ThinkProtocol.Goal.FOLLOW,
                evaluation(request, "PROJECT", "WORK", "position"), ThinkFixtures.config());
        assertEquals(ThinkProtocol.Goal.PROJECT, decision.goal());
        assertEquals(new ThinkProtocol.Work("place", -123, 71, 42, "minecraft:oak_planks"), decision.work());
        for (String invalid : List.of("unknown_position", "minecraft:diamond_block")) {
            var response = ThinkFixtures.response(request, "PROJECT", "WORK", "NONE");
            ThinkFixtures.choose(response, invalid.startsWith("minecraft:") ? "material" : "work_project", invalid, 1);
            assertThrows(IllegalArgumentException.class, () -> ThinkProtocol.parse(response, request));
        }
    }

    @Test void lowConfidenceMaterialStopsPlacementButDoesNotStopGathering() {
        var request = project("Build an arch");
        var response = ThinkFixtures.response(request, "PROJECT", "WORK", "NONE");
        ThinkFixtures.choose(response, "work_project", "position", .9);
        ThinkFixtures.choose(response, "material", "minecraft:oak_planks", .1);
        assertEquals(ThinkProtocol.Action.WAIT, ThinkProtocol.decide(ThinkProtocol.Goal.FOLLOW,
                ThinkProtocol.parse(response, request), ThinkFixtures.config()).action());
        ThinkFixtures.choose(response, "work_project", "resource", .9);
        var decision = ThinkProtocol.decide(ThinkProtocol.Goal.PROJECT, ThinkProtocol.parse(response, request), ThinkFixtures.config());
        assertEquals(ThinkProtocol.Action.WORK, decision.action());
        assertEquals("dig", decision.work().kind());
        ThinkFixtures.choose(response, "work_project", "craft", .9);
        assertEquals("craft", ThinkProtocol.decide(ThinkProtocol.Goal.PROJECT,
                ThinkProtocol.parse(response, request), ThinkFixtures.config()).work().kind());
    }

    @Test void woodCollectionCannotChooseArbitraryExcavationOrPlacement() {
        var options = project("Collect wood").body().getAsJsonObject("questions")
                .getAsJsonObject("work_wood").getAsJsonObject("criteria");
        assertTrue(options.has("tree"));
        assertFalse(options.has("resource"));
        assertFalse(options.has("position"));
        assertFalse(options.has("craft"));
    }

    @Test void gatheringAndRefinementsKeepOriginalGoalPastChatExpiryAndCompletionClearsIt() {
        ArrayList<CompletableFuture<ThinkProtocol.Evaluation>> futures = new ArrayList<>();
        var loop = new ThinkLoop(ThinkFixtures.config(), request -> {
            var future = new CompletableFuture<ThinkProtocol.Evaluation>(); futures.add(future); return future;
        });
        var initial = project("Build a wooden arch");
        loop.tick(0, 1, () -> initial);
        futures.getLast().complete(evaluation(initial, "PROJECT", "WORK", "position"));
        loop.tick(250, 1, () -> initial);
        assertEquals("Build a wooden arch", loop.goalInstructions().get(0).getAsJsonObject().get("text").getAsString());
        var refinement = project("Make it taller");
        loop.tick(1000, 2, () -> refinement);
        futures.getLast().complete(evaluation(refinement, "PROJECT", "WORK", "resource"));
        loop.tick(1250, 2, () -> refinement);
        assertEquals(2, loop.goalInstructions().size());
        assertEquals("dig", loop.decision().work().kind());
        var noChat = project(null);
        loop.tick(130000, 2, () -> noChat);
        futures.getLast().complete(evaluation(noChat, "KEEP", "WORK", "craft"));
        loop.tick(130250, 2, () -> noChat);
        assertEquals(ThinkProtocol.Goal.PROJECT, loop.decision().goal());
        assertEquals(2, loop.goalInstructions().size());
        assertEquals("craft", loop.decision().work().kind());
        loop.tick(131000, 2, () -> noChat);
        futures.getLast().complete(evaluation(noChat, "KEEP", "COMPLETE", "NONE"));
        loop.tick(131250, 2, () -> noChat);
        assertEquals(ThinkProtocol.Goal.HOLD, loop.decision().goal());
        assertTrue(loop.goalInstructions().isEmpty());
        assertTrue(loop.goalFocus().isJsonNull());
        assertEquals("COMPLETE", loop.takeReply());
        assertEquals("NONE", loop.takeReply());
    }

    @Test void staleCompletionCannotClearANewerInstructionAndHelpReplyIsOneShot() {
        ArrayList<CompletableFuture<ThinkProtocol.Evaluation>> futures = new ArrayList<>();
        var loop = new ThinkLoop(ThinkFixtures.config(), request -> {
            var future = new CompletableFuture<ThinkProtocol.Evaluation>(); futures.add(future); return future;
        });
        var request = project("Build an arch");
        loop.tick(0, 1, () -> request);
        futures.getLast().complete(evaluation(request, "PROJECT", "WORK", "position"));
        loop.tick(250, 1, () -> request);
        loop.tick(1000, 1, () -> request);
        futures.getLast().complete(evaluation(request, "KEEP", "COMPLETE", "NONE"));
        loop.tick(1250, 2, () -> request);
        assertEquals(ThinkProtocol.Goal.PROJECT, loop.decision().goal());
        assertFalse(loop.goalInstructions().isEmpty());
        loop.tick(2000, 2, () -> request);
        var response = ThinkFixtures.response(request, "KEEP", "WAIT", "NONE");
        ThinkFixtures.choose(response, "reply", "HELP", .95);
        futures.getLast().complete(ThinkProtocol.parse(response, request));
        loop.tick(2250, 2, () -> request);
        assertEquals("HELP", loop.takeReply());
        assertEquals("NONE", loop.takeReply());
        assertEquals(ThinkProtocol.Goal.PROJECT, loop.decision().goal());
    }

    @Test void releaseAndAskHelpAreDistinctFromCompletion() {
        var request = project("Build what you can; give up if needed");
        for (String action : List.of("RELEASE_GOAL", "ASK_HELP")) {
            var future = new CompletableFuture<ThinkProtocol.Evaluation>();
            var loop = new ThinkLoop(ThinkFixtures.config(), ignored -> future);
            loop.tick(0, 1, () -> request);
            future.complete(evaluation(request, "PROJECT", action, "NONE"));
            loop.tick(250, 1, () -> request);
            if (action.equals("RELEASE_GOAL")) {
                assertEquals("RELEASED", loop.takeReply());
                assertTrue(loop.goalInstructions().isEmpty());
            } else {
                assertEquals(ThinkProtocol.Goal.PROJECT, loop.decision().goal());
                assertFalse(loop.goalInstructions().isEmpty());
                assertEquals("NONE", loop.takeReply());
            }
        }
    }

    @Test void logConversionIsExplicitAndDoesNotInventRecipes() {
        assertEquals("minecraft:oak_planks", ThinkWork.planksForLog("minecraft:oak_log"));
        assertEquals("minecraft:dark_oak_planks", ThinkWork.planksForLog("minecraft:stripped_dark_oak_wood"));
        assertNull(ThinkWork.planksForLog("minecraft:stone"));
        assertNull(ThinkWork.planksForLog("mod:oak_log"));
    }
}
