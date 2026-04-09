package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;
import com.assistantbot.util.InventoryHelper;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
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
    private static final double SCAN_RADIUS = 30.0;
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
        targetEntity = findNearestHostile(bot);
        if (targetEntity == null) {
            return TickResult.COMPLETE;
        }
        phase = CombatPhase.APPROACHING;
        return TickResult.CONTINUE;
    }

    private TickResult tickApproaching(AssistantBot bot) {
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

    private Entity findNearestHostile(AssistantBot bot) {
        Vec3d pos = bot.getPos();
        Box searchBox = new Box(
                pos.x - SCAN_RADIUS, pos.y - SCAN_RADIUS, pos.z - SCAN_RADIUS,
                pos.x + SCAN_RADIUS, pos.y + SCAN_RADIUS, pos.z + SCAN_RADIUS
        );

        List<HostileEntity> hostiles = bot.getWorld().getEntitiesByClass(
                HostileEntity.class, searchBox, Entity::isAlive
        );

        return hostiles.stream()
                .min((a, b) -> Double.compare(
                        a.squaredDistanceTo(pos.x, pos.y, pos.z),
                        b.squaredDistanceTo(pos.x, pos.y, pos.z)))
                .orElse(null);
    }

    @Override
    public String getStatusString() {
        return "in combat (" + phase + ", " + (ticksInCombat / 20) + "s)";
    }
}
