# Damage-Aware Weapon Selection & Pre-Build Block Validation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve combat weapon selection to be damage-aware (naturally picking netherite sword), spawn the bot with a netherite sword, and add pre-build block validation that catches invalid block IDs and asks the LLM for corrections before building begins.

**Architecture:** Two independent features. Feature 1 modifies `InventoryHelper.equipBestWeapon()` to compare attack damage values and adds a netherite sword to the bot's inventory at spawn. Feature 2 adds a `VALIDATING` phase to `BuildTask` that checks all block IDs against `Registries.BLOCK` after VXB-1 parsing, and calls a new `LlmClient.requestBlockCorrectionsAsync()` method to fix invalid blocks before building.

**Tech Stack:** Java 21, Fabric API / Minecraft 1.21.11, Gson

**Spec:** `docs/superpowers/specs/2026-04-12-weapon-selection-block-validation-design.md`

---

## File Structure

### Existing files to modify

| File | Changes |
|------|---------|
| `src/main/java/com/assistantbot/util/InventoryHelper.java` | Rewrite `equipBestWeapon()` to compare attack damage values using attribute modifiers |
| `src/main/java/com/assistantbot/bot/AssistantBot.java` | Give netherite sword at spawn in constructor |
| `src/main/java/com/assistantbot/task/BuildTask.java` | Add `VALIDATING` phase between `REQUESTING` and `SORTING`, validation logic, block ID substitution |
| `src/main/java/com/assistantbot/llm/LlmClient.java` | Add `requestBlockCorrectionsAsync()` and `requestBlockCorrections()` methods |
| `src/main/java/com/assistantbot/llm/BuildStructure.java` | Add `replaceBlockId()` and `getUniqueBlockIds()` methods |

### No new files

---

## Chunk 1: Damage-Aware Weapon Selection + Netherite Sword on Summon

### Task 1: Rewrite `equipBestWeapon()` to be damage-aware

**Files:**
- Modify: `src/main/java/com/assistantbot/util/InventoryHelper.java:37-63`

- [ ] **Step 1: Rewrite `equipBestWeapon()` with damage-value comparison**

