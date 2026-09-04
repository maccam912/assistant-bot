package com.assistantbot.bot;

import com.assistantbot.AssistantMod;
import com.assistantbot.nav.BotPathfinder;
import com.assistantbot.task.BotTask;
import com.assistantbot.task.BuildRateLimiter;
import com.assistantbot.task.BuildUndoSnapshot;
import com.assistantbot.task.BuildTask;
import com.assistantbot.task.CombatTask;
import com.assistantbot.task.IdleTask;
import com.assistantbot.task.PlanTask;
import com.assistantbot.task.TickResult;
import com.assistantbot.task.UndoBuildTask;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Core bot class wrapping a BotPlayer. Owns the state machine that drives
 * tick-by-tick behavior. Inspired by third-principles-bot's mode/interrupt
 * architecture: the current task is the source of truth, combat interrupts
 * save/restore the previous task via boxing.
 */
public class AssistantBot {
    private final UUID ownerUuid;
    private final String ownerName;
    private final UUID botUuid;
    private ServerLevel world;
    private BotPlayer botPlayer;

    private BotTask currentTask;
    private BotTask savedTask; // saved during combat interrupt, restored after
    private float lastKnownHealth;
    private float lastKnownOwnerHealth;
    private BotPathfinder pathfinder;
    private double buildSpeedBlocksPerSecond = BuildRateLimiter.DEFAULT_BLOCKS_PER_SECOND;
    private BuildUndoSnapshot latestBuildUndo;

    private static final int DOWNED_DURATION_TICKS = 2400; // 2 minutes at 20 TPS
    private static final double DOWNED_SPEED_MULTIPLIER = 0.25;
    private static final int ARMOR_THRESHOLD = 10; // iron armor equivalent — skip penalty if armor >= this

    private long downedUntilTick = -1; // -1 = not downed; uses -1 sentinel to avoid tick-0 edge case

