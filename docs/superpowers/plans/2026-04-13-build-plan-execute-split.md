# Build Plan/Execute Split Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the monolithic `BuildTask` into a plan phase (LLM + validate + sort) and an execute phase (wait + clear + place) with a global in-memory plan registry, allowing plans to be reused and shared across players.

**Architecture:** Extract `BuildPlanRegistry` + `BuildPlan` as the data layer, `PlanTask` as the LLM/validation task, and refactor `BuildTask` to consume stored plans. Add `plan`, `execute`, and `plans` commands. Chain `build` via `AssistantBot.onTaskComplete()`. No tests — Minecraft server types make unit testing impractical; verification is `./gradlew build` + code review.

**Tech Stack:** Java 21, Fabric API / Minecraft 1.21.11

**Spec:** `docs/superpowers/specs/2026-04-13-build-plan-execute-split-design.md`

---

## File Structure

### New files

| File | Responsibility |
|------|---------------|
| `src/main/java/com/assistantbot/llm/BuildPlan.java` | Immutable data object: description, creator, sorted blocks, block count |
| `src/main/java/com/assistantbot/llm/BuildPlanRegistry.java` | Static singleton: store/get/list plans with auto-incrementing IDs |
| `src/main/java/com/assistantbot/task/PlanTask.java` | BotTask: LLM request, validation, sorting, stores result in registry |

### Existing files to modify

| File | Changes |
|------|---------|
| `src/main/java/com/assistantbot/task/BuildTask.java` | Gut and rewrite: consume plan from registry, add WAITING phase, keep CLEARING+PLACING |
| `src/main/java/com/assistantbot/command/AssistantCommand.java` | Add `plan`, `execute`, `plans` commands; update `build` to use PlanTask+autoExecute |
| `src/main/java/com/assistantbot/bot/AssistantBot.java` | Add PlanTask auto-execute chaining in `onTaskComplete()` |

---

## Chunk 1: Data Layer — BuildPlan + BuildPlanRegistry

### Task 1: Create BuildPlan

**Files:**
- Create: `src/main/java/com/assistantbot/llm/BuildPlan.java`

- [ ] **Step 1: Create the BuildPlan class**

```java
package com.assistantbot.llm;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import java.util.Collections;
import java.util.List;

/**
 * Immutable data object holding a validated, BFS-sorted build plan.
 * Plans are stored in BuildPlanRegistry and referenced by integer ID.
 * Block coordinates are relative (0-based); the world-space origin is
 * provided at execute time.
 */
public class BuildPlan {
    private final int id;
    private final String description;
    private final String creatorName;
    private final List<BlockEntry> sortedBlocks;

    public BuildPlan(int id, String description, String creatorName, List<BlockEntry> sortedBlocks) {
        this.id = id;
        this.description = description;
        this.creatorName = creatorName;
        this.sortedBlocks = Collections.unmodifiableList(sortedBlocks);
    }

    public int getId() { return id; }
    public String getDescription() { return description; }
    public String getCreatorName() { return creatorName; }
    public List<BlockEntry> getSortedBlocks() { return sortedBlocks; }
    public int getBlockCount() { return sortedBlocks.size(); }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (new file compiles with no errors)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/llm/BuildPlan.java
git commit -m "Add BuildPlan data class for plan/execute split"
```

### Task 2: Create BuildPlanRegistry

**Files:**
- Create: `src/main/java/com/assistantbot/llm/BuildPlanRegistry.java`

- [ ] **Step 1: Create the BuildPlanRegistry class**

```java
package com.assistantbot.llm;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global in-memory registry of build plans. Plans survive for the server's
 * lifetime (no persistence). IDs are globally unique auto-incrementing integers.
 * Thread-safe: multiple bots can store/read plans concurrently.
 *
 * No eviction — plans accumulate until server restart. Intentional for V1;
 * each plan is a few KB at most.
 */
public class BuildPlanRegistry {
    private static final BuildPlanRegistry INSTANCE = new BuildPlanRegistry();
    private final Map<Integer, BuildPlan> plans = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private BuildPlanRegistry() {}

    public static BuildPlanRegistry getInstance() { return INSTANCE; }

    /**
     * Store a plan and assign it the next available ID.
     * The plan is constructed with a temporary ID of 0; this method
     * creates a new BuildPlan with the assigned ID.
     *
     * @param description plan description
     * @param creatorName name of the player who created the plan
     * @param sortedBlocks validated, BFS-sorted block list
     * @return the assigned plan ID
     */
    public int store(String description, String creatorName,
                     java.util.List<BuildStructure.BlockEntry> sortedBlocks) {
        int id = nextId.getAndIncrement();
        BuildPlan plan = new BuildPlan(id, description, creatorName, sortedBlocks);
        plans.put(id, plan);
        return id;
    }

    /**
     * Look up a plan by ID.
     * @return the plan, or null if not found
     */
    public BuildPlan get(int id) {
        return plans.get(id);
    }

    /**
     * Get all stored plans (unmodifiable view).
     */
    public Collection<BuildPlan> getAll() {
        return Collections.unmodifiableCollection(plans.values());
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/llm/BuildPlanRegistry.java
git commit -m "Add BuildPlanRegistry singleton for global plan storage"
```

