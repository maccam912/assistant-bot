# Design: Enhanced Combat, Invincibility, and Build Command

**Date:** 2026-04-08
**Status:** Draft

---

## Overview

Three features for the assistant bot:

1. **Enhanced combat** — On any damage, the bot clears all hostile mobs within 30 blocks before resuming its previous task.
2. **Invincibility with slowdown penalty** — The bot cannot die. Health is clamped at 1 HP. When damage would have killed it, a 2-minute slowdown penalty (25% speed) is applied instead. Armor prevents the penalty.
3. **Build command** — `/assistant build <description>` calls an LLM via OpenRouter to generate a structure plan, then the bot walks around placing blocks with server-enforced placement (infinite creative-like inventory).

---

## Feature 1: Enhanced Combat

### What changes

Modifications to two existing files only: `CombatTask.java` and `AssistantBot.java`.

### CombatTask changes

| Parameter | Current | New |
|-----------|---------|-----|
| `SCAN_RADIUS` | 16.0 | 30.0 |
| `TIMEOUT_TICKS` | 600 (30s) | 6000 (5 min safety valve) |

**Note on tick counting:** `CombatTask.ticksInCombat` increments by 5 each task tick (matching the 5-game-tick task interval), so the constant's value represents game ticks directly. 600 game ticks = 30s, 6000 game ticks = 5 min.

The existing combat loop already works correctly: EQUIPPING -> SCANNING -> APPROACHING -> ATTACKING -> (target dies) -> SCANNING. The scan returns to COMPLETE when no hostiles remain. The 30s timeout was the main reason the bot stopped fighting early. Extending it to 5 minutes as a safety valve prevents infinite stuck states while allowing the bot to clear a large area.

### AssistantBot.checkCombatInterrupt() changes

No changes needed. The existing health-drop detection and interrupt-via-boxing pattern already handles reactive combat correctly. When health drops, the current task is saved, CombatTask starts, and when CombatTask completes (no hostiles found), the saved task is restored.

### Behavior

1. Bot takes any damage (mob hit, projectile, fall, etc.)
2. Current task is saved, CombatTask begins
3. Bot equips best weapon, scans 30-block radius for hostiles
4. Engages closest hostile, attacks until dead
5. Re-scans. If more hostiles found, engages next closest.
6. When no hostiles remain within 30 blocks, task completes
7. Previous task is restored

---

## Feature 2: Invincibility with Slowdown Penalty

### Concept: "Downed" state

The bot tracks HP normally but cannot die. When lethal damage occurs, a speed penalty is applied instead of death. Armor mitigates the penalty.

### BotPlayer changes

Two-layer approach to prevent death: clamp damage amount *before* calling super, and override `onDeath()` as a safety net.

**Primary: clamp damage in `damage()` override:**

```java
@Override
public boolean damage(ServerWorld world, DamageSource source, float amount) {
    float currentHealth = this.getHealth();
    
    // Clamp damage so health never reaches 0 — this prevents
    // LivingEntity.damage() from triggering onDeath() internally.
    float maxAllowable = currentHealth - 1.0f;
    if (amount >= maxAllowable) {
        // This hit would have killed us
        boolean wasLethal = (amount >= currentHealth);
        amount = Math.max(0, maxAllowable);  // leaves us at 1 HP
        boolean result = super.damage(world, source, amount);
        if (wasLethal && onLethalDamageCallback != null) {
            onLethalDamageCallback.run();
        }
        return result;
    }
    
    return super.damage(world, source, amount);
}
```

**Safety net: override `onDeath()`:**

```java
@Override
public void onDeath(DamageSource damageSource) {
    // Never allow death — reset health and cancel
    this.setHealth(1.0f);
    this.deathTime = 0;
    this.dead = false;
}
```

This two-layer approach ensures the bot cannot die even if some damage source bypasses the `damage()` override (e.g., void damage, `/kill`).

Add a `Runnable onLethalDamageCallback` field that `AssistantBot` sets during bot initialization to trigger the downed state.

### AssistantBot downed state

New fields:
- `long downedUntilTick` — server tick when penalty expires (0 = not downed)
- Penalty duration: 2400 ticks (2 minutes at 20 TPS)

