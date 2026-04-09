# Combat, Invincibility, and Build Command Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three features to the assistant bot: enhanced combat (30-block clear-all), invincibility with slowdown penalty, and LLM-powered build command.

**Architecture:** Three independent features sharing the existing task/bot infrastructure. Combat is constant tweaks. Invincibility adds a downed state to AssistantBot with damage interception in BotPlayer. Build adds an LLM client, structure parser, and new BuildTask state machine with server-enforced block placement.

**Tech Stack:** Java 21, Fabric API 1.21.11, Gson (new dependency), java.net.http.HttpClient

**Spec:** `docs/superpowers/specs/2026-04-08-combat-invincibility-build-design.md`

---

## File Structure

### Existing files to modify

| File | Changes |
|------|---------|
| `src/main/java/com/assistantbot/task/CombatTask.java` | Bump SCAN_RADIUS to 30, TIMEOUT_TICKS to 6000 |
| `src/main/java/com/assistantbot/bot/BotPlayer.java` | Override `damage()` + `onDeath()`, add lethal damage callback |
| `src/main/java/com/assistantbot/bot/AssistantBot.java` | Add downed state fields, speed multiplier, lethal damage handler, status string |
| `src/main/java/com/assistantbot/util/NavigationHelper.java` | Apply speed multiplier in `moveToward()` |
| `src/main/java/com/assistantbot/command/AssistantCommand.java` | Add `/assistant build` command |

### New files to create

| File | Responsibility |
|------|---------------|
| `src/main/java/com/assistantbot/llm/LlmClient.java` | OpenRouter HTTP client — sends prompts, reads responses, handles retry/repair |
| `src/main/java/com/assistantbot/llm/BuildStructure.java` | Data class (BlockEntry record + block list) and JSON parsing for both tuple and grid formats |
| `src/main/java/com/assistantbot/task/BuildTask.java` | Build state machine — REQUESTING → SORTING → PLACING → DONE |

---

## Chunk 1: Enhanced Combat + Invincibility

These two features are small and tightly coupled to existing code. They form a natural chunk.

### Task 1: Enhanced Combat — Update CombatTask Constants

**Files:**
- Modify: `src/main/java/com/assistantbot/task/CombatTask.java:16-17`

- [ ] **Step 1: Update SCAN_RADIUS and TIMEOUT_TICKS**

In `CombatTask.java`, change the two constants:

```java
// Old values:
// private static final double SCAN_RADIUS = 16.0;
// private static final int TIMEOUT_TICKS = 600; // 30s at 20tps

// New values:
private static final double SCAN_RADIUS = 30.0;
private static final int TIMEOUT_TICKS = 6000; // 5 min safety valve (ticksInCombat increments by 5 per task tick)
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/task/CombatTask.java
git commit -m "feat: expand combat scan radius to 30 blocks and extend timeout to 5 minutes"
```

---

### Task 2: Invincibility — BotPlayer Damage Interception

**Files:**
- Modify: `src/main/java/com/assistantbot/bot/BotPlayer.java`

- [ ] **Step 1: Add lethal damage callback field and setter**

Add at the top of the `BotPlayer` class, after the existing fields:

```java
/** Callback invoked when damage would have been lethal. Set by AssistantBot. */
private Runnable onLethalDamageCallback = null;

public void setOnLethalDamageCallback(Runnable callback) {
    this.onLethalDamageCallback = callback;
}
```

- [ ] **Step 2: Override damage() to clamp lethal damage**

Add this method to `BotPlayer`. It must import `net.minecraft.entity.damage.DamageSource` and `net.minecraft.server.world.ServerWorld` (ServerWorld is already imported):

```java
@Override
public boolean damage(ServerWorld world, DamageSource source, float amount) {
    float currentHealth = this.getHealth();

    // Clamp damage so health never reaches 0 — prevents
    // LivingEntity.damage() from triggering onDeath() internally.
    float maxAllowable = currentHealth - 1.0f;
    if (amount >= maxAllowable) {
        boolean wasLethal = (amount >= currentHealth);
        amount = Math.max(0, maxAllowable); // leaves us at 1 HP
        boolean result = super.damage(world, source, amount);
        if (wasLethal && onLethalDamageCallback != null) {
            onLethalDamageCallback.run();
        }
        return result;
    }

    return super.damage(world, source, amount);
}
```

