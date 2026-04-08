# A* Pathfinding Integration Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the bot's dumb straight-line movement with Minecraft's built-in A* pathfinding so it walks around obstacles, avoids holes, and navigates terrain intelligently.

**Architecture:** Create a `BotPathfinder` wrapper that uses a temporary invisible `ZombieEntity` as a dummy mob to satisfy `EntityNavigation`'s `MobEntity` requirement. The dummy mob is positioned at the bot's location, used only for A* path computation via `MobNavigation`, then the resulting `Path` (list of `PathNode` waypoints) is fed to `NavigationHelper` which walks the bot between waypoints using the existing velocity-based movement. Tasks remain unchanged except they call `NavigationHelper.navigateTo()` instead of `moveToward()`.

**Tech Stack:** Minecraft 1.21.11, Fabric API, Yarn mappings, Java 17. Uses `net.minecraft.entity.ai.pathing.*` (MobNavigation, LandPathNodeMaker, PathNodeNavigator, Path, PathNode) and `net.minecraft.entity.mob.ZombieEntity` as the dummy mob.

---

## Chunk 1: Core Pathfinding Infrastructure

### File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/com/assistantbot/nav/BotPathfinder.java` | Wraps MobNavigation, manages dummy mob, computes A* paths |
| Modify | `src/main/java/com/assistantbot/util/NavigationHelper.java` | Add `navigateTo()` that follows waypoints, keep `moveToward()` as low-level primitive |
| Modify | `src/main/java/com/assistantbot/bot/AssistantBot.java` | Own a `BotPathfinder` instance, expose it to tasks |

### Task 1: Create BotPathfinder

**Files:**
- Create: `src/main/java/com/assistantbot/nav/BotPathfinder.java`

- [ ] **Step 1: Create the `nav` package and `BotPathfinder` class**

```java
package com.assistantbot.nav;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps Minecraft's built-in A* pathfinding (MobNavigation + LandPathNodeMaker)
 * for use with our bot. Uses a temporary invisible ZombieEntity as a dummy mob
 * because the pathfinding system requires a MobEntity.
 *
 * Usage: call computePath() to get a list of waypoints, then iterate them
 * in NavigationHelper.
 */
public class BotPathfinder {
    private static final int PATHFINDING_RANGE = 32;
    private static final int RECOMPUTE_INTERVAL_TICKS = 20; // recompute every 1 second (at 4Hz task ticks = every 4 task ticks)

    private final AssistantBot bot;
    private ZombieEntity dummyMob;
    private MobNavigation navigation;

    // Current path state
    private @Nullable List<Vec3d> currentWaypoints;
    private int currentWaypointIndex;
    private @Nullable BlockPos currentTarget;
    private int ticksSinceLastCompute;

    public BotPathfinder(AssistantBot bot) {
        this.bot = bot;
        initDummyMob();
    }

    private void initDummyMob() {
        ServerWorld world = bot.getWorld();
        this.dummyMob = new ZombieEntity(EntityType.ZOMBIE, world);
        // Make it invisible and non-interacting — it exists only for path math
        dummyMob.setInvisible(true);
        dummyMob.setAiDisabled(true);
        dummyMob.setSilent(true);
        dummyMob.setNoGravity(true);
        // Do NOT spawn it into the world — just use it as a calculation vessel
        this.navigation = (MobNavigation) dummyMob.getNavigation();
    }

    /**
     * Compute a path from the bot's current position to the target.
     * Returns a list of waypoint Vec3d positions (block-centered on XZ,
     * feet-level Y), or null if no path found.
     */
    public @Nullable List<Vec3d> computePath(BlockPos target) {
        ServerWorld world = bot.getWorld();
        Vec3d botPos = bot.getPos();

        // Sync dummy mob to bot's position and world
        dummyMob.setPosition(botPos);

        // Use MobNavigation to compute the A* path
        Path path = navigation.findPathTo(target, 1);
        if (path == null) {
            AssistantMod.LOGGER.debug("No path found to {}", target);
            return null;
        }

        // Extract waypoints from the Path
        List<Vec3d> waypoints = new ArrayList<>();
        for (int i = 0; i < path.getLength(); i++) {
            PathNode node = path.getNode(i);
            // Center X/Z in the block, Y at foot level
            waypoints.add(new Vec3d(node.x + 0.5, node.y, node.z + 0.5));
        }

        return waypoints;
    }

    /**
     * Navigate to a target position. Call this every task tick (~4Hz).
     * Handles path computation, caching, recomputation when target moves,
     * and returns the next waypoint the bot should walk toward.
     *
     * @return the next waypoint to walk toward, or null if already at target / no path
     */
    public @Nullable Vec3d getNextWaypoint(BlockPos target) {
        ticksSinceLastCompute += 5; // called every 5 game ticks

        boolean needsRecompute = false;

        // Recompute if target changed
        if (currentTarget == null || !currentTarget.equals(target)) {
            needsRecompute = true;
        }

        // Recompute periodically (target may have moved, or we got stuck)
        if (ticksSinceLastCompute >= RECOMPUTE_INTERVAL_TICKS) {
            needsRecompute = true;
        }

        // Recompute if we finished or lost our path
        if (currentWaypoints == null || currentWaypointIndex >= currentWaypoints.size()) {
            needsRecompute = true;
        }

        if (needsRecompute) {
            currentWaypoints = computePath(target);
            currentWaypointIndex = 0;
            currentTarget = target;
            ticksSinceLastCompute = 0;

            if (currentWaypoints == null || currentWaypoints.isEmpty()) {
                return null;
            }
        }

        // Advance past waypoints we've already reached
        Vec3d botPos = bot.getPos();
        while (currentWaypointIndex < currentWaypoints.size()) {
            Vec3d waypoint = currentWaypoints.get(currentWaypointIndex);
            double horizontalDist = Math.sqrt(
                    Math.pow(botPos.x - waypoint.x, 2) +
                    Math.pow(botPos.z - waypoint.z, 2)
            );
            // Consider waypoint reached if within ~0.7 blocks horizontally
            if (horizontalDist < 0.7) {
                currentWaypointIndex++;
            } else {
                break;
            }
        }

        if (currentWaypointIndex >= currentWaypoints.size()) {
            return null; // reached end of path
        }

        return currentWaypoints.get(currentWaypointIndex);
    }

    /**
     * Clear any cached path. Call when switching tasks or stopping movement.
     */
    public void clearPath() {
        currentWaypoints = null;
        currentWaypointIndex = 0;
        currentTarget = null;
        ticksSinceLastCompute = 0;
    }

    /**
     * @return true if we have an active path with waypoints remaining
     */
    public boolean hasPath() {
        return currentWaypoints != null && currentWaypointIndex < currentWaypoints.size();
    }

    /**
     * Clean up the dummy mob. Call when the bot is destroyed.
     */
    public void destroy() {
        if (dummyMob != null) {
            dummyMob.discard();
            dummyMob = null;
            navigation = null;
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (no usages yet, just the class existing)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/nav/BotPathfinder.java
git commit -m "feat: add BotPathfinder wrapper for A* pathfinding via dummy mob"
```