---

## Chunk 2: PlanTask — Extract LLM + Validation + Sorting

### Task 3: Create PlanTask

**Files:**
- Create: `src/main/java/com/assistantbot/task/PlanTask.java`
- Reference (read-only): `src/main/java/com/assistantbot/task/BuildTask.java` (lines 1-298 for REQUESTING, VALIDATING, SORTING phases)

- [ ] **Step 1: Create PlanTask.java**

Extract the REQUESTING, VALIDATING, and SORTING phases from BuildTask into PlanTask. The code below is extracted from `BuildTask.java` lines 37-298 with these changes:
- Phases are `REQUESTING, VALIDATING, SORTING, STORING, DONE`
- Constructor takes `(String description, ServerPlayerEntity commandSource)`
- On completion (STORING phase), stores plan in `BuildPlanRegistry` and sends chat message to player
- Exposes `getLastPlanId()` and `isAutoExecute()`/`setAutoExecute(boolean)` for chaining
- `onStop()` cancels both `llmFuture` and `correctionFuture`

```java
package com.assistantbot.task;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.llm.BuildPlanRegistry;
import com.assistantbot.llm.BuildStructure;
import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.LlmClient;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Plan phase of the build pipeline: requests a structure from the LLM,
 * validates block IDs, BFS-sorts for placement order, and stores the
 * result in BuildPlanRegistry. Does NOT place any blocks.
 *
 * Phases: REQUESTING -> VALIDATING -> SORTING -> STORING -> DONE
 */
public class PlanTask implements BotTask {

    private enum PlanPhase { REQUESTING, VALIDATING, SORTING, STORING, DONE }

    private static final int LLM_WAIT_LOG_INTERVAL = 24;

    private final String description;
    private final ServerPlayerEntity commandSource;
    private final String creatorName;

    private PlanPhase phase;
    private CompletableFuture<BuildStructure> llmFuture;
    private final LlmClient llmClient;
    private int llmWaitTicks;

    // Validation state
    private BuildStructure structure;
    private CompletableFuture<Map<String, String>> correctionFuture;
    private int validationWaitTicks;
    private boolean correctionAttempted;

    // Sorting result
    private List<BlockEntry> sortedBlocks;

    // Output
    private int lastPlanId = -1;
    private boolean autoExecute = false;

    public PlanTask(String description, ServerPlayerEntity commandSource) {
        this.description = description;
        this.commandSource = commandSource;
        this.creatorName = commandSource.getName().getString();
        this.phase = PlanPhase.REQUESTING;
        this.llmClient = new LlmClient();
        this.llmWaitTicks = 0;
    }

    @Override
    public void onStart(AssistantBot bot) {
        AssistantMod.LOGGER.info("PlanTask starting: \"{}\" (creator: {})", description, creatorName);
        llmFuture = llmClient.requestStructureAsync(description);
    }

    @Override
    public void onStop(AssistantBot bot) {
        if (llmFuture != null && !llmFuture.isDone()) {
            llmFuture.cancel(true);
            AssistantMod.LOGGER.info("PlanTask cancelled, LLM request aborted");
        }
        if (correctionFuture != null && !correctionFuture.isDone()) {
            correctionFuture.cancel(true);
            AssistantMod.LOGGER.info("PlanTask cancelled, block correction request aborted");
        }
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        return switch (phase) {
            case REQUESTING -> tickRequesting();
            case VALIDATING -> tickValidating();
            case SORTING -> tickSorting();
            case STORING -> tickStoring();
            case DONE -> TickResult.COMPLETE;
        };
    }

    // --- Phase: REQUESTING ---

    private TickResult tickRequesting() {
        if (!llmFuture.isDone()) {
            llmWaitTicks++;
            if (llmWaitTicks % LLM_WAIT_LOG_INTERVAL == 0) {
                int waitSeconds = llmWaitTicks * 5 / 20;
                AssistantMod.LOGGER.info("Still waiting for LLM response... ({}s elapsed, description: \"{}\")",
                        waitSeconds, description);
            }
            return TickResult.CONTINUE;
        }

        try {
            structure = llmFuture.join();
            AssistantMod.LOGGER.info("LLM returned {} blocks for \"{}\"",
                    structure.getBlocks().size(), description);

            if (structure.getBlocks().isEmpty()) {
                AssistantMod.LOGGER.warn("LLM returned empty structure");
                sendMessage("§c[Assistant] Plan failed: LLM returned empty structure.");
                return TickResult.FAILED;
            }

            phase = PlanPhase.VALIDATING;
            validationWaitTicks = 0;
            return TickResult.CONTINUE;
        } catch (Exception e) {
            AssistantMod.LOGGER.error("LLM request failed: {}", e.getMessage());
            sendMessage("§c[Assistant] Plan failed: " + e.getMessage());
            return TickResult.FAILED;
        }
    }

    // --- Phase: VALIDATING ---

    private TickResult tickValidating() {
        if (correctionFuture != null) {
            if (!correctionFuture.isDone()) {
                validationWaitTicks++;
                if (validationWaitTicks % LLM_WAIT_LOG_INTERVAL == 0) {
                    int waitSeconds = validationWaitTicks * 5 / 20;
                    AssistantMod.LOGGER.info("Still waiting for block correction response... ({}s elapsed)", waitSeconds);
                }
                return TickResult.CONTINUE;
            }

            try {
                Map<String, String> corrections = correctionFuture.join();
                applyCorrectionResults(corrections);
            } catch (Exception e) {
                AssistantMod.LOGGER.warn("Block correction request failed: {}", e.getMessage());
                AssistantMod.LOGGER.warn("Proceeding with plan — invalid blocks will become air");
            }
            correctionFuture = null;

            Set<String> stillInvalid = findInvalidBlockIds();
            if (!stillInvalid.isEmpty()) {
                AssistantMod.LOGGER.warn("After correction, {} block IDs are still invalid — they will become air: {}",
                        stillInvalid.size(), stillInvalid);
                for (String invalidId : stillInvalid) {
                    structure.replaceBlockId(invalidId, "minecraft:air");
                }
            }

            return transitionToSorting();
        }

        Set<String> invalidIds = findInvalidBlockIds();

        if (invalidIds.isEmpty()) {
            AssistantMod.LOGGER.info("All block IDs validated successfully");
            return transitionToSorting();
        }

        AssistantMod.LOGGER.warn("Found {} invalid block IDs: {}", invalidIds.size(), invalidIds);

        if (correctionAttempted) {
            AssistantMod.LOGGER.warn("Correction already attempted, proceeding — invalid blocks will become air");
            for (String id : invalidIds) {
                structure.replaceBlockId(id, "minecraft:air");
            }
            return transitionToSorting();
        }

        correctionAttempted = true;
        correctionFuture = llmClient.requestBlockCorrectionsAsync(invalidIds);
        validationWaitTicks = 0;
        AssistantMod.LOGGER.info("Requesting block corrections from LLM for {} invalid blocks", invalidIds.size());
        return TickResult.CONTINUE;
    }

    private static String stripBlockState(String blockId) {
        int bracketIdx = blockId.indexOf('[');
        return bracketIdx >= 0 ? blockId.substring(0, bracketIdx) : blockId;
    }

    private Set<String> findInvalidBlockIds() {
        Set<String> invalid = new HashSet<>();
        for (String blockId : structure.getUniqueBlockIds()) {
            String baseId = stripBlockState(blockId);
            if (baseId.equals("minecraft:air")) continue;

            Identifier id = Identifier.tryParse(baseId);
            if (id == null) {
                invalid.add(blockId);
                continue;
            }

            Block block = Registries.BLOCK.get(id);
            if (block == Blocks.AIR) {
                invalid.add(blockId);
            }
        }
        return invalid;
    }

    private void applyCorrectionResults(Map<String, String> corrections) {
        for (Map.Entry<String, String> entry : corrections.entrySet()) {
            String invalidId = entry.getKey();
            String replacement = entry.getValue();

            String baseReplacement = stripBlockState(replacement);
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

    private TickResult transitionToSorting() {
        sortedBlocks = sortBlocksBFS(structure.getBlocks());
        phase = PlanPhase.SORTING;
        return TickResult.CONTINUE;
    }

    // --- Phase: SORTING (instant transition to STORING) ---

    private TickResult tickSorting() {
        // Sorting already done in transitionToSorting. Store the plan.
        phase = PlanPhase.STORING;
        return TickResult.CONTINUE;
    }

    // --- Phase: STORING ---

    private TickResult tickStoring() {
        lastPlanId = BuildPlanRegistry.getInstance().store(description, creatorName, sortedBlocks);
        AssistantMod.LOGGER.info("Plan stored with ID {} ({} blocks, description: \"{}\")",
                lastPlanId, sortedBlocks.size(), description);
        sendMessage("§a[Assistant] Plan ready! ID: " + lastPlanId + " (" + description
                + " — " + sortedBlocks.size() + " blocks)");
        phase = PlanPhase.DONE;
        return TickResult.COMPLETE;
    }

    // --- BFS sort (extracted from BuildTask) ---

    private List<BlockEntry> sortBlocksBFS(List<BlockEntry> blocks) {
        Map<Long, BlockEntry> posMap = new HashMap<>();
        for (BlockEntry entry : blocks) {
            posMap.put(packPos(entry.x(), entry.y(), entry.z()), entry);
        }

        Set<Long> placed = new HashSet<>();
        List<BlockEntry> sorted = new ArrayList<>();
        Queue<BlockEntry> ready = new LinkedList<>();

        for (BlockEntry entry : blocks) {
            if (entry.y() == 0) {
                long key = packPos(entry.x(), entry.y(), entry.z());
                if (placed.add(key)) {
                    ready.add(entry);
                }
            }
        }

        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        while (!ready.isEmpty()) {
            BlockEntry current = ready.poll();
            sorted.add(current);

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

    private long packPos(int x, int y, int z) {
        return ((long)(x & 0x1FFFFF) << 42) | ((long)(y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    // --- Helpers ---

    private void sendMessage(String message) {
        if (commandSource != null && !commandSource.isDisconnected()) {
            commandSource.sendMessage(Text.literal(message));
        }
    }

    // --- Accessors ---

    public int getLastPlanId() { return lastPlanId; }
    public boolean isAutoExecute() { return autoExecute; }
    public void setAutoExecute(boolean autoExecute) { this.autoExecute = autoExecute; }

    @Override
    public String getStatusString() {
        return switch (phase) {
            case REQUESTING -> {
                int waitSeconds = llmWaitTicks * 5 / 20;
                yield "planning: waiting for LLM... (" + waitSeconds + "s, " + description + ")";
            }
            case VALIDATING -> {
                if (correctionFuture != null) {
                    int waitSeconds = validationWaitTicks * 5 / 20;
                    yield "planning: waiting for block corrections... (" + waitSeconds + "s)";
                }
                yield "planning: validating block IDs...";
            }
            case SORTING -> "planning: sorting placement order...";
            case STORING -> "planning: storing plan...";
            case DONE -> "planning: complete (plan #" + lastPlanId + ")";
        };
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/task/PlanTask.java
git commit -m "Add PlanTask: extract LLM request, validation, sorting from BuildTask"
```

