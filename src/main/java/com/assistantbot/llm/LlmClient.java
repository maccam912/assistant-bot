package com.assistantbot.llm;

import com.assistantbot.AssistantMod;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for calling an LLM via OpenRouter's OpenAI-compatible API.
 * Sends a structure description, receives VXB-1 format text, parses it into
 * a BuildStructure. Runs asynchronously to avoid blocking the server thread.
 *
 * Configuration:
 *   OPENROUTER_API_KEY  — env var / .env: bearer token (required)
 *   OPENROUTER_BASE_URL — env var / .env: API base URL (required)
 *   OPENROUTER_MODEL    — read from mounted file at /config/openrouter-model
 *                         (falls back to env var / .env if file not found)
 */
public class LlmClient {

    /** 3 minute timeout — if the LLM hasn't responded by then, the request has failed. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(180);

    /** Path to the mounted ConfigMap file containing the model name. */
    private static final String MODEL_FILE_PATH = "/config/openrouter/openrouter-model";

    private static final String SYSTEM_PROMPT = """
            You are a Minecraft structure generator. Given a description, output a structure in VXB-1 format.

            Output ONLY the VXB-1 text — no markdown fences, no explanation, no commentary.

            VXB-1 FORMAT:
            Line 1 must be "VXB-1".
            Then: name, origin, size, axes (header fields).
            Then: palette/endpalette section mapping single-char symbols to block IDs.
            Then: build commands (box, set, layer/endlayer).

            COMMANDS:
            - box x1 y1 z1 x2 y2 z2 S — fill an inclusive cuboid with symbol S.
            - set x y z S — place one block.
            - layer y Y z Z0 / endlayer — 2D character grid at fixed y=Y, rows starting at z=Z0.
              Rows are ordered by increasing z. Characters in each row are ordered by increasing x.
              Use "." for air (or the air palette symbol) inside layers.
            - layer y Y1-Y2 z Z0 / endlayer — same as above but the grid is duplicated to every
              Y level from Y1 to Y2 (inclusive). Use this for tall repetitive sections like walls,
              pillars, or towers where the same cross-section repeats across many layers.
              Example: "layer y 1-8 z 0" applies the grid to y=1, y=2, ..., y=8.

            Later commands overwrite earlier ones (last-write-wins). This means you can:
            1. Lay down a solid floor with box.
            2. Define wall shells with layers.
            3. Carve doors/windows by overwriting with air in later layers.
            4. Add roof and details.

            AUTHORING RULES:
            1. Use box for any rectangle or prism larger than 2x2x2.
            2. Use layer for irregular walls, floors with holes, or decorative patterns.
            3. Never emit coordinates outside the declared size.
            4. Use palette symbols consistently — do not invent new symbols after endpalette.
            5. Avoid directional block states unless necessary; when necessary, hide them in the palette.
            6. Prefer bilateral symmetry when possible.
            7. Build from large masses to small details.
            8. Keep structures compact on the ground (under 20x20 footprint). Height can be
                taller — use layer Y ranges to efficiently define repeating floors.
            9. Use short block names without "minecraft:" prefix: "dirt", "oak_planks", "stone", etc.
            10. y=0 is ground level. y=up, x=east, z=south.
            11. NEVER use leaf blocks (oak_leaves, birch_leaves, etc.) as decorative elements
                like bushes, hedges, or shrubs. Leaves decay in normal Minecraft when not
                connected to a log within 7 blocks. Only use leaves if they are part of
                a tree with a connected trunk.
            12. Stained glass panes (e.g. white_stained_glass_pane) only form full flat
                panes when they connect to adjacent panes or blocks. A single isolated
                pane looks like a thin cross. Use at least a 2-wide span of glass panes
                so they connect to each other and display as a proper window surface.
            13. Use "layer y Y1-Y2 z Z0" for walls, columns, and floors that repeat
                identically across multiple Y levels. This avoids duplicating the same
                grid and makes tall builds feasible.

            EXAMPLE (small cabin):
            VXB-1
            name cabin_9x7x7
            origin 0 0 0
            size 9 7 7
            axes x=east y=up z=south

            palette
            . = air
            C = cobblestone
            P = spruce_planks
            L = spruce_log[axis=y]
            G = glass_pane
            D = spruce_door[half=lower,facing=south]
            U = spruce_door[half=upper,facing=south]
            T = torch
            endpalette

            box 0 0 0 8 0 6 C

            layer y 1 z 0
            LPPGPGPPL
            P.......P
            G.......G
            P.......P
            G.......G
            P.......P
            LPPPDPPPL
            endlayer

            layer y 2 z 0
            LPPGPGPPL
            P.......P
            G.......G
            P.......P
            G.......G
            P.......P
            LPPU.UPPL
            endlayer

            layer y 3 z 0
            LPPGPGPPL
            P.......P
            G.......G
            P.......P
            G.......G
            P.......P
            LPPPPPPPL
            endlayer

            box 0 4 0 8 4 6 P
            box 1 5 1 7 5 5 P
            box 2 6 2 6 6 4 P

            set 4 1 3 T""";

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
        String model = readModel();

        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String userMessage = "Description: " + description + "\nAvailable inventory: infinite (creative mode)";