Replace the existing `equipBestWeapon` method (lines 37-63) with a damage-aware version. The new logic:
1. Iterates all inventory slots.
2. For each item that is a sword (`ItemTags.SWORDS`) or axe (`instanceof AxeItem`), extracts attack damage using `stack.applyAttributeModifiers(EquipmentSlot.MAINHAND, callback)` (1.21's callback-based API) by matching `EntityAttributes.GENERIC_ATTACK_DAMAGE` modifiers and summing ADD_VALUE modifier values plus the base damage of 1.0.
3. Tracks the best weapon by highest damage. When damage values are equal, prefers swords over axes.
4. Equips the winner via the existing `moveToHand()` helper.

```java
public static void equipBestWeapon(ServerPlayerEntity player) {
    PlayerInventory inv = player.getInventory();
    int bestSlot = -1;
    double bestDamage = 0.0;
    boolean bestIsSword = false;

    for (int i = 0; i < inv.size(); i++) {
        ItemStack stack = inv.getStack(i);
        if (stack.isEmpty()) continue;

        Item item = stack.getItem();
        boolean isSword = stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS);
        boolean isAxe = item instanceof AxeItem;
        if (!isSword && !isAxe) continue;

        double damage = getAttackDamage(stack);

        // Prefer higher damage; tie-break: prefer swords over axes
        if (damage > bestDamage || (damage == bestDamage && isSword && !bestIsSword)) {
            bestDamage = damage;
            bestSlot = i;
            bestIsSword = isSword;
        }
    }

    if (bestSlot >= 0) {
        moveToHand(inv, bestSlot);
    }
}

/**
 * Extract the effective attack damage from an item stack by reading its
 * attribute modifiers for the main hand slot. Returns base damage (1.0)
 * plus all GENERIC_ATTACK_DAMAGE modifiers.
 *
 * Uses 1.21's callback-based applyAttributeModifiers API (the 1.20.x
 * Multimap-based getAttributeModifiers does not exist in 1.21).
 */
private static double getAttackDamage(ItemStack stack) {
    // Use an array so we can mutate from inside the lambda
    double[] damage = {1.0}; // base attack damage

    stack.applyAttributeModifiers(net.minecraft.entity.EquipmentSlot.MAINHAND,
            (attribute, modifier) -> {
                if (attribute == net.minecraft.entity.attribute.EntityAttributes.GENERIC_ATTACK_DAMAGE
                        && modifier.operation() == net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADD_VALUE) {
                    damage[0] += modifier.value();
                }
            });

    return damage[0];
}
```

Add the required import at the top of the file:
```java
import net.minecraft.entity.EquipmentSlot;
```

- [ ] **Step 2: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (no compilation errors in `InventoryHelper.java`)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/util/InventoryHelper.java
git commit -m "feat: rewrite equipBestWeapon to use damage-aware comparison"
```

### Task 2: Give netherite sword at spawn

**Files:**
- Modify: `src/main/java/com/assistantbot/bot/AssistantBot.java:43-64`

- [ ] **Step 1: Add netherite sword to bot's main hand after spawn**

In the `AssistantBot` constructor, after the `botPlayer.spawn(...)` call (line 56) and before setting the idle task (line 58), add a line to give the bot a netherite sword:

```java
// After line 56 (botPlayer.spawn call):
this.botPlayer.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.NETHERITE_SWORD));
```

Add the required imports at the top of the file:
```java
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
```

The constructor should now read (lines 43-65):
```java
public AssistantBot(ServerPlayerEntity owner) {
    this.ownerUuid = owner.getUuid();
    this.ownerName = owner.getName().getString();
    this.botUuid = UUID.randomUUID();
    this.world = (ServerWorld) owner.getEntityWorld();

    GameProfile profile = new GameProfile(botUuid, "[Bot]" + ownerName);
    this.botPlayer = new BotPlayer(world.getServer(), world, profile);

    Vec3d ownerPos = owner.getEntityPos();
    this.botPlayer.spawn(
            ownerPos.x + 1, ownerPos.y, ownerPos.z + 1,
            owner.getYaw(), owner.getPitch()
    );

    // Arm the bot with a netherite sword
    this.botPlayer.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.NETHERITE_SWORD));

    this.currentTask = new IdleTask();
    this.lastKnownHealth = botPlayer.getHealth();
    this.lastKnownOwnerHealth = owner.getHealth();
    this.pathfinder = new BotPathfinder(this);
    this.botPlayer.setOnLethalDamageCallback(this::onLethalDamage);

    AssistantMod.LOGGER.info("Assistant bot spawned for {} at {}", ownerName, botPlayer.getBlockPos());
}
```

- [ ] **Step 2: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/bot/AssistantBot.java
git commit -m "feat: arm bot with netherite sword on summon"
```

---

## Chunk 2: Pre-Build Block Validation with LLM Correction

### Task 3: Add `getUniqueBlockIds()` and `replaceBlockId()` to BuildStructure

**Files:**
- Modify: `src/main/java/com/assistantbot/llm/BuildStructure.java:20-31`

- [ ] **Step 1: Add helper methods to BuildStructure**

Add two new public methods to `BuildStructure`. These go after the existing `getMaterials()` accessor (line 31).

Since `BlockEntry` is a record (immutable), `replaceBlockId` must rebuild the entries list with new records. The `blocks` field needs to be mutable (`ArrayList`), which it already is — the constructor receives a `List<BlockEntry>` and stores it directly, and the parse method creates it as `new ArrayList<>()`.