---

## Chunk 3: Refactor BuildTask + Wire Up AssistantBot Chaining

### Task 4: Rewrite BuildTask to consume plans from registry

**Files:**
- Modify: `src/main/java/com/assistantbot/task/BuildTask.java`

Replace the entire contents of `BuildTask.java`. The new version takes a plan ID, looks it up from the registry, and runs WAITING -> CLEARING -> PLACING -> DONE.

- [ ] **Step 1: Replace BuildTask.java with the plan-consuming version**

Replace the entire file with:

```java
package com.assistantbot.task;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import com.assistantbot.llm.BuildPlan;
import com.assistantbot.llm.BuildPlanRegistry;
import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.*;

/**
 * Execute phase of the build pipeline: looks up a stored plan from
 * BuildPlanRegistry, waits 5 seconds, clears the build area, then
 * places blocks one per tick.
 *
 * Phases: WAITING -> CLEARING -> PLACING -> DONE
 *
 * The origin (bot's position at execute time) localizes the plan
 * so the same plan can be stamped at different locations.
 */
public class BuildTask implements BotTask {

    private enum BuildPhase { WAITING, CLEARING, PLACING, DONE }

    private static final int MAX_RETRIES_PER_BLOCK = 3;
    private static final int WAIT_TICKS = 20; // 20 task-ticks * 5 game ticks = 100 game ticks = 5 seconds
    private static final double VISUAL_REPOSITION_START_DISTANCE = 2.0;
    private static final double VISUAL_REPOSITION_TELEPORT_DISTANCE = 6.0;
    private static final double VISUAL_SNAP_RADIUS = 1.5;
    private static final double BUILD_VISUAL_MOVE_SPEED = 0.26;

    private final int planId;
    private final BlockPos originPos;

    private BuildPhase phase;
    private BuildPlan plan; // resolved from registry in onStart

    // Wait state
    private int waitTicksRemaining;

    // Placement state
    private List<BlockEntry> sortedBlocks;
    private int currentBlockIndex;
    private Map<Integer, Integer> retryCount;
    private List<Integer> retryQueue;
    private int totalPlaced;
    private int totalSkipped;

    // Clearing state
    private BlockPos clearMin;
    private BlockPos clearMax;
    private int clearCurrentY;
    private int totalCleared;

    public BuildTask(int planId, BlockPos originPos) {
        this.planId = planId;
        this.originPos = originPos;
        this.phase = BuildPhase.WAITING;
        this.retryCount = new HashMap<>();
        this.retryQueue = new ArrayList<>();
        this.waitTicksRemaining = WAIT_TICKS;
    }

    @Override
    public void onStart(AssistantBot bot) {
        plan = BuildPlanRegistry.getInstance().get(planId);
        if (plan == null) {
            AssistantMod.LOGGER.error("BuildTask started with invalid plan ID: {}", planId);
            sendMessage(bot, "§c[Assistant] No plan found with ID: " + planId);
            return;
        }

        sortedBlocks = plan.getSortedBlocks();
        currentBlockIndex = 0;
        totalPlaced = 0;
        totalSkipped = 0;

        AssistantMod.LOGGER.info("BuildTask starting: plan #{} \"{}\" ({} blocks) at {}",
                planId, plan.getDescription(), sortedBlocks.size(), originPos);
        sendMessage(bot, "§a[Assistant] Building plan #" + planId + " in 5 seconds... ("
                + plan.getDescription() + " — " + sortedBlocks.size() + " blocks)");
    }

    @Override
    public void onStop(AssistantBot bot) {
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        if (plan == null) {
            return TickResult.FAILED;
        }

        return switch (phase) {
            case WAITING -> tickWaiting(bot);
            case CLEARING -> tickClearing(bot);
            case PLACING -> tickPlacing(bot);
            case DONE -> TickResult.COMPLETE;
        };
    }

    // --- Phase: WAITING (5-second countdown) ---

    private TickResult tickWaiting(AssistantBot bot) {
        waitTicksRemaining--;
        if (waitTicksRemaining > 0) {
            return TickResult.CONTINUE;
        }

        // Transition to clearing
        computeClearBounds();
        clearCurrentY = clearMax.getY();
        totalCleared = 0;
        phase = BuildPhase.CLEARING;
        AssistantMod.LOGGER.info("Wait complete. Clearing area from {} to {} for plan #{}",
                clearMin, clearMax, planId);
        return TickResult.CONTINUE;
    }

    // --- Phase: CLEARING ---

    private void computeClearBounds() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockEntry entry : sortedBlocks) {
            minX = Math.min(minX, entry.x());
            minY = Math.min(minY, entry.y());
            minZ = Math.min(minZ, entry.z());
            maxX = Math.max(maxX, entry.x());
            maxY = Math.max(maxY, entry.y());
            maxZ = Math.max(maxZ, entry.z());
        }
        clearMin = originPos.add(new Vec3i(minX, minY, minZ));
        clearMax = originPos.add(new Vec3i(maxX, maxY, maxZ));
    }

    private TickResult tickClearing(AssistantBot bot) {
        if (clearCurrentY < clearMin.getY()) {
            AssistantMod.LOGGER.info("Clearing complete: {} blocks removed", totalCleared);
            phase = BuildPhase.PLACING;
            return TickResult.CONTINUE;
        }

        ServerWorld world = bot.getWorld();
        BlockState air = Blocks.AIR.getDefaultState();

        for (int x = clearMin.getX(); x <= clearMax.getX(); x++) {
            for (int z = clearMin.getZ(); z <= clearMax.getZ(); z++) {
                BlockPos pos = new BlockPos(x, clearCurrentY, z);
                if (!world.getBlockState(pos).isAir()) {
                    world.setBlockState(pos, air, Block.NOTIFY_ALL);
                    totalCleared++;
                }
            }
        }

        Vec3d layerCenter = new Vec3d(
                (clearMin.getX() + clearMax.getX()) / 2.0 + 0.5,
                clearCurrentY + 0.5,
                (clearMin.getZ() + clearMax.getZ()) / 2.0 + 0.5
        );
        LookHelper.lookAt(bot.getFakePlayer(), layerCenter);

        clearCurrentY--;
        return TickResult.CONTINUE;
    }

    // --- Phase: PLACING ---

    private TickResult tickPlacing(AssistantBot bot) {
        if (currentBlockIndex >= sortedBlocks.size()) {
            if (!retryQueue.isEmpty()) {
                return tickRetryQueue(bot);
            }
            NavigationHelper.stopMoving(bot);
            bot.getPathfinder().clearPath();
            phase = BuildPhase.DONE;
            AssistantMod.LOGGER.info("Build complete: {} placed, {} skipped (plan #{})",
                    totalPlaced, totalSkipped, planId);
            sendMessage(bot, "§a[Assistant] Build complete! " + totalPlaced + " blocks placed"
                    + (totalSkipped > 0 ? " (" + totalSkipped + " skipped)" : "")
                    + " — plan #" + planId);
            return TickResult.COMPLETE;
        }

        BlockEntry entry = sortedBlocks.get(currentBlockIndex);
        BlockPos worldPos = originPos.add(new Vec3i(entry.x(), entry.y(), entry.z()));
        boolean placed = attemptPlacementThisTick(bot, entry);

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

    private TickResult tickRetryQueue(AssistantBot bot) {
        if (retryQueue.isEmpty()) {
            NavigationHelper.stopMoving(bot);
            bot.getPathfinder().clearPath();
            phase = BuildPhase.DONE;
            return TickResult.COMPLETE;
        }

        int blockIdx = retryQueue.remove(0);
        BlockEntry entry = sortedBlocks.get(blockIdx);
        BlockPos worldPos = originPos.add(new Vec3i(entry.x(), entry.y(), entry.z()));
        boolean placed = attemptPlacementThisTick(bot, entry);

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

    private boolean attemptPlacementThisTick(AssistantBot bot, BlockEntry entry) {
        BlockPos worldPos = originPos.add(new Vec3i(entry.x(), entry.y(), entry.z()));
        Vec3d targetCenter = Vec3d.ofCenter(worldPos);
        double distance = bot.getPos().distanceTo(targetCenter);

        if (distance > VISUAL_REPOSITION_TELEPORT_DISTANCE) {
            NavigationHelper.teleportNear(bot, targetCenter);
            snapCloseToTarget(bot, targetCenter);
            NavigationHelper.moveToward(bot, targetCenter, BUILD_VISUAL_MOVE_SPEED);
        } else if (distance > VISUAL_REPOSITION_START_DISTANCE) {
            NavigationHelper.navigateTo(bot, worldPos, BUILD_VISUAL_MOVE_SPEED);
            snapCloseToTarget(bot, targetCenter);
        } else {
            NavigationHelper.moveToward(bot, targetCenter, BUILD_VISUAL_MOVE_SPEED);
        }

        LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
        return placeBlockServerEnforced(bot.getWorld(), worldPos, entry.blockId());
    }

    private void snapCloseToTarget(AssistantBot bot, Vec3d targetCenter) {
        var player = bot.getFakePlayer();
        Vec3d currentPos = player.getEntityPos();
        Vec3d delta = targetCenter.subtract(currentPos);
        double distance = delta.length();

        if (distance <= VISUAL_SNAP_RADIUS) {
            return;
        }

        double step = Math.min(
                distance,
                Math.max(0.0, distance - VISUAL_SNAP_RADIUS)
        );
        if (step <= 0.0) {
            return;
        }

        Vec3d snapped = currentPos.add(delta.normalize().multiply(step));
        Vec3d bounded = new Vec3d(
                clamp(snapped.x, clearMin.getX() + 0.5, clearMax.getX() + 0.5),
                clamp(snapped.y, clearMin.getY(), clearMax.getY() + 1.0),
                clamp(snapped.z, clearMin.getZ() + 0.5, clearMax.getZ() + 0.5)
        );
        player.refreshPositionAndAngles(
                bounded.x,
                bounded.y,
                bounded.z,
                player.getYaw(), player.getPitch()
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean placeBlockServerEnforced(ServerWorld world, BlockPos pos, String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null) {
            AssistantMod.LOGGER.warn("Invalid block ID: {}", blockId);
            return false;
        }

        Block block = Registries.BLOCK.get(id);
        if (block == Blocks.AIR) {
            AssistantMod.LOGGER.warn("Unknown block ID (LLM hallucination?): {}", blockId);
            return false;
        }

        BlockState state = block.getDefaultState();
        boolean success = world.setBlockState(pos, state, Block.NOTIFY_ALL);

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

    // --- Helpers ---

    private void sendMessage(AssistantBot bot, String message) {
        ServerPlayerEntity owner = bot.getOwnerPlayer();
        if (owner != null && !owner.isDisconnected()) {
            owner.sendMessage(Text.literal(message));
        }
    }

    @Override
    public String getStatusString() {
        if (plan == null) {
            return "building: invalid plan #" + planId;
        }
        return switch (phase) {
            case WAITING -> "building: starting in " + (waitTicksRemaining * 5 / 20) + "s (plan #" + planId + ")";
            case CLEARING -> {
                int layersTotal = clearMax.getY() - clearMin.getY() + 1;
                int layersCleared = clearMax.getY() - clearCurrentY;
                yield "building: clearing area " + layersCleared + "/" + layersTotal + " layers (" + totalCleared + " blocks removed)";
            }
            case PLACING -> {
                int total = sortedBlocks != null ? sortedBlocks.size() : 0;
                yield "building: " + totalPlaced + "/" + total + " blocks placed (plan #" + planId + ")";
            }
            case DONE -> "building: complete (" + totalPlaced + " placed, " + totalSkipped + " skipped)";
        };
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (BuildTask compiles against BuildPlan/BuildPlanRegistry)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/task/BuildTask.java
git commit -m "Refactor BuildTask to consume plans from registry with 5s wait phase"
```