---

### Task 2: Wire BotPathfinder into AssistantBot

**Files:**
- Modify: `src/main/java/com/assistantbot/bot/AssistantBot.java`

- [ ] **Step 1: Add BotPathfinder field and accessor**

Add import at the top:
```java
import com.assistantbot.nav.BotPathfinder;
```

Add field after `private float lastKnownHealth;`:
```java
private BotPathfinder pathfinder;
```

Initialize in constructor, after `this.lastKnownHealth = botPlayer.getHealth();`:
```java
this.pathfinder = new BotPathfinder(this);
```

Add accessor with other accessors:
```java
public BotPathfinder getPathfinder() { return pathfinder; }
```

- [ ] **Step 2: Clean up pathfinder on destroy**

In `destroy()`, before `botPlayer.despawn()`:
```java
if (pathfinder != null) {
    pathfinder.destroy();
    pathfinder = null;
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/assistantbot/bot/AssistantBot.java
git commit -m "feat: wire BotPathfinder into AssistantBot lifecycle"
```

---

### Task 3: Add `navigateTo()` to NavigationHelper

**Files:**
- Modify: `src/main/java/com/assistantbot/util/NavigationHelper.java`

- [ ] **Step 1: Add the `navigateTo()` method**

This is the new high-level method that tasks will call instead of `moveToward()`. It uses the pathfinder to get waypoints and walks toward the next one.

Add import:
```java
import com.assistantbot.nav.BotPathfinder;
```

Add method after `moveToward()`:
```java
/**
 * Navigate to a target position using A* pathfinding. Computes a path
 * around obstacles and follows waypoints. Falls back to direct movement
 * if pathfinding fails (e.g., target is very close or unreachable).
 *
 * Call this from task tick (~4Hz) instead of moveToward() for smart navigation.
 *
 * @return true if actively navigating, false if at destination or no path
 */
public static boolean navigateTo(AssistantBot bot, BlockPos target, double speed) {
    BotPathfinder pathfinder = bot.getPathfinder();
    Vec3d nextWaypoint = pathfinder.getNextWaypoint(target);

    if (nextWaypoint == null) {
        // No path or already at target — check if we're close enough
        // to just walk directly (handles the last-meter case)
        Vec3d targetVec = Vec3d.ofCenter(target);
        if (bot.getPos().distanceTo(targetVec) < 2.0) {
            stopMoving(bot);
            return false;
        }
        // Pathfinding failed — fall back to direct movement
        moveToward(bot, targetVec, speed);
        return true;
    }

    moveToward(bot, nextWaypoint, speed);
    return true;
}
```

