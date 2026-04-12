# Damage-Aware Weapon Selection & Pre-Build Block Validation

**Date:** 2026-04-12
**Status:** Approved

## Overview

Two independent features:
1. Replace the fixed-priority weapon selection with damage-aware comparison, and spawn the bot holding a netherite sword.
2. Validate block IDs from LLM-generated VXB-1 build plans against Minecraft's block registry before building, with LLM-assisted correction of invalid blocks.

---

## Feature 1: Damage-Aware Weapon Selection + Netherite Sword on Summon

### Problem

`InventoryHelper.equipBestWeapon()` uses a fixed priority system (swords=3, axes=2) and picks the first match in each category. It does not compare actual damage values, so a wooden sword would be preferred over a netherite axe.

### Design

**`InventoryHelper.equipBestWeapon(ServerPlayerEntity)`** — Replace the priority-based system with damage-value comparison:
- Iterate all inventory slots.
- For each item that is a sword (`ItemTags.SWORDS`) or axe (`instanceof AxeItem`), read its attack damage from the item's attribute modifiers.
- Track the best weapon by highest damage. Tie-break: prefer swords over axes (swords get a slight bonus in comparison).
- Equip the winner to main hand via the existing `moveToHand()` helper.

**Bot spawn (in `AssistantBot` or `AssistantManager`)** — After creating the `BotPlayer`, call `setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.NETHERITE_SWORD))` so the bot spawns armed.

### Files Changed

| File | Change |
|------|--------|
| `InventoryHelper.java` | Rewrite `equipBestWeapon()` to compare attack damage values |
| `AssistantBot.java` or `AssistantManager.java` | Give netherite sword at spawn |

---

## Feature 2: Pre-Build Block Validation with LLM Correction

### Problem

The LLM sometimes generates VXB-1 block IDs that don't exist in Minecraft's registry (e.g., `minecraft:dark_wood_planks`). These are only discovered at placement time, where they silently become air. There is no feedback loop to correct them.

### Design

**New build phase: `VALIDATING`** — Inserted between `REQUESTING` and `SORTING` in BuildTask's state machine.

**Validation flow:**
1. Extract all unique block IDs from the parsed `BuildStructure`.
2. For each block ID:
   - Strip block state properties (everything inside `[...]`).
   - Parse with `Identifier.tryParse()`.
   - Look up in `Registries.BLOCK`.
   - If the registry returns `Blocks.AIR` for a non-air ID, mark it as invalid.
3. If all blocks are valid, transition directly to `SORTING`.
4. If invalid blocks are found, call `LlmClient.requestBlockCorrectionsAsync()`.

**LLM correction prompt:**
```
The following block IDs from your VXB-1 output are not valid Minecraft 1.20.1 blocks:
- minecraft:dark_wood_planks
- minecraft:mossy_brick

For each invalid block, suggest a valid Minecraft 1.20.1 replacement block ID.
Reply with one correction per line in this exact format:
minecraft:dark_wood_planks -> minecraft:dark_oak_planks
minecraft:mossy_brick -> minecraft:mossy_stone_bricks
```

**Correction parsing:**
- Split response by newlines.
- For each line matching the pattern `<invalid> -> <replacement>`, extract the pair.
- Validate the replacement against the registry.
- If a replacement is also invalid after 1 retry round, skip that block (it becomes air) and log a warning.

**Substitution:**
- `BuildStructure` gets a new `replaceBlockId(String oldId, String newId)` method that updates all `BlockEntry` objects with matching `blockId`.
- `BuildStructure` gets a new `getUniqueBlockIds()` method returning `Set<String>`.

**Retry policy:** 1 correction attempt. If the replacement is still invalid, the block is skipped (becomes air) with a logged warning.

### State Machine (Updated)

```
REQUESTING -> VALIDATING -> SORTING -> CLEARING -> PLACING -> DONE
```

### Files Changed

| File | Change |
|------|--------|
| `BuildTask.java` | Add `VALIDATING` phase, validation logic, substitution application |
| `LlmClient.java` | Add `requestBlockCorrectionsAsync()` method |
| `BuildStructure.java` | Add `replaceBlockId()` and `getUniqueBlockIds()` methods; make `BlockEntry` list mutable or provide replacement mechanism |

---

## Out of Scope

- Block state property parsing (e.g., `[axis=y]`, `[facing=south]`) — existing limitation, separate concern.
- Weapon types beyond swords and axes (maces, tridents, crossbows).
- Inventory management for the netherite sword (it's just given at spawn, no persistence).