### Task 5: Add PlanTask auto-execute chaining in AssistantBot

**Files:**
- Modify: `src/main/java/com/assistantbot/bot/AssistantBot.java:124-131` (`onTaskComplete` method)

- [ ] **Step 1: Add PlanTask import and chaining logic to onTaskComplete**

In `AssistantBot.java`, add an import for `PlanTask`:

```java
import com.assistantbot.task.PlanTask;
```

(Add after the existing `import com.assistantbot.task.CombatTask;` line.)

Then replace the `onTaskComplete()` method (lines 124-131) with:

```java
    private void onTaskComplete() {
        // Auto-execute chaining: if PlanTask completed with autoExecute, start BuildTask
        if (currentTask instanceof PlanTask planTask && planTask.isAutoExecute()) {
            int planId = planTask.getLastPlanId();
            if (planId > 0) {
                AssistantMod.LOGGER.info("Plan #{} complete, auto-executing build at {}", planId, getBlockPos());
                currentTask = new BuildTask(planId, getBlockPos());
                currentTask.onStart(this);
                return;
            }
        }

        if (currentTask instanceof CombatTask && savedTask != null) {
            AssistantMod.LOGGER.info("Combat complete, resuming previous task");
            currentTask = savedTask;
            savedTask = null;
        } else {
            currentTask = new IdleTask();
        }
    }
```