- [ ] **Step 2: Add an overload that takes Vec3d target**

Tasks like FollowTask target a Vec3d (player position), not a BlockPos. Add a convenience overload:

```java
/**
 * Navigate to a Vec3d target using A* pathfinding. Converts to BlockPos
 * for path computation, walks toward exact waypoints.
 */
public static boolean navigateTo(AssistantBot bot, Vec3d target, double speed) {
    return navigateTo(bot, BlockPos.ofFloored(target), speed);
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/assistantbot/util/NavigationHelper.java
git commit -m "feat: add navigateTo() with A* pathfinding support"
```

---

## Chunk 2: Update All Tasks to Use Pathfinding

### Task 4: Update FollowTask

**Files:**
- Modify: `src/main/java/com/assistantbot/task/FollowTask.java`

- [ ] **Step 1: Replace `moveToward()` with `navigateTo()`**

Change the movement call in `tick()`. The follow task is the simplest case — just swap the call:

Replace:
```java
LookHelper.lookAt(bot.getFakePlayer(), ownerPos.add(0, owner.getStandingEyeHeight(), 0));
NavigationHelper.moveToward(bot, ownerPos, NavigationHelper.WALK_SPEED);
```

With:
```java
LookHelper.lookAt(bot.getFakePlayer(), ownerPos.add(0, owner.getStandingEyeHeight(), 0));
NavigationHelper.navigateTo(bot, ownerPos, NavigationHelper.WALK_SPEED);
```

Also add path clearing when the bot stops (within follow distance) or teleports. In the `distance <= FOLLOW_DISTANCE` block, add:
```java
bot.getPathfinder().clearPath();
```

And in the `distance > TOO_FAR_DISTANCE` block, add:
```java
bot.getPathfinder().clearPath();
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/task/FollowTask.java
git commit -m "feat: FollowTask uses A* pathfinding"
```

---

### Task 5: Update MineTask

**Files:**
- Modify: `src/main/java/com/assistantbot/task/MineTask.java`

- [ ] **Step 1: Replace `moveToward()` with `navigateTo()` in APPROACHING phase**

In `tickApproaching()`, replace:
```java
LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
NavigationHelper.moveToward(bot, targetCenter, NavigationHelper.WALK_SPEED);
```

With:
```java
LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
NavigationHelper.navigateTo(bot, targetPos, NavigationHelper.WALK_SPEED);
```

Also add path clearing when transitioning to EQUIPPING. In the `distance <= BlockHelper.REACH_DISTANCE` block, add before `phase = MinePhase.EQUIPPING;`:
```java
bot.getPathfinder().clearPath();
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/task/MineTask.java
git commit -m "feat: MineTask uses A* pathfinding for approach"
```

---

### Task 6: Update PlaceTask

**Files:**
- Modify: `src/main/java/com/assistantbot/task/PlaceTask.java`

- [ ] **Step 1: Replace `moveToward()` with `navigateTo()` in APPROACHING phase**

In `tickApproaching()`, replace:
```java
LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
NavigationHelper.moveToward(bot, targetCenter, NavigationHelper.WALK_SPEED);
```

With:
```java
LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
NavigationHelper.navigateTo(bot, targetPos, NavigationHelper.WALK_SPEED);
```

Add path clearing when transitioning to EQUIPPING. In the `distance <= BlockHelper.REACH_DISTANCE` block, add before `phase = PlacePhase.EQUIPPING;`:
```java
bot.getPathfinder().clearPath();
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/task/PlaceTask.java
git commit -m "feat: PlaceTask uses A* pathfinding for approach"
```

---

### Task 7: Update CombatTask

**Files:**
- Modify: `src/main/java/com/assistantbot/task/CombatTask.java`

- [ ] **Step 1: Replace `moveToward()` with `navigateTo()` in APPROACHING phase**

In `tickApproaching()`, replace:
```java
NavigationHelper.moveToward(bot, targetPos, NavigationHelper.SPRINT_SPEED);
```

With:
```java
NavigationHelper.navigateTo(bot, targetPos, NavigationHelper.SPRINT_SPEED);
```

Add path clearing when transitioning away from APPROACHING. In the `distance <= ATTACK_RANGE` block:
```java
bot.getPathfinder().clearPath();
```

And in the dead/null target block:
```java
bot.getPathfinder().clearPath();
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/task/CombatTask.java
git commit -m "feat: CombatTask uses A* pathfinding for approach"
```

---

### Task 8: Update DepositTask

**Files:**
- Modify: `src/main/java/com/assistantbot/task/DepositTask.java`

