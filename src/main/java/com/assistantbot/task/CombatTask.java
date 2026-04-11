package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;
import com.assistantbot.util.InventoryHelper;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Reactive combat: triggered by health-drop interrupt in AssistantBot.
 * Equips best weapon, scans for nearest hostile, approaches, attacks.
 * 5-minute timeout prevents getting stuck. Previous task is restored
 * on completion (interrupt-via-boxing pattern from third-principles-bot).
 */
public class CombatTask implements BotTask {
    private static final double SCAN_RADIUS_HORIZONTAL = 10.0;
    private static final double SCAN_RADIUS_VERTICAL = 5.0;
    private static final double ATTACK_RANGE = 3.0;
    private static final int TIMEOUT_TICKS = 6000; // 5 min safety valve (ticksInCombat increments by 5 per task tick)
    private static final int ATTACK_COOLDOWN_TICKS = 4;

    private CombatPhase phase;
    private Entity targetEntity;
    private int ticksInCombat;
    private int attackCooldown;

    private enum CombatPhase { EQUIPPING, SCANNING, APPROACHING, ATTACKING }

    public CombatTask() {
        this.phase = CombatPhase.EQUIPPING;
    }

    /**
     * Create a CombatTask with a pre-identified target (e.g., the entity
     * attacking the owner, obtained via owner.getAttacker()).
     */
    public CombatTask(Entity initialTarget) {
        this.phase = CombatPhase.EQUIPPING;
        this.targetEntity = initialTarget;
    }

    @Override
    public TickResult tick(AssistantBot bot) {
        ticksInCombat += 5;
        if (ticksInCombat > TIMEOUT_TICKS) {
            return TickResult.COMPLETE;
        }

        return switch (phase) {
            case EQUIPPING  -> tickEquipping(bot);
            case SCANNING   -> tickScanning(bot);
            case APPROACHING -> tickApproaching(bot);
            case ATTACKING  -> tickAttacking(bot);
        };
    }

    private TickResult tickEquipping(AssistantBot bot) {
        InventoryHelper.equipBestWeapon(bot.getFakePlayer());
        phase = CombatPhase.SCANNING;
        return TickResult.CONTINUE;
    }

    private TickResult tickScanning(AssistantBot bot) {
        // If we already have a living target (e.g., pre-identified attacker), skip scan
        if (targetEntity != null && targetEntity.isAlive()) {
            phase = CombatPhase.APPROACHING;
            return TickResult.CONTINUE;
        }
        targetEntity = findNearestHostile(bot);
        if (targetEntity == null) {
            return TickResult.COMPLETE;
        }
        phase = CombatPhase.APPROACHING;
        return TickResult.CONTINUE;
    }

    private TickResult tickApproaching(AssistantBot bot) {
        // Re-evaluate nearest hostile while approaching to always chase the closest threat
        Entity nearest = findNearestHostile(bot);
        if (nearest != null) {
            targetEntity = nearest;
        } else {
            // No hostiles within scan radius — disengage
            NavigationHelper.stopMoving(bot);
            bot.getPathfinder().clearPath();
            phase = CombatPhase.SCANNING;
            return TickResult.CONTINUE;
        }

        if (targetEntity == null || !targetEntity.isAlive()) {
            NavigationHelper.stopMoving(bot);
            bot.getPathfinder().clearPath();
            phase = CombatPhase.SCANNING;
            return TickResult.CONTINUE;
        }

        Vec3d targetPos = targetEntity.getEntityPos();
        double distance = bot.getPos().distanceTo(targetPos);

        LookHelper.lookAt(bot.getFakePlayer(), targetPos.add(0, targetEntity.getHeight() / 2, 0));

        if (distance <= ATTACK_RANGE) {
            NavigationHelper.stopMoving(bot);
            bot.getPathfinder().clearPath();
            phase = CombatPhase.ATTACKING;
            return TickResult.CONTINUE;
        }

        NavigationHelper.navigateTo(bot, targetPos, NavigationHelper.SPRINT_SPEED);
        return TickResult.CONTINUE;
    }

    private TickResult tickAttacking(AssistantBot bot) {
        // Re-evaluate nearest hostile before every attack to always target the closest threat
        Entity nearest = findNearestHostile(bot);
        if (nearest != null) {
            targetEntity = nearest;
        } else {
            // No hostiles within scan radius — disengage
            phase = CombatPhase.SCANNING;
            return TickResult.CONTINUE;
        }

        if (targetEntity == null || !targetEntity.isAlive()) {
            phase = CombatPhase.SCANNING;
            return TickResult.CONTINUE;
        }

        Vec3d targetPos = targetEntity.getEntityPos();
        double distance = bot.getPos().distanceTo(targetPos);

        if (distance > ATTACK_RANGE + 1) {
            bot.getPathfinder().clearPath();
            phase = CombatPhase.APPROACHING;
            return TickResult.CONTINUE;
        }

        LookHelper.lookAt(bot.getFakePlayer(), targetPos.add(0, targetEntity.getHeight() / 2, 0));

        if (attackCooldown <= 0) {
            bot.getFakePlayer().attack(targetEntity);
            attackCooldown = ATTACK_COOLDOWN_TICKS;
        } else {
            attackCooldown--;
        }

        return TickResult.CONTINUE;
    }

    /**
     * Scans for the nearest hostile entity around the bot's position
     * within a tight area: 10 blocks horizontal, 5 blocks vertical.
     * Falls back to the bot's position if the owner is offline.
     */
    private Entity findNearestHostile(AssistantBot bot) {
        ServerPlayerEntity owner = bot.getOwnerPlayer();
        Vec3d scanCenter = (owner != null) ? owner.getEntityPos() : bot.getPos();
        Box searchBox = new Box(
                scanCenter.x - SCAN_RADIUS_HORIZONTAL, scanCenter.y - SCAN_RADIUS_VERTICAL, scanCenter.z - SCAN_RADIUS_HORIZONTAL,
                scanCenter.x + SCAN_RADIUS_HORIZONTAL, scanCenter.y + SCAN_RADIUS_VERTICAL, scanCenter.z + SCAN_RADIUS_HORIZONTAL
        );

        List<HostileEntity> hostiles = bot.getWorld().getEntitiesByClass(
                HostileEntity.class, searchBox, Entity::isAlive
        );

        return hostiles.stream()
                .min((a, b) -> Double.compare(
                        a.squaredDistanceTo(scanCenter.x, scanCenter.y, scanCenter.z),
                        b.squaredDistanceTo(scanCenter.x, scanCenter.y, scanCenter.z)))
                .orElse(null);
    }

    @Override
    public String getStatusString() {
        return "in combat (" + phase + ", " + (ticksInCombat / 20) + "s)";
    }
}