Also add the BuildTask import if not already present:

```java
import com.assistantbot.task.BuildTask;
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/bot/AssistantBot.java
git commit -m "Add PlanTask auto-execute chaining in AssistantBot.onTaskComplete()"
```

---

## Chunk 4: Commands — plan, execute, plans, updated build

### Task 6: Add new commands and update build command

**Files:**
- Modify: `src/main/java/com/assistantbot/command/AssistantCommand.java`

- [ ] **Step 1: Add IntegerArgumentType import**

Add this import to the top of `AssistantCommand.java`:

```java
import com.mojang.brigadier.arguments.IntegerArgumentType;
```

Also add imports for the new types used:

```java
import com.assistantbot.llm.BuildPlan;
import com.assistantbot.llm.BuildPlanRegistry;
```

- [ ] **Step 2: Register plan, execute, and plans commands in the command tree**

In the `register()` method, add these three command registrations after the existing `build` block (after line 56, before the `.then(CommandManager.literal("status")` line):

```java
                .then(CommandManager.literal("plan")
                    .then(CommandManager.argument("description", StringArgumentType.greedyString())
                        .executes(AssistantCommand::plan)))
                .then(CommandManager.literal("execute")
                    .then(CommandManager.argument("id", IntegerArgumentType.integer(1))
                        .executes(AssistantCommand::execute)))
                .then(CommandManager.literal("plans")
                    .executes(AssistantCommand::listPlans))
```