```java
/**
 * Returns all unique block IDs used in the structure.
 */
public Set<String> getUniqueBlockIds() {
    Set<String> ids = new HashSet<>();
    for (BlockEntry entry : blocks) {
        ids.add(entry.blockId());
    }
    return ids;
}

/**
 * Replaces all occurrences of a block ID with a new one.
 * Since BlockEntry is a record (immutable), this rebuilds
 * affected entries in the list.
 */
public void replaceBlockId(String oldId, String newId) {
    for (int i = 0; i < blocks.size(); i++) {
        BlockEntry entry = blocks.get(i);
        if (entry.blockId().equals(oldId)) {
            blocks.set(i, new BlockEntry(entry.x(), entry.y(), entry.z(), newId));
        }
    }
    // Update materials map
    if (materials.containsKey(oldId)) {
        int count = materials.remove(oldId);
        materials.merge(newId, count, Integer::sum);
    }
}
```

Note: The `blocks` list is created as `new ArrayList<>()` in the `parse()` method (line 272), and the constructor stores the reference directly (line 26). So `blocks.set(i, ...)` will work. Similarly, `materials` is a `HashMap` created at line 273.

Add the required imports at the top of the file (if not already present):
```java
import java.util.HashSet;
import java.util.Set;
```

- [ ] **Step 2: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/llm/BuildStructure.java
git commit -m "feat: add getUniqueBlockIds and replaceBlockId to BuildStructure"
```

### Task 4: Add `requestBlockCorrectionsAsync()` to LlmClient

**Files:**
- Modify: `src/main/java/com/assistantbot/llm/LlmClient.java:164-172`

- [ ] **Step 1: Add block correction methods to LlmClient**

Add the following methods after the existing `requestStructureAsync` method (after line 172). These reuse the existing `callApi` infrastructure.

```java
/**
 * Ask the LLM to suggest valid replacements for invalid block IDs.
 * Returns a CompletableFuture resolving to a map of oldId -> newId.
 *
 * @param invalidBlocks set of block IDs that were not found in the Minecraft registry
 * @return CompletableFuture resolving to a correction map
 */
public CompletableFuture<Map<String, String>> requestBlockCorrectionsAsync(Set<String> invalidBlocks) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            return requestBlockCorrections(invalidBlocks);
        } catch (Exception e) {
            throw new RuntimeException("Block correction request failed: " + e.getMessage(), e);
        }
    });
}

/**
 * Synchronous block correction call. Should NOT be called on the server thread.
 */
private Map<String, String> requestBlockCorrections(Set<String> invalidBlocks) throws Exception {
    String baseUrl = requireEnv("OPENROUTER_BASE_URL");
    String apiKey = requireEnv("OPENROUTER_API_KEY");
    String model = readModel();

    String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

    StringBuilder sb = new StringBuilder();
    sb.append("The following block IDs from your VXB-1 output are not valid Minecraft 1.21.11 blocks:\n");
    for (String blockId : invalidBlocks) {
        sb.append("- ").append(blockId).append("\n");
    }
    sb.append("\nFor each invalid block, suggest a valid Minecraft 1.21.11 replacement block ID.\n");
    sb.append("Reply with one correction per line in this exact format:\n");
    sb.append("invalid_id -> replacement_id\n");
    sb.append("\nExample:\n");
    sb.append("minecraft:dark_wood_planks -> minecraft:dark_oak_planks\n");
    sb.append("minecraft:mossy_brick -> minecraft:mossy_stone_bricks\n");

    String userMessage = sb.toString();

    AssistantMod.LOGGER.info("Requesting block corrections from LLM for {} invalid blocks", invalidBlocks.size());
    String content = callApi(url, apiKey, model, userMessage, null, null);

    return parseCorrectionResponse(content);
}

/**
 * Parse the LLM's correction response. Lines that don't match the
 * "invalid -> replacement" pattern are silently skipped (lenient parsing).
 */