New method:
```java
public double getSpeedMultiplier() {
    if (downedUntilTick > 0) {
        long currentTick = world.getServer().getTicks();
        if (currentTick < downedUntilTick) {
            return 0.25; // 25% speed
        } else {
            downedUntilTick = 0; // recovered
        }
    }
    return 1.0;
}
```

When `onLethalDamage` fires:
- Check `bot.getFakePlayer().getArmor()`. If >= 10 (roughly iron armor equivalent), skip the penalty.
- Otherwise, set `downedUntilTick = currentTick + 2400`.
- Log a message: "Bot downed! Moving at 25% speed for 2 minutes."
- Heal back to 1 HP (already done in `damage()` override).

### NavigationHelper speed integration

`moveToward()` and `navigateTo()` already take a `speed` parameter from the calling task. The speed multiplier is applied at the point of use:

```java
// In NavigationHelper.moveToward():
double effectiveSpeed = speed * bot.getSpeedMultiplier();
```

Both `moveToward()` and the `navigateTo()` overloads pass speed through, so modifying `moveToward()` is sufficient since `navigateTo()` delegates to it.

**Teleport fallback:** `navigateTo()` has a `teleportNear()` fallback when pathfinding fails. Teleportation is NOT subject to the slowdown penalty — it's already an emergency fallback (bot is stuck/too far), and slowing it would just make the bot more stuck. The downed penalty only affects walking speed.

### Status reporting

`getStatusString()` in AssistantBot appends " (downed, Xs remaining)" when the penalty is active.

---

## Feature 3: Build Command

### New files

| File | Purpose |
|------|---------|
| `src/.../llm/LlmClient.java` | HTTP client for OpenRouter API |
| `src/.../llm/BuildStructure.java` | Data classes: BlockEntry, Structure |
| `src/.../task/BuildTask.java` | Build state machine |

### Modified files

| File | Change |
|------|--------|
| `AssistantCommand.java` | Add `/assistant build <description>` command |
| `build.gradle` | Add `com.google.code.gson:gson` dependency for JSON parsing |

### LlmClient

Ported from third-principles-bot's `llm.rs`. Uses `java.net.http.HttpClient` (built-in since Java 11).

**Environment variables:**
- `OPENROUTER_API_KEY` — required
- `OPENROUTER_BASE_URL` — required (e.g., `https://openrouter.ai/api/v1`)
- `OPENROUTER_MODEL` — required (e.g., `anthropic/claude-sonnet-4`)

**System prompt:** Identical to the Rust bot's `SYSTEM_PROMPT` constant. Instructs the LLM to output compact JSON in one of two formats. Includes the "keep structures small (under 10x10x10)" constraint and preference for blocks from inventory (though in our case inventory is infinite).

- **Format A (Tuple array):** `{"p":["dirt","oak_planks"],"b":[[x,y,z,i],...]}`
- **Format B (Layered grids):** `{"p":{"a":"dirt"},"l":["row0/row1",...],"offset":[ox,oy,oz]}`

