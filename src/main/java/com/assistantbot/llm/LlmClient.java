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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * HTTP client for calling an LLM via OpenRouter's OpenAI-compatible API.
 * Sends a structure description, receives VXB-2 format text, parses it into
 * a BuildStructure. Runs asynchronously to avoid blocking the server thread.
 *
 * Configuration:
 *   OPENROUTER_API_KEY  — env var / .env: bearer token (required)
 *   OPENROUTER_BASE_URL — env var / .env: API base URL (required)
 *   OPENROUTER_MODEL    — read from mounted file at /config/openrouter-model
 *                         (falls back to env var / .env if file not found)
 */
public class LlmClient {

    /** A human-readable update emitted as an LLM plan moves through generation and repair. */
    public record Progress(String step, String detail) {
        public Progress {
            if (step == null || step.isBlank()) throw new IllegalArgumentException("step must not be blank");
            detail = detail == null ? "" : detail;
        }

        public String summary() {
            return detail.isBlank() ? step : step + ": " + detail;
        }
    }

    private static final Consumer<Progress> NO_PROGRESS_LISTENER = ignored -> {};

    /** 3 minute timeout — if the LLM hasn't responded by then, the request has failed. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(180);

    /** Path to the mounted ConfigMap file containing the model name. */
    private static final String MODEL_FILE_PATH = "/config/openrouter/openrouter-model";

    /**
     * Terse stand-in used only if the packaged prompt resource cannot be read.
     * The real prompt lives in {@code docs/vxb2-prompt.md}, which is the single
     * source of truth shared with the repository documentation.
     */
    private static final String SYSTEM_PROMPT_FALLBACK = """
            Build Minecraft structures by drawing them in VXB-2. Every block you place is a
            character in a grid; there are no volume or coordinate commands. Header: 'VXB-2',
            name, 'size X Y Z', optional 'ground true|false' and 'terrain replace|keep'. Then a
            'pal' section mapping one character to one block ID, ended by 'end'. Then slices:
            'plan y=N' is a level from above with rows running north to south and characters
            west to east; 'face south|north|east|west <axis>=N' is that side seen from outside,
            rows top to bottom. Row and column counts come from 'size'. Options 'y=1..3',
            'y=0,4' and windows such as 'x=1..7 z=1..5' narrow a slice. Slices may appear in any
            order; where two cover the same cell they must draw the same block. '.' is air.
            Never write block states: the compiler infers stair facings, pillar axes, door
            hinges, torch mounting and slab halves from the drawing. Coordinates are local with
            x=east, y=up, z=south, and the declared size is centred on the bot. Output no
            reasoning, fences or commentary.""";