private Map<String, String> parseCorrectionResponse(String response) {
    Map<String, String> corrections = new HashMap<>();
    String[] lines = response.split("\\r?\\n");

    for (String line : lines) {
        String trimmed = line.trim();
        int arrowIdx = trimmed.indexOf(" -> ");
        if (arrowIdx < 0) continue; // skip lines without " -> " (commentary, blank lines, etc.)

        String invalidId = trimmed.substring(0, arrowIdx).trim();
        String replacement = trimmed.substring(arrowIdx + 4).trim();

        // Strip backticks or other formatting the LLM might add
        invalidId = invalidId.replace("`", "");
        replacement = replacement.replace("`", "");

        if (!invalidId.isEmpty() && !replacement.isEmpty()) {
            // Ensure minecraft: namespace
            if (!replacement.contains(":")) {
                replacement = "minecraft:" + replacement;
            }
            corrections.put(invalidId, replacement);
        }
    }

    AssistantMod.LOGGER.info("Parsed {} block corrections from LLM response", corrections.size());
    return corrections;
}
```

Add the required imports at the top of the file:
```java
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
```

- [ ] **Step 2: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/llm/LlmClient.java
git commit -m "feat: add requestBlockCorrectionsAsync to LlmClient for invalid block correction"
```

### Task 5: Add `VALIDATING` phase to BuildTask

**Files:**
- Modify: `src/main/java/com/assistantbot/task/BuildTask.java:37,47,93-101,105-136,409-427`

This is the largest change. We need to:
1. Add `VALIDATING` to the `BuildPhase` enum.
2. Add state fields for the validation async call.
3. Store the parsed `BuildStructure` as a field (currently blocks are extracted and structure discarded).
4. Add `tickValidating()` method.
5. Update the `tick()` switch and `getStatusString()`.

- [ ] **Step 1: Update the BuildPhase enum**

Change line 37 from:
```java
private enum BuildPhase { REQUESTING, SORTING, CLEARING, PLACING, DONE }
```
to:
```java
private enum BuildPhase { REQUESTING, VALIDATING, SORTING, CLEARING, PLACING, DONE }
```

- [ ] **Step 2: Add state fields for validation**

After the existing `llmWaitTicks` field (line 49), add:

```java
// Validation state
private BuildStructure structure; // stored between REQUESTING and VALIDATING
private CompletableFuture<Map<String, String>> correctionFuture;
private int validationWaitTicks;
private boolean correctionAttempted; // true after one correction round
```

Add the required import at the top of the file:
```java
import java.util.Map;
import java.util.Set;
```

- [ ] **Step 3: Update `tickRequesting()` to store structure and transition to VALIDATING**

Replace the success path in `tickRequesting()` (lines 116-136). Instead of immediately sorting and going to `SORTING`, store the structure and transition to `VALIDATING`:

Change:
```java
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
```

To:
```java
        try {
            structure = llmFuture.join();
            AssistantMod.LOGGER.info("LLM returned {} blocks for \"{}\"",
                    structure.getBlocks().size(), description);

            if (structure.getBlocks().isEmpty()) {
                AssistantMod.LOGGER.warn("LLM returned empty structure");
                return TickResult.FAILED;
            }

            phase = BuildPhase.VALIDATING;
            validationWaitTicks = 0;
            return TickResult.CONTINUE;
        } catch (Exception e) {
            AssistantMod.LOGGER.error("LLM request failed: {}", e.getMessage());
            return TickResult.FAILED;
        }
```

- [ ] **Step 4: Add `tickValidating()` method**

Add the new method after `tickRequesting()` (after the closing brace around line 136):

