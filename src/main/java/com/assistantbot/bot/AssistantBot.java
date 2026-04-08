package com.assistantbot.bot;

import com.assistantbot.AssistantMod;
import com.assistantbot.nav.BotPathfinder;
import com.assistantbot.task.BotTask;
import com.assistantbot.task.CombatTask;
import com.assistantbot.task.IdleTask;
import com.assistantbot.task.TickResult;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
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
    private BotPathfinder pathfinder;

    public AssistantBot(ServerPlayerEntity owner) {
        this.ownerUuid = owner.getUuid();
        this.ownerName = owner.getName().getString();
        this.botUuid = UUID.randomUUID();
        this.world = (ServerWorld) owner.getEntityWorld();

        GameProfile profile = new GameProfile(botUuid, "[Bot] " + ownerName);
        this.botPlayer = new BotPlayer(world.getServer(), world, profile);

        Vec3d ownerPos = owner.getEntityPos();
        this.botPlayer.spawn(
                ownerPos.x + 1, ownerPos.y, ownerPos.z + 1,
                owner.getYaw(), owner.getPitch()
        );

        this.currentTask = new IdleTask();
        this.lastKnownHealth = botPlayer.getHealth();
        this.pathfinder = new BotPathfinder(this);

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
    }

    private void checkCombatInterrupt() {
        float currentHealth = botPlayer.getHealth();
        if (currentHealth < lastKnownHealth && !(currentTask instanceof CombatTask)) {
            savedTask = currentTask;
            currentTask = new CombatTask();
            currentTask.onStart(this);
            AssistantMod.LOGGER.info("Bot entering combat (health {} -> {})", lastKnownHealth, currentHealth);
        }
    }

    private void onTaskComplete() {
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
        return currentTask.getStatusString();
    }
}
