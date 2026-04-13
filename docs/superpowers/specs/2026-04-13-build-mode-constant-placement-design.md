# Build Mode Constant Placement Rate

**Date:** 2026-04-13
**Status:** Approved

## Overview

Build mode currently uses server-enforced placement, but it still waits up to about one second per block while the bot tries to walk closer before placing. That makes large builds visibly stall whenever navigation lags behind the placement queue.

The new behavior keeps build mode at a fixed cadence of **4 placement attempts per second** by attempting exactly one placement every task tick. If the bot is not visually close enough, build mode is allowed to force the illusion by moving more aggressively and snapping near the target before placing.

---

## Problem

`BuildTask` already ignores real reach checks for the actual placement call by writing blocks directly into the world with `world.setBlockState(...)`. Even so, `tickPlacing()` still spends multiple ticks in an approach phase before each placement:

- It computes the distance to the next target block.
- If the target is farther than 4 blocks away, it calls `NavigationHelper.navigateTo(...)` and waits.
- It repeats that for up to `MAX_APPROACH_TICKS` before it finally places the block.

This means build mode does **not** have a constant placement rate. Placement speed varies with pathfinding and movement, which is the opposite of the desired "always keep building" behavior.

---

## Design

### Build cadence

`BuildTask` should perform **one placement attempt per task tick**, which at the current 5-game-tick scheduler interval is **4 placement attempts per second**.

This cadence applies to both the main placement queue and the retry queue. A failed placement still counts as that tick's attempt; if the block is queued for retry, the retry is attempted on a later task tick using the existing retry accounting.

Selection order stays consistent with current behavior:

- While `currentBlockIndex < sortedBlocks.size()`, the tick operates on the next BFS-sorted block from the main queue.
- Once the main queue is exhausted, each tick operates on the front entry in `retryQueue` until that queue is empty.

This cadence is build-mode-specific. It does not change the task scheduler or any other task's pacing.

### Placement behavior in `BuildTask`

`BuildTask.tickPlacing()` will be simplified so it no longer has a per-block waiting loop.

Queue selection remains unchanged: the main queue is drained first, then `retryQueue` is processed one retry attempt per task tick.

Each placement tick will:

1. Select the current block from the main queue or retry queue using the existing queue rules.
2. Compute the world-space target position.
3. If the bot is more than 2 blocks from the target center but not more than 6 blocks away, drive it toward the target using `NavigationHelper.navigateTo(...)` with a build-task-local fast movement speed constant.
4. If the bot is more than 6 blocks from the target center, call the existing `NavigationHelper.teleportNear(...)` helper to snap it into a plausible nearby position before placement.
5. Turn the bot to face the target block after any movement or snap-near step.
6. Attempt placement immediately in the same tick.

The bot still appears to be moving around the structure, but movement no longer blocks placement progress.

### Visual-only forced proximity

The "close enough" illusion is cosmetic only for build mode:

- Normal `PlaceTask` behavior remains unchanged and still uses real approach + equip + place flow.
- `BuildTask` remains server-enforced and does not start depending on inventory, reach distance, or `BlockHelper.placeBlock(...)`.
- Any forced movement is local to build mode and should not become a global navigation rule.

The preferred mechanism is:

- Add a `BuildTask`-local visual movement speed constant for build mode's fast-approach effect.
- If the bot is between 2 and 6 blocks from the target center, call `NavigationHelper.navigateTo(bot, worldPos, BUILD_VISUAL_MOVE_SPEED)`.
- If the bot is more than 6 blocks from the target center, call the existing `NavigationHelper.teleportNear(bot, targetCenter)` helper instead.
- If the bot is within 2 blocks, no special movement step is needed before placement.

If `teleportNear(...)` still leaves the bot visibly farther away than intended because of terrain, collision resolution, or random offset, build mode still places the block in that same tick. Constant cadence takes priority over perfect visual fidelity in that rare edge case.

This reuses existing movement primitives instead of introducing a new navigation subsystem.

### Scope of code changes

Only `BuildTask` needs behavior changes.

The current `BuildTask` fields `MAX_APPROACH_TICKS` and `approachTicksRemaining` become unnecessary and should be removed. The task should transition from `CLEARING` to `PLACING` without initializing any per-block approach countdown.

The rest of the build pipeline stays as-is:

- `REQUESTING`, `VALIDATING`, `SORTING`, and `CLEARING` phases are unchanged.
- BFS placement order is unchanged.
- Retry behavior for failed placements is unchanged.
- Server-enforced placement remains unchanged through `BuildTask.placeBlockServerEnforced(...)`, which still writes blocks directly with `world.setBlockState(...)`.

### Status behavior

`getStatusString()` for `PLACING` can continue reporting placed count and build description. No user-facing status format change is required for this feature.

---

## Files Changed

| File | Change |
|------|--------|
| `src/main/java/com/assistantbot/task/BuildTask.java` | Remove per-block approach waiting and place one block every task tick while using faster visual movement and snap-near behavior |

---

## Testing

This behavior change should be verified with a normal project build and by checking the resulting control flow in `BuildTask`:

- `./gradlew build` should still pass.
- `tickPlacing()` should no longer return early just because the bot is still approaching the current target.
- The bot should still look toward the target before each placement attempt.
- When the bot starts farther than 2 blocks away, the tick should still issue either a movement update or a snap-near step before that tick's placement attempt.
- Clearing, retry handling, and completion accounting should remain intact.

---

## Out of Scope

- Making placement cadence configurable.
- Changing the normal `/assistant place` command behavior.
- Changing the global bot tick rate.
- Reworking `NavigationHelper` into a new generalized "force close enough" abstraction.
