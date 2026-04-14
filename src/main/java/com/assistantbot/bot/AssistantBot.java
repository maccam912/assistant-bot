package com.assistantbot.bot;

import com.assistantbot.AssistantMod;
import com.assistantbot.nav.BotPathfinder;
import com.assistantbot.task.BotTask;
import com.assistantbot.task.BuildTask;
import com.assistantbot.task.CombatTask;
import com.assistantbot.task.IdleTask;
import com.assistantbot.task.PlanTask;
import com.assistantbot.task.TickResult;
import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

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
    private ServerWorld world;
    private BotPlayer botPlayer;

    private BotTask currentTask;
    private BotTask savedTask; // saved during combat interrupt, restored after
    private float lastKnownHealth;
    private float lastKnownOwnerHealth;
    private BotPathfinder pathfinder;

    private static final int DOWNED_DURATION_TICKS = 2400; // 2 minutes at 20 TPS
    private static final double DOWNED_SPEED_MULTIPLIER = 0.25;
    private static final int ARMOR_THRESHOLD = 10; // iron armor equivalent — skip penalty if armor >= this

    private long downedUntilTick = -1; // -1 = not downed; uses -1 sentinel to avoid tick-0 edge case

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
        ServerPlayerEntity owner = getOwnerPlayer();
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
        ServerPlayerEntity owner = getOwnerPlayer();
        if (owner != null) {
            float ownerHealth = owner.getHealth();
            if (ownerHealth < lastKnownOwnerHealth) {
                Entity attacker = owner.getAttacker();
                enterCombat(attacker);
                AssistantMod.LOGGER.info("Bot entering combat — owner attacked (owner health {} -> {}, attacker: {})",
                        lastKnownOwnerHealth, ownerHealth,
                        attacker != null ? attacker.getType().getName().getString() : "unknown");
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

    private void onLethalDamage() {
        // Already downed — don't spam the log
        if (downedUntilTick >= 0) return;

        int armor = botPlayer.getArmor();
        if (armor >= ARMOR_THRESHOLD) {
            AssistantMod.LOGGER.info("Lethal damage absorbed by armor (armor={}), no slowdown", armor);
            return;
        }

        long currentTick = world.getServer().getTicks();
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

    public ServerPlayerEntity getFakePlayer() { return botPlayer; }
    public ServerWorld getWorld() { return world; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public BotTask getCurrentTask() { return currentTask; }
    public BotPathfinder getPathfinder() { return pathfinder; }

    public ServerPlayerEntity getOwnerPlayer() {
        return world.getServer().getPlayerManager().getPlayer(ownerUuid);
    }

    public Vec3d getPos() { return botPlayer.getEntityPos(); }
    public BlockPos getBlockPos() { return botPlayer.getBlockPos(); }

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
}