- [ ] **Step 3: Override onDeath() as safety net**

Add this method to `BotPlayer`:

```java
@Override
public void onDeath(DamageSource damageSource) {
    // Never allow death — reset health and cancel.
    // This is a safety net for damage sources that bypass damage()
    // (e.g., void damage, /kill command).
    this.setHealth(1.0f);
    this.deathTime = 0;
    this.dead = false;
}
```

Add the import for `DamageSource`:
```java
import net.minecraft.entity.damage.DamageSource;
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/assistantbot/bot/BotPlayer.java
git commit -m "feat: make bot invincible — clamp HP at 1, override onDeath as safety net"
```

---

### Task 3: Invincibility — AssistantBot Downed State

**Files:**
- Modify: `src/main/java/com/assistantbot/bot/AssistantBot.java`

- [ ] **Step 1: Add downed state fields and constants**

Add these fields to `AssistantBot`, after the existing `private BotPathfinder pathfinder;` field:

```java
private static final int DOWNED_DURATION_TICKS = 2400; // 2 minutes at 20 TPS
private static final double DOWNED_SPEED_MULTIPLIER = 0.25;
private static final int ARMOR_THRESHOLD = 10; // iron armor equivalent — skip penalty if armor >= this

private long downedUntilTick = -1; // -1 = not downed; uses -1 sentinel to avoid tick-0 edge case
```

- [ ] **Step 2: Add speed multiplier method**

Add this method to `AssistantBot`:

```java
/**
 * Returns the current speed multiplier. 0.25 when downed, 1.0 normally.
 * Used by NavigationHelper to scale movement speed.
 */
public double getSpeedMultiplier() {
    if (downedUntilTick >= 0) {
        long currentTick = world.getServer().getTicks();
        if (currentTick < downedUntilTick) {
            return DOWNED_SPEED_MULTIPLIER;
        } else {
            downedUntilTick = -1; // recovered
            AssistantMod.LOGGER.info("Bot recovered from downed state");
        }
    }
    return 1.0;
}
```

- [ ] **Step 3: Add lethal damage handler and wire callback**

Add this method to `AssistantBot`:

```java
private void onLethalDamage() {
    int armor = botPlayer.getArmor();
    if (armor >= ARMOR_THRESHOLD) {
        AssistantMod.LOGGER.info("Lethal damage absorbed by armor (armor={}), no slowdown", armor);
        return;
    }

    long currentTick = world.getServer().getTicks();
    downedUntilTick = currentTick + DOWNED_DURATION_TICKS;
    AssistantMod.LOGGER.info("Bot downed! Moving at 25% speed for 2 minutes (armor={})", armor);
}
```

In the constructor, after `this.pathfinder = new BotPathfinder(this);`, wire the callback:

```java
this.botPlayer.setOnLethalDamageCallback(this::onLethalDamage);
```

- [ ] **Step 4: Update getStatusString() to include downed state**

Replace the existing `getStatusString()` method:

```java
public String getStatusString() {
    String taskStatus = currentTask.getStatusString();
    if (downedUntilTick >= 0) {
        long currentTick = world.getServer().getTicks();
        if (currentTick < downedUntilTick) {
            long remainingTicks = downedUntilTick - currentTick;
            long remainingSeconds = remainingTicks / 20;
            return taskStatus + " (downed, " + remainingSeconds + "s remaining)";
        }
    }
    return taskStatus;
}
```