- [ ] **Step 3: Add the plan command handler**

Add this method after the existing `build` method:

```java
    private static int plan(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String description = StringArgumentType.getString(ctx, "description");

        bot.setTask(new PlanTask(description, player));
        ctx.getSource().sendFeedback(
                () -> Text.literal("§a[Assistant] Planning: " + description + " (asking LLM...)"),
                false);
        return 1;
    }
```

- [ ] **Step 4: Add the execute command handler**

```java
    private static int execute(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        int planId = IntegerArgumentType.getInteger(ctx, "id");
        BuildPlan plan = BuildPlanRegistry.getInstance().get(planId);

        if (plan == null) {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("§c[Assistant] No plan found with ID: " + planId),
                    false);
            return 0;
        }

        BlockPos origin = bot.getBlockPos();
        bot.setTask(new BuildTask(planId, origin));
        ctx.getSource().sendFeedback(
                () -> Text.literal("§a[Assistant] Executing plan #" + planId + " (" + plan.getDescription()
                        + " — " + plan.getBlockCount() + " blocks) at " + origin.toShortString()),
                false);
        return 1;
    }
```

- [ ] **Step 5: Add the plans (list) command handler**

```java
    private static int listPlans(CommandContext<ServerCommandSource> ctx) {
        var plans = BuildPlanRegistry.getInstance().getAll();

        if (plans.isEmpty()) {
            ctx.getSource().sendFeedback(
                    () -> Text.literal("§e[Assistant] No plans available. Use /assistant plan <description> to create one."),
                    false);
            return 1;
        }

        StringBuilder sb = new StringBuilder("§b[Assistant] Available plans:");
        for (BuildPlan plan : plans) {
            sb.append("\n  §f#").append(plan.getId())
              .append(": \"").append(plan.getDescription())
              .append("\" — ").append(plan.getBlockCount())
              .append(" blocks (by ").append(plan.getCreatorName()).append(")");
        }

        String output = sb.toString();
        ctx.getSource().sendFeedback(() -> Text.literal(output), false);
        return 1;
    }
```