        // First attempt
        AssistantMod.LOGGER.info("Requesting structure from LLM for: \"{}\"", description);
        String content = callApi(url, apiKey, model, userMessage, null, null);
        try {
            BuildStructure structure = BuildStructure.parse(content);
            AssistantMod.LOGGER.info("LLM structure parsed successfully: {} blocks", structure.getBlocks().size());
            return structure;
        } catch (IllegalArgumentException parseError) {
            AssistantMod.LOGGER.warn("First LLM parse failed ({}), sending repair request", parseError.getMessage());
            AssistantMod.LOGGER.warn("Unparseable LLM response body (first 500 chars): {}",
                    content.length() > 500 ? content.substring(0, 500) + "..." : content);

            // Repair attempt: send the bad response back with the parse error
            AssistantMod.LOGGER.info("Sending repair request to LLM...");
            String repairContent = callApi(url, apiKey, model, userMessage, content,
                    "Your response could not be parsed as VXB-1. Error: " + parseError.getMessage()
                            + "\nPlease output ONLY the corrected VXB-1 text, starting with 'VXB-1' on the first line.");
            try {
                BuildStructure structure2 = BuildStructure.parse(repairContent);
                AssistantMod.LOGGER.info("LLM repair parse succeeded: {} blocks", structure2.getBlocks().size());
                return structure2;
            } catch (IllegalArgumentException repairParseError) {
                AssistantMod.LOGGER.error("Repair response also failed to parse: {}", repairParseError.getMessage());
                AssistantMod.LOGGER.error("Unparseable repair response body (first 500 chars): {}",
                        repairContent.length() > 500 ? repairContent.substring(0, 500) + "..." : repairContent);
                throw repairParseError;
            }
        }
    }

    /**
     * Read the model name from a mounted file (re-read every time so ConfigMap
     * changes take effect without restarting the pod). Falls back to env var.
     */
    private String readModel() {
        Path modelFile = Path.of(MODEL_FILE_PATH);
        if (Files.exists(modelFile)) {
            try {
                String model = Files.readString(modelFile).trim();
                if (!model.isEmpty()) {
                    AssistantMod.LOGGER.info("Using model from {}: {}", MODEL_FILE_PATH, model);
                    return model;
                }
                AssistantMod.LOGGER.warn("Model file {} is empty, falling back to env var", MODEL_FILE_PATH);
            } catch (IOException e) {
                AssistantMod.LOGGER.warn("Failed to read model file {}: {}, falling back to env var",
                        MODEL_FILE_PATH, e.getMessage());
            }
        } else {
            AssistantMod.LOGGER.debug("Model file {} not found, falling back to env var", MODEL_FILE_PATH);
        }
        return requireEnv("OPENROUTER_MODEL");
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

        String requestBody = gson.toJson(body);

        AssistantMod.LOGGER.info("Sending LLM request to {} (model={}, timeout={}s)", url, model, HTTP_TIMEOUT.toSeconds());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        long startTime = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            AssistantMod.LOGGER.error("LLM request timed out after {}s (limit={}s)", elapsed, HTTP_TIMEOUT.toSeconds());
            throw new RuntimeException("LLM request timed out after " + elapsed + " seconds", e);
        } catch (IOException e) {
            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
            AssistantMod.LOGGER.error("LLM request failed with IO error after {}s: {}", elapsed, e.getMessage());
            throw new RuntimeException("LLM request IO error: " + e.getMessage(), e);
        }

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        AssistantMod.LOGGER.info("LLM responded with HTTP {} in {}s ({} bytes)",
                response.statusCode(), elapsed, response.body().length());

        if (response.statusCode() != 200) {
            String bodyPreview = response.body();
            if (bodyPreview.length() > 500) bodyPreview = bodyPreview.substring(0, 500) + "...";
            AssistantMod.LOGGER.error("LLM returned HTTP {}: {}", response.statusCode(), bodyPreview);
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + bodyPreview);
        }

        // Extract choices[0].message.content
        String rawBody = response.body();
        JsonObject responseJson;
        try {
            responseJson = JsonParser.parseString(rawBody).getAsJsonObject();
        } catch (Exception e) {
            AssistantMod.LOGGER.error("LLM response is not valid JSON: {}", e.getMessage());
            AssistantMod.LOGGER.error("Raw response body (first 500 chars): {}",
                    rawBody.length() > 500 ? rawBody.substring(0, 500) + "..." : rawBody);
            throw new RuntimeException("LLM response is not valid JSON: " + e.getMessage(), e);
        }

        String content;
        try {
            content = responseJson.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (Exception e) {
            AssistantMod.LOGGER.error("Failed to extract content from LLM response: {}", e.getMessage());
            AssistantMod.LOGGER.error("Response JSON structure: {}", rawBody.length() > 500
                    ? rawBody.substring(0, 500) + "..." : rawBody);
            throw new RuntimeException("Failed to extract content from LLM response: " + e.getMessage(), e);
        }

        AssistantMod.LOGGER.info("LLM content extracted ({} chars)", content.length());
        return content;
    }

    private static String requireEnv(String name) {
        String value = EnvLoader.get(name);
        if (value == null || value.isBlank()) {
            throw new RuntimeException("Environment variable " + name + " is not set");
        }
        return value;
    }
}
