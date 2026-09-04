package com.assistantbot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LlmClientProgressTest {

    @Test
    void summaryIncludesStepAndDetail() {
        var progress = new LlmClient.Progress("repairing", "turn 2/12 — applying a draft patch");

        assertEquals("repairing: turn 2/12 — applying a draft patch", progress.summary());
    }

    @Test
    void summaryOmitsMissingDetail() {
        assertEquals("starting", new LlmClient.Progress("starting", null).summary());
    }

    @Test
    void rejectsBlankStep() {
        assertThrows(IllegalArgumentException.class, () -> new LlmClient.Progress(" ", "detail"));
    }

    @Test
    void rejectsDraftWhoseDeclaredSizeIgnoresRequestedFootprint() {
        String mismatch = LlmClient.requestMismatch("detailed castle, 200 by 200, furnished",
                structure(10, 5, 10, List.of(new BlockEntry(0, 0, 0, "minecraft:stone"))));

        assertTrue(mismatch.contains("declares horizontal size 10 by 10"), mismatch);
    }

    @Test
    void rejectsLargeEmptyCanvasAroundTinyStructure() {
        String mismatch = LlmClient.requestMismatch("detailed castle, 200x200",
                structure(200, 40, 200, List.of(
                        new BlockEntry(20, 0, 20, "minecraft:stone"),
                        new BlockEntry(26, 10, 26, "minecraft:stone"))));

        assertTrue(mismatch.contains("blocks span only 7 by 7"), mismatch);
    }

    @Test
    void acceptsDraftThatActuallySpansRequestedFootprintAndHeight() {
        assertNull(LlmClient.requestMismatch("castle size 200 by 200 by 100",
                structure(200, 100, 200, List.of(
                        new BlockEntry(0, 0, 0, "minecraft:stone"),
                        new BlockEntry(150, 99, 150, "minecraft:stone")))));
    }

    @Test
    void validCompileIsNotReturnedWithoutExplicitSubmission() {
        StubLlmClient client = new StubLlmClient();
        client.responses.add(toolCall("compile_vxb", "vxb", """
                VXB-2
                size 1 1 1
                pal
                S stone
                end
                plan y=0
                S
                """));
        for (int i = 1; i < 12; i++) client.responses.add(emptyResponse());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client.runToolLoop("unused", "unused", "unused", "a stone cube", "", ignored -> {}));

        assertTrue(error.getMessage().contains("without explicitly submitting"), error.getMessage());
    }

    private static BuildStructure structure(int sizeX, int sizeY, int sizeZ, List<BlockEntry> blocks) {
        return new BuildStructure(new ArrayList<>(blocks), Map.of(), List.of(), Set.of(),
                sizeX, sizeY, sizeZ, false, false);
    }

    private static JsonObject toolCall(String name, String argumentName, String argumentValue) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty(argumentName, argumentValue);
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("arguments", arguments.toString());
        JsonObject call = new JsonObject();
        call.addProperty("id", "call-1");
        call.add("function", function);
        JsonArray calls = new JsonArray();
        calls.add(call);
        JsonObject message = new JsonObject();
        message.add("tool_calls", calls);
        return response(message);
    }

    private static JsonObject emptyResponse() {
        JsonObject message = new JsonObject();
        message.addProperty("content", "");
        return response(message);
    }

    private static JsonObject response(JsonObject message) {
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject response = new JsonObject();
        response.add("choices", choices);
        return response;
    }

    private static final class StubLlmClient extends LlmClient {
        private final Deque<JsonObject> responses = new ArrayDeque<>();

        @Override
        JsonObject sendChatRequest(String url, String apiKey, JsonObject body, boolean toolsEnabled) {
            return responses.removeFirst();
        }
    }
}