```java
// --- Phase: VALIDATING ---

private TickResult tickValidating(AssistantBot bot) {
    // If we're waiting for a correction response, poll it
    if (correctionFuture != null) {
        if (!correctionFuture.isDone()) {
            validationWaitTicks++;
            if (validationWaitTicks % LLM_WAIT_LOG_INTERVAL == 0) {
                int waitSeconds = validationWaitTicks * 5 / 20;
                AssistantMod.LOGGER.info("Still waiting for block correction response... ({}s elapsed)", waitSeconds);
            }
            return TickResult.CONTINUE;
        }

        // Process correction response
        try {
            Map<String, String> corrections = correctionFuture.join();
            applyCorrectionResults(corrections);
        } catch (Exception e) {
            AssistantMod.LOGGER.warn("Block correction request failed: {}", e.getMessage());
            AssistantMod.LOGGER.warn("Proceeding with build — invalid blocks will become air");
        }
        correctionFuture = null;

        // After applying corrections, validate again
        Set<String> stillInvalid = findInvalidBlockIds();
        if (!stillInvalid.isEmpty()) {
            AssistantMod.LOGGER.warn("After correction, {} block IDs are still invalid — they will become air: {}",
                    stillInvalid.size(), stillInvalid);
        }

        // Proceed to sorting
        return transitionToSorting();
    }

    // First entry: validate all block IDs
    Set<String> invalidIds = findInvalidBlockIds();

    if (invalidIds.isEmpty()) {
        AssistantMod.LOGGER.info("All block IDs validated successfully");
        return transitionToSorting();
    }

    AssistantMod.LOGGER.warn("Found {} invalid block IDs: {}", invalidIds.size(), invalidIds);

    if (correctionAttempted) {
        // Already tried once — skip invalid blocks
        AssistantMod.LOGGER.warn("Correction already attempted, proceeding with build — invalid blocks will become air");
        return transitionToSorting();
    }

    // Request corrections from LLM
    correctionAttempted = true;
    correctionFuture = llmClient.requestBlockCorrectionsAsync(invalidIds);
    validationWaitTicks = 0;
    AssistantMod.LOGGER.info("Requesting block corrections from LLM for {} invalid blocks", invalidIds.size());
    return TickResult.CONTINUE;
}

/**
 * Find all block IDs in the structure that don't exist in the Minecraft registry.
 */
private Set<String> findInvalidBlockIds() {
    Set<String> invalid = new HashSet<>();
    for (String blockId : structure.getUniqueBlockIds()) {
        // Strip block state properties like [axis=y] for registry lookup
        String baseId = blockId.contains("[") ? blockId.substring(0, blockId.indexOf('[')) : blockId;

        // Skip air — it's always valid
        if (baseId.equals("minecraft:air")) continue;

        Identifier id = Identifier.tryParse(baseId);
        if (id == null) {
            invalid.add(blockId);
            continue;
        }

        Block block = Registries.BLOCK.get(id);
        if (block == Blocks.AIR) {
            // Registry returns AIR for unknown IDs
            invalid.add(blockId);
        }
    }
    return invalid;
}

/**
 * Apply LLM-provided corrections to the structure, validating each replacement.
 */
private void applyCorrectionResults(Map<String, String> corrections) {
    for (Map.Entry<String, String> entry : corrections.entrySet()) {
        String invalidId = entry.getKey();
        String replacement = entry.getValue();

        // Validate the replacement
        String baseReplacement = replacement.contains("[") ? replacement.substring(0, replacement.indexOf('[')) : replacement;
        Identifier id = Identifier.tryParse(baseReplacement);
        if (id == null) {
            AssistantMod.LOGGER.warn("LLM suggested invalid replacement ID: {} -> {}", invalidId, replacement);
            continue;
        }

        Block block = Registries.BLOCK.get(id);
        if (block == Blocks.AIR && !baseReplacement.equals("minecraft:air")) {
            AssistantMod.LOGGER.warn("LLM suggested unknown replacement block: {} -> {}", invalidId, replacement);
            continue;
        }

        AssistantMod.LOGGER.info("Replacing invalid block: {} -> {}", invalidId, replacement);
        structure.replaceBlockId(invalidId, replacement);
    }
}

/**
 * Transition from VALIDATING to SORTING by extracting blocks and computing placement order.
 */
private TickResult transitionToSorting() {
    sortedBlocks = sortBlocksBFS(structure.getBlocks());
    currentBlockIndex = 0;
    totalPlaced = 0;
    totalSkipped = 0;
    phase = BuildPhase.SORTING;
    return TickResult.CONTINUE;
}
```