    /** Canonical prompt is a resource shared with docs; the embedded text is only a fallback. */
    private static final String SYSTEM_PROMPT = loadSystemPrompt();

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
        return requestStructureAsync(description, NO_PROGRESS_LISTENER);
    }

    /**
     * Call the LLM asynchronously and publish coarse progress suitable for status output.
     * The listener runs on the request worker thread and must return quickly.
     */
    public CompletableFuture<BuildStructure> requestStructureAsync(
            String description, Consumer<Progress> progressListener) {
        return requestStructureAsync(description, "", progressListener);
    }

    public CompletableFuture<BuildStructure> requestStructureAsync(
            String description, String terrainContext, Consumer<Progress> progressListener) {
        Consumer<Progress> listener = progressListener != null ? progressListener : NO_PROGRESS_LISTENER;
        return CompletableFuture.supplyAsync(() -> {
            try {
                return requestStructure(description, terrainContext, listener);
            } catch (Exception e) {
                throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
            }
        });
    }



    private BuildStructure requestStructure(String description, String terrainContext,
                                            Consumer<Progress> progressListener) throws Exception {
        try {
            reportProgress(progressListener, "starting", "preparing tool-assisted generation");
            return requestStructureWithTools(description, terrainContext, progressListener);
        } catch (ToolUnavailableException e) {
            AssistantMod.LOGGER.warn("Selected model/provider cannot use VXB tools; falling back to text: {}", e.getMessage());
            reportProgress(progressListener, "falling back", "model/provider does not support VXB tools; using text generation");
            return requestStructureWithoutTools(description, terrainContext, progressListener);
        }
    }

    private BuildStructure requestStructureWithoutTools(
            String description, String terrainContext, Consumer<Progress> progressListener) throws Exception {
        String baseUrl = requireEnv("OPENROUTER_BASE_URL");
        String apiKey = requireEnv("OPENROUTER_API_KEY");
        String model = readModel();

        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        String userMessage = buildUserMessage(description, terrainContext);

        // First attempt
        AssistantMod.LOGGER.info("Requesting structure from LLM for: \"{}\"", description);
        reportProgress(progressListener, "generating", "waiting for initial text draft");
        String content = callApi(url, apiKey, model, userMessage, null, null);

        // Run compiler diagnostics and architectural linter
        reportProgress(progressListener, "validating", "checking initial text draft");
        VxbDiagnostics.DiagnosticResult firstResult = VxbDiagnostics.run(content);

        if (firstResult.hasBlockers()) {
            AssistantMod.LOGGER.warn("First LLM attempt has diagnostics issues: blockers={}, warnings={}",
                    firstResult.hasBlockers(), firstResult.hasWarnings());
            AssistantMod.LOGGER.warn("Unparseable/imperfect LLM response body (first 500 chars): {}",
                    content.length() > 500 ? content.substring(0, 500) + "..." : content);

            // Repair attempt: send the bad response back with the detailed diagnostic report
            AssistantMod.LOGGER.info("Sending repair request to LLM with compiler diagnostic logs...");
            reportProgress(progressListener, "repairing", "asking the model to fix compiler diagnostics");
            String report = firstResult.getLlmReport();
            String repairContent = callApi(url, apiKey, model, userMessage, content,
                    "Your previous response had the following VXB-2 compiler diagnostics:\n" + report
                            + "\nOutput ONLY the corrected VXB-2 text with every blocker resolved. Start with 'VXB-2' on the first line.");

            reportProgress(progressListener, "validating repair", "checking corrected text draft");
            VxbDiagnostics.DiagnosticResult repairResult = VxbDiagnostics.run(repairContent);
            if (repairResult.hasBlockers()) {
                AssistantMod.LOGGER.error("Repair response also failed with blocker errors:\n{}", repairResult.getLlmReport());
                throw new IllegalArgumentException("VXB-2 blocker errors persist after repair attempt:\n" + repairResult.getLlmReport());
            }

            try {
                BuildStructure structure2 = VxbCompiler.compile(repairContent).structure();
                AssistantMod.LOGGER.info("LLM repair parse succeeded: {} blocks", structure2.getBlocks().size());
                reportProgress(progressListener, "complete", "accepted repaired draft with "
                        + structure2.getBlocks().size() + " blocks");
                return structure2;
            } catch (IllegalArgumentException repairParseError) {
                AssistantMod.LOGGER.error("Repair response also failed to parse: {}", repairParseError.getMessage());
                throw new IllegalArgumentException("VXB-2 repair parsing failed: " + repairParseError.getMessage());
            }
        } else {
            try {
                BuildStructure structure = VxbCompiler.compile(content).structure();
                AssistantMod.LOGGER.info("LLM structure parsed successfully: {} blocks", structure.getBlocks().size());
                reportProgress(progressListener, "complete", "accepted initial draft with "
                        + structure.getBlocks().size() + " blocks");
                return structure;
            } catch (IllegalArgumentException parseError) {
                AssistantMod.LOGGER.error("VXB-2 parsing failed: {}", parseError.getMessage());
                throw new IllegalArgumentException("VXB-2 parsing failed: " + parseError.getMessage());
            }
        }
    }

    /** Run a bounded local tool loop following OpenRouter's OpenAI-compatible tool-call protocol. */
    private BuildStructure requestStructureWithTools(
            String description, String terrainContext, Consumer<Progress> progressListener) throws Exception {
        String baseUrl = requireEnv("OPENROUTER_BASE_URL");
        String apiKey = requireEnv("OPENROUTER_API_KEY");
        String model = readModel();
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", buildUserMessage(description, terrainContext)));

        String draftSource = null;
        String draftId = null;
        BuildStructure compiled = null;
        for (int turn = 0; turn < 8; turn++) {
            String turnLabel = "turn " + (turn + 1) + "/8";
            reportProgress(progressListener, turn == 0 ? "generating" : "revising",
                    turnLabel + " — waiting for model response");
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.add("messages", messages);
            body.add("tools", buildVxbTools());
            body.addProperty("tool_choice", "auto");
            body.addProperty("parallel_tool_calls", false);
            body.addProperty("stream", false);

            JsonObject response = sendChatRequest(url, apiKey, body, true);
            JsonObject assistant = response.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message");
            JsonArray calls = assistant.has("tool_calls") && assistant.get("tool_calls").isJsonArray()
                    ? assistant.getAsJsonArray("tool_calls") : null;

            if (calls == null || calls.isEmpty()) {
                String content = assistant.has("content") && !assistant.get("content").isJsonNull()
                        ? assistant.get("content").getAsString() : "";
                if (compiled != null) {
                    reportProgress(progressListener, "complete", "model finished with accepted draft " + draftId);
                    return compiled;
                }
                if (!content.isBlank()) {
                    reportProgress(progressListener, "compiling", turnLabel + " — validating final text draft");
                    BuildStructure structure = VxbCompiler.compile(content).structure();
                    reportProgress(progressListener, "complete", "accepted final text draft with "
                            + structure.getBlocks().size() + " blocks");
                    return structure;
                }
                throw new IllegalArgumentException("LLM stopped without compiling or submitting a VXB draft");
            }

            JsonObject assistantHistory = assistant.deepCopy();
            assistantHistory.addProperty("role", "assistant");
            messages.add(assistantHistory);

            for (var callElement : calls) {
                JsonObject call = callElement.getAsJsonObject();
                String callId = call.get("id").getAsString();
                JsonObject function = call.getAsJsonObject("function");
                String name = function.get("name").getAsString();
                JsonObject args;
                try {
                    var rawArgs = function.get("arguments");
                    args = rawArgs.isJsonObject() ? rawArgs.getAsJsonObject()
                            : JsonParser.parseString(rawArgs.getAsString()).getAsJsonObject();
                } catch (Exception e) {
                    messages.add(toolMessage(callId, jsonError("Invalid tool arguments: " + e.getMessage())));
                    continue;
                }

                JsonObject toolResult = new JsonObject();
                String visualPreview = null;
                try {
                    switch (name) {
                        case "compile_vxb" -> {
                            reportProgress(progressListener, "compiling", turnLabel + " — validating a new draft");
                            draftSource = requiredString(args, "vxb");
                            VxbCompiler.Compilation compilation = VxbCompiler.compile(draftSource);
                            compiled = compilation.structure();
                            draftId = draftId(draftSource);
                            toolResult.addProperty("accepted", true);
                            toolResult.addProperty("draft_id", draftId);
                            toolResult.addProperty("blocks", compiled.getBlocks().size());
                            toolResult.addProperty("placement_groups", BuildStructure.planPlacementGroups(compiled).size());
                            toolResult.addProperty("warnings", compilation.diagnostics().getLlmReport());
                        }
                        case "apply_vxb_patch" -> {
                            reportProgress(progressListener, "repairing", turnLabel + " — applying a draft patch");
                            if (draftSource == null) throw new IllegalArgumentException("No draft exists; call compile_vxb first");
                            draftSource = VxbPatcher.apply(draftSource, requiredString(args, "patch"));
                            VxbCompiler.Compilation compilation = VxbCompiler.compile(draftSource);
                            compiled = compilation.structure();
                            draftId = draftId(draftSource);
                            toolResult.addProperty("accepted", true);
                            toolResult.addProperty("draft_id", draftId);
                            toolResult.addProperty("blocks", compiled.getBlocks().size());
                            toolResult.addProperty("warnings", compilation.diagnostics().getLlmReport());
                        }
                        case "inspect_vxb" -> {
                            reportProgress(progressListener, "inspecting", turnLabel + " — reviewing the compiled structure");
                            requireDraft(args, draftId, compiled);
                            toolResult.addProperty("draft_id", draftId);
                            toolResult.addProperty("compiled_vxb2", VxbPreviewRenderer.render(compiled));
                            if (visionReviewEnabled()) visualPreview = VxbPreviewRenderer.renderPngDataUrl(compiled);
                        }
                        case "submit_vxb" -> {
                            reportProgress(progressListener, "submitting", turnLabel + " — accepting validated draft " + draftId);
                            requireDraft(args, draftId, compiled);
                            AssistantMod.LOGGER.info("LLM submitted validated tool draft {} ({} blocks)", draftId,
                                    compiled.getBlocks().size());
                            reportProgress(progressListener, "complete", "accepted draft " + draftId + " with "
                                    + compiled.getBlocks().size() + " blocks");
                            return compiled;
                        }
                        default -> throw new IllegalArgumentException("Unknown VXB tool: " + name);
                    }
                } catch (VxbCompiler.CompilationException e) {
                    compiled = null;
                    draftId = draftSource == null ? null : draftId(draftSource);
                    reportProgress(progressListener, "awaiting repair", turnLabel
                            + " — draft failed compilation; diagnostics returned to model");
                    toolResult.addProperty("accepted", false);
                    toolResult.addProperty("draft_id", draftId);
                    toolResult.addProperty("diagnostics", e.getMessage());
                    if (draftSource != null) toolResult.addProperty("numbered_source", VxbPatcher.numbered(draftSource));
                    toolResult.addProperty("next", "Call apply_vxb_patch with VXP-1 line edits, then compile_vxb only if replacing the entire design is truly necessary.");
                } catch (IllegalArgumentException e) {
                    reportProgress(progressListener, "correcting tool call", turnLabel + " — " + e.getMessage());
                    toolResult.addProperty("accepted", false);
                    toolResult.addProperty("error", e.getMessage());
                }
                messages.add(toolMessage(callId, gson.toJson(toolResult)));
                if (visualPreview != null) messages.add(imageMessage(visualPreview));
            }
        }
        if (compiled != null) {
            reportProgress(progressListener, "complete", "accepted final compiled draft " + draftId + " with "
                    + compiled.getBlocks().size() + " blocks after the tool-turn limit");
            return compiled;
        }
        throw new IllegalArgumentException("LLM exceeded the 8-turn VXB tool limit without a valid submission");
    }

    private static void reportProgress(Consumer<Progress> listener, String step, String detail) {
        Progress progress = new Progress(step, detail);
        AssistantMod.LOGGER.info("LLM planning progress — {}", progress.summary());
        try {
            listener.accept(progress);
        } catch (RuntimeException e) {
            AssistantMod.LOGGER.warn("LLM progress listener failed: {}", e.getMessage());
        }
    }

    static String buildUserMessage(String description, String terrainContext) {
        StringBuilder message = new StringBuilder("Description: ").append(description)
                .append("\nAvailable inventory: infinite (creative mode)");
        if (terrainContext != null && !terrainContext.isBlank()) {
            message.append("\n\n").append(terrainContext);
        }
        return message.toString();
    }

    private JsonObject sendChatRequest(String url, String apiKey, JsonObject body, boolean toolsEnabled) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String preview = response.body().length() > 1000 ? response.body().substring(0, 1000) : response.body();
            String lower = preview.toLowerCase();
            if (toolsEnabled && (response.statusCode() == 400 || response.statusCode() == 404)
                    && (lower.contains("tool") || lower.contains("supported_parameters") || lower.contains("no endpoints"))) {
                throw new ToolUnavailableException("HTTP " + response.statusCode() + ": " + preview);
            }
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + preview);
        }
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("LLM response is not valid JSON: " + e.getMessage(), e);
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private static JsonObject toolMessage(String callId, String content) {
        JsonObject message = message("tool", content);
        message.addProperty("tool_call_id", callId);
        return message;
    }

    private static JsonObject imageMessage(String dataUrl) {
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", "Inspect this mechanically valid top/south/east projection. Use VXP-1 patches for any clear visual defect; do not rewrite the draft.");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUrl);
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        imagePart.add("image_url", imageUrl);
        JsonArray content = new JsonArray();
        content.add(textPart);
        content.add(imagePart);
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content);
        return message;
    }

    private static boolean visionReviewEnabled() {
        String value = EnvLoader.get("OPENROUTER_VISION_REVIEW");
        return value != null && (value.equalsIgnoreCase("true") || value.equals("1"));
    }

    private static JsonArray buildVxbTools() {
        JsonArray tools = new JsonArray();
        tools.add(functionTool("compile_vxb", "Compile and validate a complete VXB-2 draft.", "vxb",
                "Complete VXB-2 source text"));
        tools.add(functionTool("apply_vxb_patch", "Apply a small VXP-1 numbered-line patch to the current draft and recompile it.", "patch",
                "VXP-1 text using replace-line, delete-line, and insert-after"));
        tools.add(functionTool("inspect_vxb", "Redraw the accepted draft as VXB-2 slices so you can compare it against what you wrote.", "draft_id",
                "Draft ID returned by compile_vxb"));
        tools.add(functionTool("submit_vxb", "Submit an accepted draft as the final Minecraft build plan.", "draft_id",
                "Draft ID returned by compile_vxb"));
        return tools;
    }

    private static JsonObject functionTool(String name, String description, String argument, String argumentDescription) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", argumentDescription);
        JsonObject properties = new JsonObject();
        properties.add(argument, property);
        JsonArray required = new JsonArray();
        required.add(argument);
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        parameters.add("required", required);
        parameters.addProperty("additionalProperties", false);
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    private static String requiredString(JsonObject args, String name) {
        if (!args.has(name) || args.get(name).isJsonNull()) throw new IllegalArgumentException("Missing argument " + name);
        return args.get(name).getAsString();
    }

    private static void requireDraft(JsonObject args, String draftId, BuildStructure compiled) {
        String requested = requiredString(args, "draft_id");
        if (compiled == null || draftId == null) throw new IllegalArgumentException("No accepted draft exists");
        if (!draftId.equals(requested)) throw new IllegalArgumentException("Stale draft_id; expected " + draftId);
    }

    private static String draftId(String source) {
        return Integer.toUnsignedString(source.hashCode(), 36);
    }

    private static String jsonError(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("accepted", false);
        result.addProperty("error", message);
        return result.toString();
    }

    private static final class ToolUnavailableException extends Exception {
        ToolUnavailableException(String message) { super(message); }
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

    private static String loadSystemPrompt() {
        try (var stream = LlmClient.class.getResourceAsStream("/vxb2-prompt.md")) {
            if (stream == null) return SYSTEM_PROMPT_FALLBACK;
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AssistantMod.LOGGER.warn("Could not load /vxb2-prompt.md; using embedded fallback: {}", e.getMessage());
            return SYSTEM_PROMPT_FALLBACK;
        }
    }
}
