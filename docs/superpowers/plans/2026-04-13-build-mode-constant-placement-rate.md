# Build Mode Constant Placement Rate Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make build mode attempt exactly one placement every task tick while still moving the bot aggressively enough to appear close to each target block.

**Architecture:** Keep the change localized to `BuildTask`. Remove the per-block approach countdown, introduce a build-mode-local fast movement constant plus distance thresholds, and reuse the existing `NavigationHelper.navigateTo(...)` and `NavigationHelper.teleportNear(...)` helpers before each placement attempt. Main queue, retry queue, clearing, and server-enforced placement remain intact.

**Tech Stack:** Java 21, Fabric API / Minecraft 1.21.11

**Spec:** `docs/superpowers/specs/2026-04-13-build-mode-constant-placement-design.md`

---

## File Structure

### Existing files to modify

| File | Changes |
|------|---------|
| `src/main/java/com/assistantbot/task/BuildTask.java` | Remove per-block waiting, add build-mode visual movement thresholds/constants, and attempt one placement per task tick for both main and retry queues |

### No new files

---

## Chunk 1: Constant-Rate Placement in `BuildTask`

### Task 1: Update main queue placement flow for constant-rate attempts

**Files:**
- Modify: `src/main/java/com/assistantbot/task/BuildTask.java:39-40`
- Modify: `src/main/java/com/assistantbot/task/BuildTask.java:58-60`
- Modify: `src/main/java/com/assistantbot/task/BuildTask.java:324-330`
- Modify: `src/main/java/com/assistantbot/task/BuildTask.java:361-412`

- [ ] **Step 1: Verify and document the current no-test constraint in the execution output**

This repository currently has no `src/test` tree and no existing Java test classes. Because `BuildTask` is tightly coupled to Minecraft server types, this plan does **not** add a new test harness just for this behavior change.

Before editing `BuildTask.java`, verify those facts from the repository and include this exact note in the execution output for the task:

```text
No existing automated test harness covers BuildTask. For this change, verification will rely on BuildTask control-flow inspection plus ./gradlew build.
```

This makes the verification path explicit instead of leaving it to executor judgment.

- [ ] **Step 2: Remove the per-block approach timer state**

Delete the following state from `BuildTask`:

```java
private static final int MAX_APPROACH_TICKS = 20;
private int approachTicksRemaining;
```

Add three build-local constants near the other placement constants:

```java
private static final double BUILD_VISUAL_MOVE_SPEED = 0.26;
private static final double BUILD_MOVE_THRESHOLD = 2.0;
private static final double BUILD_TELEPORT_THRESHOLD = 6.0;
```

These values implement the approved design: within 2 blocks, place immediately; from more than 2 up to 6 blocks, issue a fast movement update; beyond 6 blocks, snap near the target before placing.

- [ ] **Step 3: Remove countdown initialization from the `CLEARING -> PLACING` transition**

Replace:

```java
phase = BuildPhase.PLACING;
approachTicksRemaining = MAX_APPROACH_TICKS;
return TickResult.CONTINUE;
```

With:

```java
phase = BuildPhase.PLACING;
return TickResult.CONTINUE;
```

- [ ] **Step 4: Rewrite `tickPlacing()` so movement never blocks that tick's placement attempt**

Update `tickPlacing()` with this control flow:

```java
private TickResult tickPlacing(AssistantBot bot) {
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
    boolean placed = attemptBuildPlacement(bot, worldPos, entry.blockId());

    if (placed) {
        totalPlaced++;
    } else {
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

    currentBlockIndex++;
    return TickResult.CONTINUE;
}
```

The important difference is that there is no early return for an approach phase anymore. Every tick either attempts the next main-queue placement or, once the main queue is exhausted, the next retry-queue placement.

- [ ] **Step 5: Add a helper that performs build-mode movement illusion plus same-tick placement**

Add a private helper immediately above `placeBlockServerEnforced(...)`:

```java
private boolean attemptBuildPlacement(AssistantBot bot, BlockPos worldPos, String blockId) {
    Vec3d targetCenter = Vec3d.ofCenter(worldPos);
    double distance = bot.getPos().distanceTo(targetCenter);

    if (distance > BUILD_TELEPORT_THRESHOLD) {
        NavigationHelper.teleportNear(bot, targetCenter);
    } else if (distance > BUILD_MOVE_THRESHOLD) {
        NavigationHelper.navigateTo(bot, worldPos, BUILD_VISUAL_MOVE_SPEED);
    }

    LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
    return placeBlockServerEnforced(bot.getWorld(), worldPos, blockId);
}
```

Do not call `NavigationHelper.stopMoving(bot)` or clear the pathfinder before placement. The point of the change is to preserve visual motion while keeping placement cadence constant.

- [ ] **Step 6: Run the build to verify the code compiles**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Continue directly to retry-path changes without committing yet**

This is a single-file behavior change. Keep the main-queue and retry-queue updates together unless you hit a natural checkpoint that truly needs its own commit.

### Task 2: Apply the same cadence rule to retry processing

**Files:**
- Modify: `src/main/java/com/assistantbot/task/BuildTask.java:414-440`

- [ ] **Step 1: Rewrite `tickRetryQueue()` to reuse the same placement helper**

Replace the current direct look-and-place flow with the same helper used by the main queue:

```java
private TickResult tickRetryQueue(AssistantBot bot) {
    if (retryQueue.isEmpty()) {
        phase = BuildPhase.DONE;
        return TickResult.COMPLETE;
    }

    int blockIdx = retryQueue.remove(0);
    BlockEntry entry = sortedBlocks.get(blockIdx);
    BlockPos worldPos = originPos.add(new Vec3i(entry.x(), entry.y(), entry.z()));

    boolean placed = attemptBuildPlacement(bot, worldPos, entry.blockId());

    if (placed) {
        totalPlaced++;
    } else {
        int retries = retryCount.getOrDefault(blockIdx, 0);
        if (retries < MAX_RETRIES_PER_BLOCK) {
            retryCount.put(blockIdx, retries + 1);
            retryQueue.add(blockIdx);
        } else {
            totalSkipped++;
            AssistantMod.LOGGER.warn("Permanently skipping block at {}", worldPos);
        }
    }

    return TickResult.CONTINUE;
}
```

This keeps retry attempts at one attempt per task tick and ensures the same movement illusion applies there too.

- [ ] **Step 2: Re-run the build after the retry-path change**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Continue directly to final verification without committing yet**

Keep this bundled with Task 1 as one logical `BuildTask.java` change.

---

## Chunk 2: Verification and cleanup

### Task 3: Verify behavior matches the approved design

**Files:**
- Review: `src/main/java/com/assistantbot/task/BuildTask.java`

- [ ] **Step 1: Inspect the final `BuildTask` control flow**

Confirm all of the following are true in the final code:

- `MAX_APPROACH_TICKS` and `approachTicksRemaining` are gone.
- `tickClearing()` no longer initializes any per-block approach countdown.
- `tickPlacing()` no longer returns early because the bot is still approaching.
- `tickRetryQueue()` uses the same same-tick placement helper as the main queue.
- `LookHelper.lookAt(...)` happens after any movement or teleport step.
- `placeBlockServerEnforced(...)` still performs the actual world write using `world.setBlockState(...)`.

- [ ] **Step 2: Run one final build**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: If the user asked for a commit, create one commit for the whole `BuildTask.java` change**

Use a single commit only if the user explicitly asked for one:

```bash
git add src/main/java/com/assistantbot/task/BuildTask.java
git commit -m "feat: keep build mode block placement at a constant rate"
```

If the user did not ask for a commit, stop after verification.
