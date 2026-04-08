package com.assistantbot.bot;

import com.assistantbot.AssistantMod;
import com.assistantbot.task.BotTask;
import com.assistantbot.task.CombatTask;
import com.assistantbot.task.IdleTask;
import com.assistantbot.task.TickResult;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Core bot class wrapping a FakePlayer. Owns the state machine that drives
 * tick-by-tick behavior. Inspired by third-principles-bot's mode/interrupt
 * architecture: the current task is the source of truth, combat interrupts
 * save/restore the previous task via boxing.
 */
public class AssistantBot {
    private final UUID ownerUuid;
    private final String ownerName;
    private final UUID botUuid;
    private ServerWorld world;
    private FakePlayer fakePlayer;

    private BotTask currentTask;
    private BotTask savedTask; // saved during combat interrupt, restored after
    private float lastKnownHealth;

    public AssistantBot(ServerPlayerEntity owner) {
        this.ownerUuid = owner.getUuid();
        this.ownerName = owner.getName().getString();
        this.botUuid = UUID.randomUUID();
        this.world = (ServerWorld) owner.getEntityWorld();

        GameProfile profile = new GameProfile(botUuid, "[Bot] " + ownerName);
        this.fakePlayer = FakePlayer.get(world, profile);

        Vec3d ownerPos = owner.getEntityPos();
        this.fakePlayer.refreshPositionAndAngles(
                ownerPos.x + 1, ownerPos.y, ownerPos.z + 1,
                owner.getYaw(), owner.getPitch()
        );

        this.currentTask = new IdleTask();
        this.lastKnownHealth = fakePlayer.getHealth();

        AssistantMod.LOGGER.info("Assistant bot spawned for {} at {}", ownerName, fakePlayer.getBlockPos());
    }

    public void tick() {
        if (fakePlayer == null || world == null) return;

        checkCombatInterrupt();

        TickResult result = currentTask.tick(this);

        switch (result) {
            case CONTINUE -> { /* keep ticking */ }
            case COMPLETE -> onTaskComplete();
            case FAILED -> onTaskFailed();
        }

        lastKnownHealth = fakePlayer.getHealth();
    }

    private void checkCombatInterrupt() {
        float currentHealth = fakePlayer.getHealth();
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
        fakePlayer = null;
        world = null;
        AssistantMod.LOGGER.info("Assistant bot for {} destroyed", ownerName);
    }

    // --- Accessors ---

    public FakePlayer getFakePlayer() { return fakePlayer; }
    public ServerWorld getWorld() { return world; }
    public UUID getOwnerUuid() { return ownerUuid; }
    public String getOwnerName() { return ownerName; }
    public BotTask getCurrentTask() { return currentTask; }

    public ServerPlayerEntity getOwnerPlayer() {
        return world.getServer().getPlayerManager().getPlayer(ownerUuid);
    }

    public Vec3d getPos() { return fakePlayer.getEntityPos(); }
    public BlockPos getBlockPos() { return fakePlayer.getBlockPos(); }

    public String getStatusString() {
        return currentTask.getStatusString();
    }
}
