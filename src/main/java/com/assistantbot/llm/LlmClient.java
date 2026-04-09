package com.assistantbot.llm;

import com.assistantbot.AssistantMod;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for calling an LLM via OpenRouter's OpenAI-compatible API.
 * Sends a structure description, receives compact JSON, parses it into
 * a BuildStructure. Runs asynchronously to avoid blocking the server thread.
 *
 * Configuration via environment variables:
 *   OPENROUTER_API_KEY  — bearer token (required)
 *   OPENROUTER_BASE_URL — API base URL (required, e.g. https://openrouter.ai/api/v1)
 *   OPENROUTER_MODEL    — model identifier (required, e.g. anthropic/claude-sonnet-4)
 */
public class LlmClient {

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(90);

    private static final String SYSTEM_PROMPT = """
            You are a Minecraft structure generator. Given a description, output a compact JSON object for a voxel structure.

            RULES:
            - Do NOT use "minecraft:" prefixes — use short names: "dirt", "oak_planks", "stone", etc.
            - Do NOT include a "materials" field — it will be computed automatically.
            - Omit air and empty spaces — only output solid blocks.
            - Keep structures small (under 10x10x10).
            - Output ONLY the JSON — no markdown fences, no explanation.

            Choose one of two formats:

            FORMAT A — Tuple array (works for any structure):
            {"p":["block_a","block_b"],"b":[[x,y,z,i],...]}
              p = palette array of block names; b = array of [x,y,z,palette_index]; y=0 is ground level.
            Example (4-block stone pillar):
            {"p":["stone"],"b":[[0,0,0,0],[0,1,0,0],[0,2,0,0],[0,3,0,0]]}

            FORMAT B — Layered character grids (best for dense box-like structures):
            {"p":{"a":"block_a","b":"block_b"},"l":["layer_y0","layer_y1",...],"offset":[ox,oy,oz]}
              p = single-char palette map; l = one string per Y level with rows separated by "/";
              offset = [x,y,z] applied to all grid positions (default [0,0,0]). Use "." for air gaps.
            Example (3x1x2 dirt floor centered at origin):
            {"p":{"d":"dirt"},"l":["ddd/ddd"],"offset":[-1,0,-1]}""";

    private final HttpClient httpClient;
    private final Gson gson;

    public LlmClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.gson = new Gson();
    }

    /**
     * Call the LLM asynchronously with the given structure description.
     * Returns a CompletableFuture that resolves to a BuildStructure.
     *
     * @param description User's description of what to build (e.g. "dirt hut")
     * @return CompletableFuture resolving to the parsed structure
     */
    public CompletableFuture<BuildStructure> requestStructureAsync(String description) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return requestStructure(description);
            } catch (Exception e) {
                throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Synchronous LLM call. Should NOT be called on the server thread.
     */
    private BuildStructure requestStructure(String description) throws Exception {
        String baseUrl = requireEnv("OPENROUTER_BASE_URL");
        String apiKey = requireEnv("OPENROUTER_API_KEY");
        String model = requireEnv("OPENROUTER_MODEL");

        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String userMessage = "Description: " + description + "\nAvailable inventory: infinite (creative mode)";

        // First attempt
        String content = callApi(url, apiKey, model, userMessage, null, null);
        try {
            BuildStructure structure = BuildStructure.parse(content);
            AssistantMod.LOGGER.info("LLM structure parsed: {} blocks", structure.getBlocks().size());
            return structure;
        } catch (IllegalArgumentException parseError) {
            AssistantMod.LOGGER.warn("First LLM parse failed ({}), sending repair request", parseError.getMessage());

            // Repair attempt: send the bad response back with the parse error
            String repairContent = callApi(url, apiKey, model, userMessage, content,
                    "Your response could not be parsed. Error: " + parseError.getMessage()
                            + "\nPlease output ONLY the corrected JSON.");
            BuildStructure structure2 = BuildStructure.parse(repairContent); // let this throw if repair also fails
            AssistantMod.LOGGER.info("LLM repair parse succeeded: {} blocks", structure2.getBlocks().size());
            return structure2;
        }
    }

    /**
     * Make an HTTP POST to the chat completions endpoint.
     *
     * @param assistantResponse If non-null, include as the assistant's prior response (for repair flow)
     * @param repairMessage If non-null, include as a follow-up user message (for repair flow)
     */
    private String callApi(String url, String apiKey, String model, String userMessage,
                           String assistantResponse, String repairMessage) throws Exception {
        // Build messages array
        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", SYSTEM_PROMPT);
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        if (assistantResponse != null) {
            JsonObject assistantMsg = new JsonObject();
            assistantMsg.addProperty("role", "assistant");
            assistantMsg.addProperty("content", assistantResponse);
            messages.add(assistantMsg);
        }

        if (repairMessage != null) {
            JsonObject repairMsg = new JsonObject();
            repairMsg.addProperty("role", "user");
            repairMsg.addProperty("content", repairMessage);
            messages.add(repairMsg);
        }

        // Build request body
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("stream", false);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        String requestBody = gson.toJson(body);

        AssistantMod.LOGGER.info("Sending LLM request to {} (model={})", url, model);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            String bodyPreview = response.body();
            if (bodyPreview.length() > 200) bodyPreview = bodyPreview.substring(0, 200) + "...";
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + bodyPreview);
        }

        // Extract choices[0].message.content
        JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
        String content = responseJson.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();

        AssistantMod.LOGGER.info("LLM response received ({} chars)", content.length());
        return content;
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new RuntimeException("Environment variable " + name + " is not set");
        }
        return value;
    }
}