- [ ] **Step 6: Update the build command handler to use PlanTask with auto-execute**

Replace the existing `build` method (lines 143-155) with:

```java
    private static int build(CommandContext<ServerCommandSource> ctx) {
        AssistantBot bot = requireBot(ctx);
        if (bot == null) return 0;

        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;

        String description = StringArgumentType.getString(ctx, "description");

        PlanTask planTask = new PlanTask(description, player);
        planTask.setAutoExecute(true);
        bot.setTask(planTask);
        ctx.getSource().sendFeedback(
                () -> Text.literal("§a[Assistant] Planning and building: " + description + " (asking LLM...)"),
                false);
        return 1;
    }
```

- [ ] **Step 7: Add PlanTask import**

Add at the top of the file with the other task imports:

```java
import com.assistantbot.task.PlanTask;
```

- [ ] **Step 8: Update the class javadoc to include new commands**

Replace the class javadoc (lines 17-29) with:

```java
/**
 * Brigadier command tree for /assistant.
 *
 * Commands:
 *   /assistant summon              — spawn your personal bot
 *   /assistant dismiss             — remove your bot
 *   /assistant follow | come       — bot follows you
 *   /assistant stop                — bot goes idle
 *   /assistant mine <pos>          — mine block at position
 *   /assistant place <block> <pos> — place block
 *   /assistant deposit             — deposit inventory into nearest container
 *   /assistant plan <description>  — generate a build plan (LLM), returns plan ID
 *   /assistant execute <id>        — execute a stored plan at bot's current position
 *   /assistant plans               — list all available build plans
 *   /assistant build <description> — plan + auto-execute (convenience)
 *   /assistant status              — show current task and position
 */
```