**Request flow:**
1. Build messages array: system prompt + user message (description, inventory="infinite")
2. POST to `{base_url}/chat/completions` with `response_format: { type: "json_object" }` and `stream: false` (non-streaming, unlike the Rust reference which uses SSE streaming)
3. Read full response body as JSON, extract `choices[0].message.content`
4. Parse content JSON. If parse fails, send repair request (append assistant's bad response + error message, re-request). If repair fails, task fails.
5. HTTP timeout: 90 seconds (matching the Rust bot)

**Thread model:** LLM calls run on `CompletableFuture.supplyAsync()` to avoid blocking the server tick thread. `BuildTask` polls the future each tick.

### BuildStructure

```java
public class BuildStructure {
    public record BlockEntry(int x, int y, int z, String blockId) {}
    
    private final List<BlockEntry> blocks;
    // ... parsing methods for tuple and grid formats
}
```

Parsing logic mirrors the Rust `parse_tuple_format()` and `parse_grid_format()` functions. Auto-prepends `minecraft:` namespace to short block names.

### BuildTask state machine

Phases:
1. **REQUESTING** — Fires async LLM call. Polls `CompletableFuture` each tick. Shows "Thinking..." status.
2. **SORTING** — Runs once after LLM response is parsed. Sorts blocks into realistic placement order using BFS from ground support.
3. **PLACING** — Places one block per tick (~4 per second at 5-tick interval). Bot walks toward block, looks at it, then places via `world.setBlockState()`.
4. **DONE** — All blocks placed.

**Cancellation:** If the task is stopped (player issues `/assistant stop`, bot is dismissed, or combat interrupt occurs), `onStop()` cancels the `CompletableFuture` if still pending. Partially-built structures remain in the world — no rollback. When the interrupted task resumes (e.g., after combat), it continues from where it left off in the placement queue.

### Build anchor point

The structure is anchored at the **bot's block position when the `/assistant build` command is issued**. The bot's BlockPos at command time becomes the origin (0, 0, 0) for the LLM's relative coordinates. This position is captured in the `BuildTask` constructor and stored as `originPos`.

Coordinate translation: `worldPos = originPos.add(entry.x, entry.y, entry.z)`

### Placement order: BFS from support

Blocks are sorted so each one is placed only when it has a face-adjacent neighbor that's already placed (or is on the ground). This gives the visual appearance of building with structural support:

```
Algorithm:
1. Collect all blocks at y=0 into "ready" queue (ground-supported)
2. Place them in the sorted output
3. For each placed block, check if any unplaced blocks are face-adjacent to it
4. If yes, add those to the "ready" queue
5. Repeat until all blocks are placed
6. Any orphan blocks (no path to ground) are appended at the end
```

This is a simple BFS traversal of the adjacency graph, seeded with ground-level blocks.

### Server-enforced "smoke and mirrors" placement

For each block in the sorted queue:

1. **Walk toward it:** Call `NavigationHelper.navigateTo()` to move the bot near the block position. Wait up to ~20 ticks (1 second) for the bot to get close. If it doesn't arrive in time, proceed anyway.
2. **Look at it:** Call `LookHelper.lookAt()` to face the block position.
3. **Resolve block state:** Convert the string block ID (e.g., `"minecraft:oak_planks"`) to a `BlockState` via `Registries.BLOCK.get(Identifier.of(blockId)).getDefaultState()`. If the block ID is not found in the registry (LLM hallucinated a block name), skip this block and log a warning.
4. **Place it:** Call `world.setBlockState(pos, blockState, Block.NOTIFY_ALL)` directly. This bypasses reach distance, inventory checks, and item consumption. The server just sets the block.
5. **Verify:** Read back `world.getBlockState(pos)` to confirm placement. If it failed (e.g., entity in the way), retry next tick.

The bot's walking creates the visual impression of a player building by hand, but the actual placement is guaranteed by the server. Distance from the block doesn't matter.

### Command registration

```
/assistant build <description>
```

`<description>` is a greedy string argument (`StringArgumentType.greedyString()`) so multi-word descriptions like "dirt hut" or "small stone tower with a door" work without quotes.

### Infinite inventory

Since the bot pretends to have creative-mode inventory:
- No inventory scanning or material collection
- The LLM prompt says "Available inventory: infinite (creative mode)" instead of listing items
- `world.setBlockState()` doesn't consume items
- No need to check/equip block items in hand

### Error handling

- **LLM call fails** (network, auth, timeout): Task fails with error message to player.
- **LLM returns unparseable response + repair fails:** Task fails with error message.
- **Block placement fails** (entity blocking, unloaded chunk): Skip and retry at end. After 3 retries for any single block, skip it permanently and log a warning.
- **Environment variables missing:** Task fails immediately with descriptive error message telling the user which env var is missing.

---

## Files Changed Summary

| File | Action | Feature |
|------|--------|---------|
| `CombatTask.java` | Modify constants | Combat |
| `BotPlayer.java` | Override `damage()`, add lethal damage callback | Invincibility |
| `AssistantBot.java` | Add downed state, speed multiplier, lethal damage handler | Invincibility |
| `NavigationHelper.java` | Apply speed multiplier | Invincibility |
| `llm/LlmClient.java` | New file — OpenRouter HTTP client | Build |
| `llm/BuildStructure.java` | New file — structure data + parsing | Build |
| `task/BuildTask.java` | New file — build state machine | Build |
| `AssistantCommand.java` | Add `/assistant build` command | Build |
| `build.gradle` | Add Gson dependency | Build |

---

## Out of Scope

- Proactive combat scanning (only reactive on damage)
- Resource collection for building (infinite inventory for now)
- Multi-structure or undo/rollback
- Structure saving/loading from files
- Combat during building (combat interrupt still works as-is via the existing boxing mechanism)
