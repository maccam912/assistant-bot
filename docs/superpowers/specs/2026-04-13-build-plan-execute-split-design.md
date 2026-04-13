# Build Plan/Execute Split Design

**Date:** 2026-04-13
**Status:** Approved

## Problem

The current `BuildTask` is monolithic — it requests an LLM plan, validates, sorts, clears, and places all in one shot. There's no way to:
- Get a plan without building it immediately
- Reuse a plan to stamp copies in different locations
- Share a plan with other players
- Control when execution starts

## Design

Split the build pipeline into two phases: **plan** (LLM request + validation + sorting) and **execute** (wait + clear + place). Plans are stored in a global in-memory registry with integer IDs, surviving for the server's lifetime.

### New Classes

#### `BuildPlanRegistry`

Static singleton holding all plans for the server lifetime.

```java
package com.assistantbot.llm;

public class BuildPlanRegistry {
    private static final BuildPlanRegistry INSTANCE = new BuildPlanRegistry();
    private final Map<Integer, BuildPlan> plans = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public static BuildPlanRegistry getInstance() { return INSTANCE; }

    public int store(BuildPlan plan);    // assigns ID, stores, returns ID
    public BuildPlan get(int id);        // returns null if not found
    public Collection<BuildPlan> getAll(); // for listing
}
```

#### `BuildPlan`

Immutable data object holding a validated, sorted build plan. Reuses `BuildStructure.BlockEntry` for block data (same package, no need to duplicate the record).

```java
package com.assistantbot.llm;

public class BuildPlan {
    private final int id;                          // assigned by registry
    private final String description;
    private final String creatorName;              // from commandSource.getName().getString()
    private final List<BlockEntry> sortedBlocks;   // validated + BFS-sorted, uses BuildStructure.BlockEntry
    // getters, blockCount() convenience method
}
```

No eviction or cleanup — plans accumulate for the server's lifetime. This is intentional for V1; the data is small (a few KB per plan) and server restarts naturally clear them.

#### `PlanTask` (BotTask)

Handles the LLM request, validation, and sorting phases. On completion, stores the plan in the registry and messages the player with the plan ID.

Phases: `REQUESTING -> VALIDATING -> SORTING -> STORING -> DONE`

- Extracted from the first half of the current `BuildTask`
- On completion, sends chat message: `"[Assistant] Plan ready! ID: 14 (house - 847 blocks)"`
- Returns `TickResult.COMPLETE`

Constructor: `PlanTask(String description, ServerPlayerEntity commandSource)`

The `commandSource` (player reference) is stored so the task can send the plan ID back via `commandSource.sendMessage()`. The creator name is extracted via `commandSource.getName().getString()` and passed to the `BuildPlan`.

`PlanTask.onStop()` must cancel both `llmFuture` and `correctionFuture` if in progress (matching current `BuildTask.onStop()` behavior at lines 92-101).

PlanTask also exposes `getLastPlanId()` which returns the ID of the plan it created (or -1 if not yet complete). This is used for chaining (see below).

#### Refactored `BuildTask`

Takes a plan ID (not a description) and captures the origin at construction time.

Phases: `WAITING -> CLEARING -> PLACING -> DONE`

- `WAITING`: Counts 20 task-ticks (each task tick = 5 game ticks, so 20 * 5 = 100 game ticks = 5 seconds). Sends countdown messages at start.
- `CLEARING`: Same as current — clears bounding box top-down, one Y-layer per tick
- `PLACING`: Same as current — places blocks one per tick with visual bot movement

Constructor: `BuildTask(int planId, BlockPos originPos)`

The origin is the bot's position at the time `/assistant execute` is run, making each execution location-independent.

Messages during WAITING phase (e.g., "Building plan #14 in 5 seconds...") are sent via `bot.getOwnerPlayer().sendMessage()` using the bot reference available in `tick(AssistantBot bot)`. If the owner is offline, the message is skipped (log only).

### Chaining for `build` Command

The `build` command needs to run PlanTask and then automatically start BuildTask.

**Chosen approach: `AssistantBot.onTaskComplete()` chaining.**