- [ ] **Step 9: Verify compilation**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/assistantbot/command/AssistantCommand.java
git commit -m "Add plan, execute, plans commands and update build to use PlanTask"
```

### Task 7: Update AGENTS.md command table

**Files:**
- Modify: `AGENTS.md`

- [ ] **Step 1: Update the Commands table in AGENTS.md**

Replace the Commands table with the updated version including the new commands:

| Command | Description |
|---------|-------------|
| `/assistant summon` | Spawn your personal bot |
| `/assistant dismiss` | Remove your bot |
| `/assistant follow` / `come` | Bot follows you |
| `/assistant stop` | Bot goes idle |
| `/assistant mine <x> <y> <z>` | Mine block at position |
| `/assistant place <block> <x> <y> <z>` | Place a block |
| `/assistant deposit` | Deposit inventory into nearest container |
| `/assistant plan <description>` | Generate a build plan from LLM, returns plan ID |
| `/assistant execute <id>` | Execute a stored plan at bot's current position |
| `/assistant plans` | List all available build plans |
| `/assistant build <description>` | Plan + auto-execute (convenience shortcut) |
| `/assistant status` | Show current task and position |

- [ ] **Step 2: Commit**

```bash
git add AGENTS.md
git commit -m "Update AGENTS.md command table with plan/execute/plans commands"
```

### Task 8: Final verification

- [ ] **Step 1: Full build verification**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL with the output jar in `build/libs/`

- [ ] **Step 2: Verify all files are committed**

Run: `git status`
Expected: working tree clean