Add the required imports at the top of the file (if not already present):
```java
import java.util.Set;
import java.util.HashSet;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
```

Note: `BuildTask` likely already imports `Identifier`, `Registries`, `Block`, and `Blocks` since it does block placement. Verify at implementation time and add only what's missing.

- [ ] **Step 5: Update the `tick()` switch to include VALIDATING**

Change the `tick()` method (lines 93-101) from:
```java
    @Override
    public TickResult tick(AssistantBot bot) {
        return switch (phase) {
            case REQUESTING -> tickRequesting(bot);
            case SORTING -> tickSorting(bot);
            case CLEARING -> tickClearing(bot);
            case PLACING -> tickPlacing(bot);
            case DONE -> TickResult.COMPLETE;
        };
    }
```

To:
```java
    @Override
    public TickResult tick(AssistantBot bot) {
        return switch (phase) {
            case REQUESTING -> tickRequesting(bot);
            case VALIDATING -> tickValidating(bot);
            case SORTING -> tickSorting(bot);
            case CLEARING -> tickClearing(bot);
            case PLACING -> tickPlacing(bot);
            case DONE -> TickResult.COMPLETE;
        };
    }
```

- [ ] **Step 6: Update `getStatusString()` to include VALIDATING**

In the `getStatusString()` method (lines 409-427), add a case for `VALIDATING` after the `REQUESTING` case:

```java
    @Override
    public String getStatusString() {
        return switch (phase) {
            case REQUESTING -> {
                int waitSeconds = llmWaitTicks * 5 / 20;
                yield "building: waiting for LLM... (" + waitSeconds + "s, " + description + ")";
            }
            case VALIDATING -> {
                if (correctionFuture != null) {
                    int waitSeconds = validationWaitTicks * 5 / 20;
                    yield "building: waiting for block corrections... (" + waitSeconds + "s)";
                }
                yield "building: validating block IDs...";
            }
            case SORTING -> "building: planning placement order...";
            case CLEARING -> {
                int layersTotal = clearMax.getY() - clearMin.getY() + 1;
                int layersCleared = clearMax.getY() - clearCurrentY;
                yield "building: clearing area " + layersCleared + "/" + layersTotal + " layers (" + totalCleared + " blocks removed)";
            }
            case PLACING -> {
                int total = sortedBlocks != null ? sortedBlocks.size() : 0;
                yield "building: " + totalPlaced + "/" + total + " blocks placed (" + description + ")";
            }
            case DONE -> "building: complete (" + totalPlaced + " placed, " + totalSkipped + " skipped)";
        };
    }
```

- [ ] **Step 7: Update `onStop()` to cancel correction future if active**

In the `onStop()` method (lines 82-89), add cancellation for the correction future:

Change:
```java
    @Override
    public void onStop(AssistantBot bot) {
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
        if (llmFuture != null && !llmFuture.isDone()) {
            llmFuture.cancel(true);
            AssistantMod.LOGGER.info("BuildTask cancelled, LLM request aborted");
        }
    }
```

To:
```java
    @Override
    public void onStop(AssistantBot bot) {
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
        if (llmFuture != null && !llmFuture.isDone()) {
            llmFuture.cancel(true);
            AssistantMod.LOGGER.info("BuildTask cancelled, LLM request aborted");
        }
        if (correctionFuture != null && !correctionFuture.isDone()) {
            correctionFuture.cancel(true);
            AssistantMod.LOGGER.info("BuildTask cancelled, block correction request aborted");
        }
    }
```

- [ ] **Step 8: Verify the build compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/assistantbot/task/BuildTask.java
git commit -m "feat: add VALIDATING phase to BuildTask for pre-build block ID validation with LLM correction"
```

---

## Final Verification

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL with jar output in `build/libs/`

- [ ] **Step 2: Review all changes**

Run: `git log --oneline -5`
Expected: 5 new commits (equipBestWeapon rewrite, netherite sword on summon, BuildStructure helpers, LlmClient corrections, BuildTask VALIDATING phase)