Calling `bot.setTask()` from inside a task's `tick()` method is re-entrant — `setTask` calls `onStop()` on the current task while `tick()` is still on the stack. To avoid this, we use the existing task completion flow.

PlanTask exposes two fields:
- `getLastPlanId()` — the plan ID stored on completion (-1 if not yet done)
- `isAutoExecute()` — flag set by the `build` command to indicate chaining

When `PlanTask` returns `TickResult.COMPLETE`, `AssistantBot.onTaskComplete()` checks if the completed task is a `PlanTask` with `isAutoExecute()` true. If so, it reads the plan ID and creates a `BuildTask` with the bot's current position as origin. This keeps task transitions in `onTaskComplete()` where they belong.

```java
// In AssistantBot.onTaskComplete():
if (currentTask instanceof PlanTask planTask && planTask.isAutoExecute()) {
    int planId = planTask.getLastPlanId();
    if (planId > 0) {
        currentTask = new BuildTask(planId, getBlockPos());
        currentTask.onStart(this);
        return;
    }
}
// ... existing combat restore / idle fallback
```

The `build` command simply sets the flag:
```java
PlanTask planTask = new PlanTask(description, player);
planTask.setAutoExecute(true);
bot.setTask(planTask);
```

### Commands

| Command | Description |
|---------|-------------|
| `/assistant plan <description>` | Creates a PlanTask, announces plan ID on completion. Arg: `StringArgumentType.greedyString()` |
| `/assistant execute <id>` | Creates a BuildTask from stored plan at bot's current position. Arg: `IntegerArgumentType.integer(1)` |
| `/assistant build <description>` | Convenience: plan + auto-chain into execute. Arg: `StringArgumentType.greedyString()` |
| `/assistant plans` | Lists all available plans with ID, description, block count, creator. No args. |

**`plans` output format:** One line per plan in chat, e.g.:
```
[Assistant] Available plans:
  #1: "small house" — 847 blocks (by Steve)
  #2: "watchtower" — 1203 blocks (by Alex)
```
If no plans exist: `"[Assistant] No plans available. Use /assistant plan <description> to create one."`

**`build` command feedback** changes from the current `"Building: <desc> (asking LLM...)"` to `"Planning and building: <desc> (asking LLM...)"` to indicate both phases will happen.

### 5-Second Delay

The `WAITING` phase in BuildTask counts 20 task-ticks before proceeding. At the start, it sends: `"[Assistant] Building plan #14 in 5 seconds..."`. The bot stays idle during the wait.

### Origin Localization

Block coordinates in a `BuildPlan` are relative (0-based from the LLM). At execute time, the bot's current `getBlockPos()` becomes the origin. All world positions are computed as `origin + relative`. This means the same plan can be stamped at any location by any player.

### What Moves Where

From the current 624-line `BuildTask`:

| Code | Destination |
|------|-------------|
| `tickRequesting()` | `PlanTask` |
| `tickValidating()` + all validation helpers | `PlanTask` |
| `tickSorting()` + `sortBlocksBFS()` + `packPos()` | `PlanTask` |
| `computeClearBounds()` | `BuildTask` (recomputed from plan blocks + new origin) |
| `tickClearing()` | `BuildTask` |
| `tickPlacing()` + placement helpers | `BuildTask` |
| `placeBlockServerEnforced()` | `BuildTask` |
| `snapCloseToTarget()` / visual helpers | `BuildTask` |

### Impact on Existing Behavior

- Combat interrupt still works — both PlanTask and BuildTask are regular BotTasks, saved/restored by the combat system
- If combat interrupts during PlanTask, the LLM request continues in the background (CompletableFuture keeps running). When combat ends and PlanTask resumes, it picks up from where it was.
- `/assistant stop` cancels either task (calls `onStop()` which cancels futures)
- Status strings update to reflect the new phases

### Error Handling

- `execute` with invalid plan ID: `"[Assistant] No plan found with ID: 14"`
- PlanTask failure (LLM error): Reports failure, no plan stored
- BuildTask references a valid plan from the registry — the plan data is immutable so there's no race condition even if multiple bots execute the same plan simultaneously