    public AssistantBot(ServerPlayer owner) {
        this.ownerUuid = owner.getUUID();
        this.ownerName = owner.getName().getString();
        this.botUuid = UUID.randomUUID();
        this.world = (ServerLevel) owner.level();

        GameProfile profile = new GameProfile(botUuid, "[Bot]" + ownerName);
        this.botPlayer = new BotPlayer(world.getServer(), world, profile);

        Vec3 ownerPos = owner.position();
        this.botPlayer.spawn(
                ownerPos.x + 1, ownerPos.y, ownerPos.z + 1,
                owner.getYRot(), owner.getXRot()
        );

        // Arm the bot with a netherite sword
        this.botPlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.NETHERITE_SWORD));

        this.currentTask = new IdleTask();
        this.lastKnownHealth = botPlayer.getHealth();
        this.lastKnownOwnerHealth = owner.getHealth();
        this.pathfinder = new BotPathfinder(this);
        this.botPlayer.setOnLethalDamageCallback(this::onLethalDamage);

        AssistantMod.LOGGER.info("Assistant bot spawned for {} at {}", ownerName, botPlayer.blockPosition());
    }

    public void tick() {
        if (botPlayer == null || world == null) return;

        checkCombatInterrupt();

        TickResult result = currentTask.tick(this);

        switch (result) {
            case CONTINUE -> { /* keep ticking */ }
            case COMPLETE -> onTaskComplete();
            case FAILED -> onTaskFailed();
        }

        lastKnownHealth = botPlayer.getHealth();
        ServerPlayer owner = getOwnerPlayer();
        if (owner != null) {
            lastKnownOwnerHealth = owner.getHealth();
        }
    }

    private void checkCombatInterrupt() {
        if (currentTask instanceof CombatTask) return;

        // Check 1: bot itself took damage
        float currentHealth = botPlayer.getHealth();
        if (currentHealth < lastKnownHealth) {
            enterCombat(null);
            AssistantMod.LOGGER.info("Bot entering combat — self-damage (health {} -> {})", lastKnownHealth, currentHealth);
            return;
        }

        // Check 2: owner took damage
        ServerPlayer owner = getOwnerPlayer();
        if (owner != null) {
            float ownerHealth = owner.getHealth();
            if (ownerHealth < lastKnownOwnerHealth) {
                Entity attacker = owner.getLastHurtByMob();
                enterCombat(attacker);
                AssistantMod.LOGGER.info("Bot entering combat — owner attacked (owner health {} -> {}, attacker: {})",
                        lastKnownOwnerHealth, ownerHealth,
                        attacker != null ? attacker.getType().getDescription().getString() : "unknown");
            }
        }
    }

    private void enterCombat(Entity initialTarget) {
        savedTask = currentTask;
        currentTask = (initialTarget != null) ? new CombatTask(initialTarget) : new CombatTask();
        currentTask.onStart(this);
    }

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

    private void onTaskFailed() {
        AssistantMod.LOGGER.warn("Task failed: {}", currentTask.getClass().getSimpleName());
        if (currentTask instanceof CombatTask && savedTask != null) {
            currentTask = savedTask;
            savedTask = null;
        } else {
            currentTask = new IdleTask();
        }
    }

    /**
     * Returns the current speed multiplier. 0.25 when downed, 1.0 normally.
     * Used by NavigationHelper to scale movement speed.
     */
    public double getSpeedMultiplier() {
        if (downedUntilTick >= 0) {
            long currentTick = world.getServer().getTickCount();
            if (currentTick < downedUntilTick) {
                return DOWNED_SPEED_MULTIPLIER;
            } else {
                downedUntilTick = -1; // recovered
                AssistantMod.LOGGER.info("Bot recovered from downed state");
            }
        }
        return 1.0;
    }

    /**
     * Sets the placement/finalization rate used by both active and future
     * builds. BuildTask reads this value every task tick, so changes take
     * effect without restarting the task.
     */
    public void setBuildSpeedBlocksPerSecond(double blocksPerSecond) {
        if (!BuildRateLimiter.isValid(blocksPerSecond)) {
            throw new IllegalArgumentException(
                    "Build speed must be at least "
                            + BuildRateLimiter.format(BuildRateLimiter.MIN_BLOCKS_PER_SECOND)
                            + " blocks per second");
        }
        buildSpeedBlocksPerSecond = blocksPerSecond;
    }

    public double getBuildSpeedBlocksPerSecond() {
        return buildSpeedBlocksPerSecond;
    }

    /** Starts a fresh one-build undo history once a valid build actually starts. */
    public BuildUndoSnapshot beginBuildUndo() {
        latestBuildUndo = new BuildUndoSnapshot(world);
        return latestBuildUndo;
    }

    /**
     * Interrupts any active work and starts restoring the latest build. Returns
     * the number of positions queued, or {@code -1} when there is no build to undo.
     * The history is consumed immediately, making undo intentionally one-shot.
     */
    public int undoLatestBuild() {
        if (latestBuildUndo == null) return -1;

        if (currentTask != null) currentTask.onStop(this);
        if (savedTask != null && savedTask != currentTask) savedTask.onStop(this);
        savedTask = null;

        BuildUndoSnapshot snapshot = latestBuildUndo;
        latestBuildUndo = null;
        currentTask = new UndoBuildTask(snapshot);
        currentTask.onStart(this);
        return snapshot.size();
    }

    private void onLethalDamage() {
        // Already downed — don't spam the log
        if (downedUntilTick >= 0) return;

        int armor = botPlayer.getArmorValue();
        if (armor >= ARMOR_THRESHOLD) {
            AssistantMod.LOGGER.info("Lethal damage absorbed by armor (armor={}), no slowdown", armor);
            return;
        }

        long currentTick = world.getServer().getTickCount();
        downedUntilTick = currentTick + DOWNED_DURATION_TICKS;
        AssistantMod.LOGGER.info("Bot downed! Moving at 25% speed for 2 minutes (armor={})", armor);
    }

    public void setTask(BotTask task) {
        if (currentTask != null) {
            currentTask.onStop(this);
        }
        this.currentTask = task;
        task.onStart(this);
    }

    public void destroy() {
        if (currentTask != null) {
            currentTask.onStop(this);
        }
        if (pathfinder != null) {
            pathfinder.destroy();
            pathfinder = null;
        }
        if (botPlayer != null) {
            botPlayer.despawn();
        }
        botPlayer = null;
        world = null;
        AssistantMod.LOGGER.info("Assistant bot for {} destroyed", ownerName);
    }

    // --- Accessors ---

    public ServerPlayer getFakePlayer() { return botPlayer; }
    public ServerLevel getWorld() { return world; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public BotTask getCurrentTask() { return currentTask; }
    public BotPathfinder getPathfinder() { return pathfinder; }

    public ServerPlayer getOwnerPlayer() {
        return world.getServer().getPlayerList().getPlayer(ownerUuid);
    }

    public Vec3 getPos() { return botPlayer.position(); }
    public BlockPos getBlockPos() { return botPlayer.blockPosition(); }

    public String getStatusString() {
        String taskStatus = currentTask.getStatusString();
        if (currentTask instanceof BuildTask || currentTask instanceof UndoBuildTask) {
            taskStatus += " @ " + BuildRateLimiter.format(buildSpeedBlocksPerSecond) + " blocks/s";
        }
        if (downedUntilTick >= 0) {
            long currentTick = world.getServer().getTickCount();
            if (currentTick < downedUntilTick) {
                long remainingTicks = downedUntilTick - currentTick;
                long remainingSeconds = remainingTicks / 20;
                return taskStatus + " (downed, " + remainingSeconds + "s remaining)";
            }
        }
        return taskStatus;
    }
}