- [ ] **Step 1: Replace `moveToward()` with `navigateTo()` in APPROACHING phase**

In `tickApproaching()`, replace:
```java
LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
NavigationHelper.moveToward(bot, targetCenter, NavigationHelper.WALK_SPEED);
```

With:
```java
LookHelper.lookAt(bot.getFakePlayer(), targetCenter);
NavigationHelper.navigateTo(bot, containerPos, NavigationHelper.WALK_SPEED);
```

Add path clearing when transitioning to DEPOSITING. In the `distance <= 3.0` block, add before `phase = DepositPhase.DEPOSITING;`:
```java
bot.getPathfinder().clearPath();
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/task/DepositTask.java
git commit -m "feat: DepositTask uses A* pathfinding for approach"
```

---

### Task 9: Clear pathfinder in BotTask.onStop()

**Files:**
- Modify: `src/main/java/com/assistantbot/task/BotTask.java`

- [ ] **Step 1: Add pathfinder clearing to default onStop()**

This ensures any active path is cleared whenever a task is swapped, preventing stale paths from leaking across tasks.

Replace the default `onStop()`:
```java
default void onStop(AssistantBot bot) {
    NavigationHelper.stopMoving(bot);
}
```

With:
```java
default void onStop(AssistantBot bot) {
    NavigationHelper.stopMoving(bot);
    bot.getPathfinder().clearPath();
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/assistantbot/task/BotTask.java
git commit -m "feat: clear pathfinder state on task stop"
```

---

## Chunk 3: Update NavigationHelper comments, build, bump version

### Task 10: Update NavigationHelper doc comment and version bump

**Files:**
- Modify: `src/main/java/com/assistantbot/util/NavigationHelper.java` (update class javadoc)
- Modify: `gradle.properties` (version bump to 0.4.0)

- [ ] **Step 1: Update NavigationHelper class javadoc**

Replace the existing class javadoc:
```java
/**
 * Simple movement primitives for the fake player. Sets entity velocity
 * and lets Minecraft's built-in physics (gravity, collision, friction)
 * handle the actual movement at 20Hz. No A* pathfinding yet — walks
 * toward target in a straight line, jumping over 1-block obstacles.
 */
```

With:
```java
/**
 * Movement primitives for the fake player. Provides two levels of navigation:
 *
 * - {@link #moveToward} — Low-level: walk in a straight line toward a point.
 *   Sets persistent velocity; Minecraft's physics handles gravity/collision at 20Hz.
 *
 * - {@link #navigateTo} — High-level: A* pathfinding via BotPathfinder. Computes
 *   a path around obstacles and follows waypoints. Falls back to moveToward if
 *   pathfinding fails.
 *
 * Tasks should use navigateTo() for approach phases. moveToward() remains
 * available for short-range direct movement.
 */
```

- [ ] **Step 2: Bump version to 0.4.0**

In `gradle.properties`, change:
```
mod_version=0.3.1
```
To:
```
mod_version=0.4.0
```

- [ ] **Step 3: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit and tag**

```bash
git add -A
git commit -m "feat: A* pathfinding via built-in mob navigation

Uses a dummy ZombieEntity for path computation through MobNavigation
(LandPathNodeMaker + PathNodeNavigator). All tasks now use navigateTo()
for approach phases, which follows A* waypoints around obstacles.

Falls back to direct movement for short range or when pathfinding fails.
Path is recomputed every ~1s to handle moving targets."
git tag v0.4.0
```

---

## Risk Notes

1. **Dummy ZombieEntity not spawned in world**: We call `new ZombieEntity()` but never `world.spawnEntity()`. The mob exists only in memory for path calculation. We need to verify that `MobNavigation.findPathTo()` works when the entity isn't spawned — it should, since it only needs the entity's position and dimensions for the `ChunkCache` and `PathNodeMaker`, not world registration. If this doesn't work, we may need to briefly add/remove it from the world, or use `world.spawnEntity()` with invisibility.

2. **Step height mismatch**: ZombieEntity has step height 0.6 (standard mob), while players have step height 0.6 too (since 1.20). This should be fine.

3. **Entity dimensions**: ZombieEntity is 0.6 wide x 1.95 tall. Player is 0.6 wide x 1.8 tall. The zombie is slightly taller, so paths will be conservative (won't path through 1.8-high gaps a player could fit through). This is acceptable — being too cautious is better than getting stuck.

4. **Performance**: Path computation uses ChunkCache (snapshot), so it doesn't hold chunk locks. Computing every ~1s for one bot is negligible. If we later support multiple bots, we may want to stagger recomputation.

5. **1.21 API compatibility**: The `MobNavigation` constructor takes `(MobEntity, World)`. We're on 1.21.11. The Yarn mappings research confirmed these signatures. If any names differ in 1.21.11 vs 1.21.1, the build will immediately tell us.