- [ ] **Step 5: Build to verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/assistantbot/bot/AssistantBot.java
git commit -m "feat: add downed state with 2-min speed penalty on lethal damage, armor mitigation"
```

---

### Task 4: Invincibility — NavigationHelper Speed Multiplier

**Files:**
- Modify: `src/main/java/com/assistantbot/util/NavigationHelper.java`

- [ ] **Step 1: Apply speed multiplier in moveToward()**

In `NavigationHelper.moveToward()`, add the speed multiplier as the first line of the method body:

```java
public static void moveToward(AssistantBot bot, Vec3d target, double speed) {
    // Apply downed-state speed penalty
    speed *= bot.getSpeedMultiplier();

    ServerPlayerEntity player = bot.getFakePlayer();
    // ... rest of method unchanged
```

This is the only change needed. `navigateTo()` delegates to `moveToward()`, and `teleportNear()` intentionally does NOT apply the speed penalty (it's an emergency fallback).

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/util/NavigationHelper.java
git commit -m "feat: apply downed-state speed multiplier to bot movement"
```

---

## Chunk 2: Build Command — LLM Client and Structure Parsing

### Task 5: Add Gson Dependency

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Verify Gson is available**

Minecraft bundles Gson as a dependency, so it's already available at runtime. No new dependency is needed in `build.gradle`. The `com.google.gson` classes can be used directly in mod code.

Verify by checking that `com.google.gson.JsonParser` resolves during the build (Task 6 will exercise this).

- [ ] **Step 2: Build to verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (no changes needed)

- [ ] **Step 3: Commit**

No commit needed — no files changed. Proceed to Task 6.

---

### Task 6: BuildStructure — Data Classes and Parsing

**Files:**
- Create: `src/main/java/com/assistantbot/llm/BuildStructure.java`

- [ ] **Step 1: Create the llm package directory**

Verify the parent exists, then create:
```bash
mkdir -p src/main/java/com/assistantbot/llm
```

- [ ] **Step 2: Write BuildStructure.java**

Create `src/main/java/com/assistantbot/llm/BuildStructure.java`:

```java
package com.assistantbot.llm;

import com.assistantbot.AssistantMod;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a structure returned by the LLM: a list of block entries
 * with relative coordinates. Supports parsing from two compact JSON formats
 * (tuple array and layered grid), matching the third-principles-bot formats.
 */
public class BuildStructure {

    /**
     * A single block in the structure, with coordinates relative to the build origin.
     */
    public record BlockEntry(int x, int y, int z, String blockId) {}

    private final List<BlockEntry> blocks;
    private final Map<String, Integer> materials;

    public BuildStructure(List<BlockEntry> blocks, Map<String, Integer> materials) {
        this.blocks = blocks;
        this.materials = materials;
    }

    public List<BlockEntry> getBlocks() { return blocks; }
    public Map<String, Integer> getMaterials() { return materials; }

    /**
     * Parse an LLM JSON response into a BuildStructure. Auto-detects format:
     * - Tuple format: has "b" key → {"p":[...], "b":[[x,y,z,i],...]}
     * - Grid format: has "l" key → {"p":{...}, "l":[...], "offset":[...]}
     *
     * @throws IllegalArgumentException if the JSON is malformed or unrecognized
     */
    public static BuildStructure parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }

        if (root.has("l")) {
            return parseGridFormat(root);
        } else if (root.has("b")) {
            return parseTupleFormat(root);
        } else {
            throw new IllegalArgumentException(
                    "Unrecognized format: expected \"b\" key (tuple array) or \"l\" key (layered grids)");
        }
    }

    /**
     * Parse tuple format: {"p":["dirt","oak_planks"],"b":[[x,y,z,i],...]}
     */
    private static BuildStructure parseTupleFormat(JsonObject root) {
        JsonArray palette = root.getAsJsonArray("p");
        JsonArray blockArray = root.getAsJsonArray("b");

        if (palette == null || blockArray == null) {
            throw new IllegalArgumentException("Tuple format requires both \"p\" and \"b\" arrays");
        }

        List<String> paletteList = new ArrayList<>();
        for (JsonElement e : palette) {
            paletteList.add(e.getAsString());
        }

        List<BlockEntry> blocks = new ArrayList<>();
        Map<String, Integer> materials = new HashMap<>();

        for (JsonElement e : blockArray) {
            JsonArray tuple = e.getAsJsonArray();
            if (tuple.size() != 4) {
                throw new IllegalArgumentException(
                        "Tuple entry must have 4 elements [x,y,z,i], got " + tuple.size());
            }

            int x = tuple.get(0).getAsInt();
            int y = tuple.get(1).getAsInt();
            int z = tuple.get(2).getAsInt();
            int idx = tuple.get(3).getAsInt();

            if (idx < 0 || idx >= paletteList.size()) {
                throw new IllegalArgumentException(
                        "Palette index " + idx + " out of range (palette has " + paletteList.size() + " entries)");
            }

            String blockId = ensureNamespace(paletteList.get(idx));
            blocks.add(new BlockEntry(x, y, z, blockId));
            materials.merge(blockId, 1, Integer::sum);
        }

        return new BuildStructure(blocks, materials);
    }

    /**
     * Parse grid format: {"p":{"a":"dirt"},"l":["row0/row1",...],"offset":[ox,oy,oz]}
     */
    private static BuildStructure parseGridFormat(JsonObject root) {
        JsonObject palette = root.getAsJsonObject("p");
        JsonArray layers = root.getAsJsonArray("l");

        if (palette == null || layers == null) {
            throw new IllegalArgumentException("Grid format requires both \"p\" and \"l\" fields");
        }

        // Parse offset (defaults to [0,0,0])
        int ox = 0, oy = 0, oz = 0;
        if (root.has("offset")) {
            JsonArray offset = root.getAsJsonArray("offset");
            if (offset.size() >= 3) {
                ox = offset.get(0).getAsInt();
                oy = offset.get(1).getAsInt();
                oz = offset.get(2).getAsInt();
            }
        }

        // Build palette map: single char -> block id
        Map<Character, String> paletteMap = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : palette.entrySet()) {
            if (entry.getKey().length() != 1) {
                throw new IllegalArgumentException(
                        "Grid palette keys must be single characters, got: \"" + entry.getKey() + "\"");
            }
            paletteMap.put(entry.getKey().charAt(0), ensureNamespace(entry.getValue().getAsString()));
        }

        List<BlockEntry> blocks = new ArrayList<>();
        Map<String, Integer> materials = new HashMap<>();

        for (int yi = 0; yi < layers.size(); yi++) {
            int y = yi + oy;
            String layerStr = layers.get(yi).getAsString();
            String[] rows = layerStr.split("/", -1);

            for (int zi = 0; zi < rows.length; zi++) {
                int z = zi + oz;
                String row = rows[zi];

                for (int xi = 0; xi < row.length(); xi++) {
                    char ch = row.charAt(xi);
                    if (ch == '.') continue; // air gap

                    int x = xi + ox;
                    String blockId = paletteMap.get(ch);
                    if (blockId == null) {
                        throw new IllegalArgumentException(
                                "Palette character '" + ch + "' not defined in palette");
                    }

                    blocks.add(new BlockEntry(x, y, z, blockId));
                    materials.merge(blockId, 1, Integer::sum);
                }
            }
        }

        return new BuildStructure(blocks, materials);
    }

    /**
     * Ensure a block name has the "minecraft:" namespace prefix.
     */
    private static String ensureNamespace(String name) {
        if (name.contains(":")) return name;
        return "minecraft:" + name;
    }
}
```

- [ ] **Step 3: Build to verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/assistantbot/llm/BuildStructure.java
git commit -m "feat: add BuildStructure with tuple and grid format parsing"
```

---

### Task 7: LlmClient — OpenRouter HTTP Client

**Files:**
- Create: `src/main/java/com/assistantbot/llm/LlmClient.java`

- [ ] **Step 1: Write LlmClient.java**

Create `src/main/java/com/assistantbot/llm/LlmClient.java`:

```java
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
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/llm/LlmClient.java
git commit -m "feat: add LlmClient for OpenRouter API with retry/repair support"
```

---

## Chunk 3: Build Command — Task and Command Registration

### Task 8: BuildTask — State Machine

**Files:**
- Create: `src/main/java/com/assistantbot/task/BuildTask.java`

- [ ] **Step 1: Write BuildTask.java**

Create `src/main/java/com/assistantbot/task/BuildTask.java`:

```java
package com.assistantbot.task;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.llm.BuildStructure;
import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.LlmClient;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Build a structure from an LLM-generated plan. State machine phases:
 *   REQUESTING → SORTING → PLACING → DONE
 *
 * Uses server-enforced block placement (world.setBlockState) regardless
 * of bot distance. The bot walks toward each block for visual realism
 * but placement is not gated on reach distance.
 */
public class BuildTask implements BotTask {

    private enum BuildPhase { REQUESTING, SORTING, PLACING, DONE }

    private static final int MAX_APPROACH_TICKS = 20; // ~1 second, then place anyway
    private static final int MAX_RETRIES_PER_BLOCK = 3;

    private final String description;
    private final BlockPos originPos;

    private BuildPhase phase;
    private CompletableFuture<BuildStructure> llmFuture;
    private final LlmClient llmClient;

    // Placement state
    private List<BlockEntry> sortedBlocks;
    private int currentBlockIndex;
    private int approachTicksRemaining;
    private Map<Integer, Integer> retryCount; // block index -> retry count
    private List<Integer> retryQueue; // block indices to retry
    private int totalPlaced;
    private int totalSkipped;

    public BuildTask(String description, BlockPos originPos) {
        this.description = description;
        this.originPos = originPos;
        this.phase = BuildPhase.REQUESTING;
        this.llmClient = new LlmClient();
        this.retryCount = new HashMap<>();
        this.retryQueue = new ArrayList<>();
    }

    @Override
    public void onStart(AssistantBot bot) {
        AssistantMod.LOGGER.info("BuildTask starting: \"{}\" at {}", description, originPos);
        llmFuture = llmClient.requestStructureAsync(description);
    }

    @Override
    public void onStop(AssistantBot bot) {
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
        if (llmFuture != null && !llmFuture.isDone()) {
            llmFuture.cancel(true);
            AssistantMod.LOGGER.info("BuildTask cancelled, LLM request aborted");
        }
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        return switch (phase) {
            case REQUESTING -> tickRequesting(bot);
            case SORTING -> tickSorting(bot);
            case PLACING -> tickPlacing(bot);
            case DONE -> TickResult.COMPLETE;
        };
    }

    // --- Phase: REQUESTING ---

    private TickResult tickRequesting(AssistantBot bot) {
        if (!llmFuture.isDone()) {
            return TickResult.CONTINUE; // still waiting
        }

        try {
            BuildStructure structure = llmFuture.join();
            AssistantMod.LOGGER.info("LLM returned {} blocks for \"{}\"",
                    structure.getBlocks().size(), description);

            if (structure.getBlocks().isEmpty()) {
                AssistantMod.LOGGER.warn("LLM returned empty structure");
                return TickResult.FAILED;
            }

            sortedBlocks = sortBlocksBFS(structure.getBlocks());
            currentBlockIndex = 0;
            totalPlaced = 0;
            totalSkipped = 0;
            phase = BuildPhase.SORTING;
            return TickResult.CONTINUE;
        } catch (Exception e) {
            AssistantMod.LOGGER.error("LLM request failed: {}", e.getMessage());
            return TickResult.FAILED;
        }
    }

    // --- Phase: SORTING (instant transition) ---

    private TickResult tickSorting(AssistantBot bot) {
        // Sorting already done in tickRequesting. Transition immediately to placing.
        phase = BuildPhase.PLACING;
        approachTicksRemaining = MAX_APPROACH_TICKS;
        AssistantMod.LOGGER.info("Build plan sorted: {} blocks to place", sortedBlocks.size());
        return TickResult.CONTINUE;
    }

    // --- Phase: PLACING ---

    private TickResult tickPlacing(AssistantBot bot) {
        // Check if we're done with main queue and retry queue
        if (currentBlockIndex >= sortedBlocks.size()) {
            if (!retryQueue.isEmpty()) {
                return tickRetryQueue(bot);
            }
            phase = BuildPhase.DONE;
            AssistantMod.LOGGER.info("Build complete: {} placed, {} skipped",
                    totalPlaced, totalSkipped);
            return TickResult.COMPLETE;
        }

        BlockEntry entry = sortedBlocks.get(currentBlockIndex);
        BlockPos worldPos = originPos.add(new Vec3i(entry.x(), entry.y(), entry.z()));
        Vec3d targetCenter = Vec3d.ofCenter(worldPos);

        // Look at the target block
        LookHelper.lookAt(bot.getFakePlayer(), targetCenter);

        // Try to walk toward it (smoke and mirrors)
        double distance = bot.getPos().distanceTo(targetCenter);
        if (distance > 4.0 && approachTicksRemaining > 0) {
            NavigationHelper.navigateTo(bot, worldPos, NavigationHelper.WALK_SPEED);
            approachTicksRemaining -= 5; // we tick every 5 game ticks
            return TickResult.CONTINUE;
        }

        // Place the block
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
        boolean placed = placeBlockServerEnforced(bot.getWorld(), worldPos, entry.blockId());

        if (placed) {
            totalPlaced++;
        } else {
            // Queue for retry
            int retries = retryCount.getOrDefault(currentBlockIndex, 0);
            if (retries < MAX_RETRIES_PER_BLOCK) {
                retryCount.put(currentBlockIndex, retries + 1);
                retryQueue.add(currentBlockIndex);
            } else {
                totalSkipped++;
                AssistantMod.LOGGER.warn("Skipping block at {} after {} retries",
                        worldPos, MAX_RETRIES_PER_BLOCK);
            }
        }

        // Move to next block
        currentBlockIndex++;
        approachTicksRemaining = MAX_APPROACH_TICKS;
        return TickResult.CONTINUE;
    }

    private TickResult tickRetryQueue(AssistantBot bot) {
        if (retryQueue.isEmpty()) {
            phase = BuildPhase.DONE;
            return TickResult.COMPLETE;
        }

        int blockIdx = retryQueue.remove(0);
        BlockEntry entry = sortedBlocks.get(blockIdx);
        BlockPos worldPos = originPos.add(new Vec3i(entry.x(), entry.y(), entry.z()));

        LookHelper.lookAt(bot.getFakePlayer(), Vec3d.ofCenter(worldPos));
        boolean placed = placeBlockServerEnforced(bot.getWorld(), worldPos, entry.blockId());

        if (placed) {
            totalPlaced++;
        } else {
            int retries = retryCount.getOrDefault(blockIdx, 0);
            if (retries < MAX_RETRIES_PER_BLOCK) {
                retryCount.put(blockIdx, retries + 1);
                retryQueue.add(blockIdx); // re-add for another retry
            } else {
                totalSkipped++;
                AssistantMod.LOGGER.warn("Permanently skipping block at {}", worldPos);
            }
        }

        return TickResult.CONTINUE;
    }

    // --- Block placement ---

    /**
     * Place a block using world.setBlockState() directly. Server-enforced,
     * ignoring distance, inventory, and item checks.
     *
     * @return true if the block was placed successfully
     */
    private boolean placeBlockServerEnforced(ServerWorld world, BlockPos pos, String blockId) {
        // Resolve block ID to BlockState
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) {
            AssistantMod.LOGGER.warn("Invalid block ID: {}", blockId);
            return false;
        }

        Block block = Registries.BLOCK.get(id);
        if (block == Blocks.AIR) {
            // Registry returns AIR for unknown IDs — treat as hallucinated block name
            AssistantMod.LOGGER.warn("Unknown block ID (LLM hallucination?): {}", blockId);
            return false;
        }

        BlockState state = block.getDefaultState();
        boolean success = world.setBlockState(pos, state, Block.NOTIFY_ALL);

        // Verify placement
        if (success) {
            BlockState actual = world.getBlockState(pos);
            if (actual.getBlock() != block) {
                AssistantMod.LOGGER.warn("Block verification failed at {}: expected {}, got {}",
                        pos, blockId, Registries.BLOCK.getId(actual.getBlock()));
                return false;
            }
        }

        return success;
    }

    // --- BFS sort for realistic placement order ---

    /**
     * Sort blocks so each one is placed only when it has a face-adjacent
     * neighbor that's already placed (or is on the ground, y=0).
     *
     * This gives the visual appearance of building with structural support:
     * ground layer first, then blocks connected to already-placed blocks.
     * Orphan blocks (floating, no path to ground) are appended at the end
     * sorted by Y ascending.
     */
    private List<BlockEntry> sortBlocksBFS(List<BlockEntry> blocks) {
        // Build a lookup: position -> block entry
        Map<Long, BlockEntry> posMap = new HashMap<>();
        for (BlockEntry entry : blocks) {
            posMap.put(packPos(entry.x(), entry.y(), entry.z()), entry);
        }

        Set<Long> placed = new HashSet<>();
        List<BlockEntry> sorted = new ArrayList<>();
        Queue<BlockEntry> ready = new LinkedList<>();

        // Seed: all blocks at y=0 (ground-supported)
        for (BlockEntry entry : blocks) {
            if (entry.y() == 0) {
                long key = packPos(entry.x(), entry.y(), entry.z());
                if (placed.add(key)) {
                    ready.add(entry);
                }
            }
        }

        // BFS: place ready blocks, then check their neighbors
        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        while (!ready.isEmpty()) {
            BlockEntry current = ready.poll();
            sorted.add(current);

            // Check all 6 face-adjacent positions for unplaced blocks
            for (int[] dir : directions) {
                int nx = current.x() + dir[0];
                int ny = current.y() + dir[1];
                int nz = current.z() + dir[2];
                long nKey = packPos(nx, ny, nz);

                if (posMap.containsKey(nKey) && placed.add(nKey)) {
                    ready.add(posMap.get(nKey));
                }
            }
        }

        // Append orphans (floating blocks) sorted by Y ascending
        List<BlockEntry> orphans = new ArrayList<>();
        for (BlockEntry entry : blocks) {
            long key = packPos(entry.x(), entry.y(), entry.z());
            if (!placed.contains(key)) {
                orphans.add(entry);
            }
        }
        orphans.sort(Comparator.comparingInt(BlockEntry::y)
                .thenComparingInt(BlockEntry::x)
                .thenComparingInt(BlockEntry::z));
        sorted.addAll(orphans);

        return sorted;
    }

    /** Pack 3 ints into a long for HashMap key. */
    private long packPos(int x, int y, int z) {
        return ((long)(x & 0x1FFFFF) << 42) | ((long)(y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    @Override
    public String getStatusString() {
        return switch (phase) {
            case REQUESTING -> "building: thinking... (" + description + ")";
            case SORTING -> "building: planning placement order...";
            case PLACING -> {
                int total = sortedBlocks != null ? sortedBlocks.size() : 0;
                yield "building: " + totalPlaced + "/" + total + " blocks placed (" + description + ")";
            }
            case DONE -> "building: complete (" + totalPlaced + " placed, " + totalSkipped + " skipped)";
        };
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/task/BuildTask.java
git commit -m "feat: add BuildTask state machine with BFS placement ordering"
```

---

### Task 9: Command Registration — /assistant build

**Files:**
- Modify: `src/main/java/com/assistantbot/command/AssistantCommand.java`

- [ ] **Step 1: Add BuildTask import**

Add to the imports in `AssistantCommand.java`. Note: `StringArgumentType` and `BlockPos` are already imported in the existing file — verify they're present before proceeding.

```java
import com.assistantbot.task.BuildTask;
```

- [ ] **Step 2: Register the build command in the command tree**

In `AssistantCommand.register()`, add the build subcommand after the existing `.then(CommandManager.literal("deposit")...)` block:

```java
.then(CommandManager.literal("build")
    .then(CommandManager.argument("description", StringArgumentType.greedyString())
        .executes(AssistantCommand::build)))
```

- [ ] **Step 3: Add the build handler method**

Add this method to `AssistantCommand`, alongside the other command handlers:

```java
private static int build(CommandContext<ServerCommandSource> ctx) {
    AssistantBot bot = requireBot(ctx);
    if (bot == null) return 0;

    String description = StringArgumentType.getString(ctx, "description");
    BlockPos origin = bot.getBlockPos();

    bot.setTask(new BuildTask(description, origin));
    ctx.getSource().sendFeedback(
            () -> Text.literal("§a[Assistant] Building: " + description + " (asking LLM...)"),
            false);
    return 1;
}
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/assistantbot/command/AssistantCommand.java
git commit -m "feat: add /assistant build command with greedy string description"
```

---

### Task 10: Final Build Verification

- [ ] **Step 1: Clean build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify output JAR exists**

Run: `ls build/libs/assistant-bot-*.jar`
Expected: `assistant-bot-0.4.0.jar` present

- [ ] **Step 3: Commit any remaining changes**

If any files were missed, stage and commit them. Otherwise, this step is a no-op.

```bash
git status
# If clean, no action needed
```
