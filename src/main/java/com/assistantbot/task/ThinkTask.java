package com.assistantbot.task;

import com.assistantbot.bot.AssistantBot;
import com.assistantbot.think.*;
import com.assistantbot.util.InventoryHelper;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

/** Reactive high-level decisions with short, continuously revalidated movement/combat actions. */
public final class ThinkTask implements BotTask {
    private final TypesafeConfig config;
    private final ThinkLoop loop;
    private final ThinkMemory memory = new ThinkMemory();
    private Vec3 anchor;
    private long previousGoalVersion;
    private String outcome = "starting";
    private long nextAttackTick;
    private boolean askedForHelp;
    private final ThinkWork work = new ThinkWork();
    private static final String HELP = "I can follow, autoprotect, stay here, hunt, avoid fighting, collect wood, "
            + "and build or dig from your instructions, one block at a time. Try 'bot, build a stone arch', "
            + "'bot, dig a 3x3 pit here', or 'bot, collect wood'. Look at a block to point out 'that'. "
            + "I can gather missing materials and turn logs into planks, then resume. Other crafting recipes are not supported. "
            + "Use /assistant stop to stop.";

    public ThinkTask(TypesafeConfig config) {
        this.config = config;
        this.loop = new ThinkLoop(config, new TypesafeClient(config)::evaluate);
    }

    private static long nowMs() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
    public void hearOwner(String text) { memory.add(text, nowMs()); }
    public void setIntervalSeconds(double seconds) { loop.setIntervalMs((long) Math.ceil(seconds * 1000)); }
    public double intervalSeconds() { return loop.intervalMs() / 1000.0; }

    @Override public void onStart(AssistantBot bot) { anchor = bot.getPos(); }

    @Override public TickResult tick(AssistantBot bot) {
        ServerPlayer owner = bot.getOwnerPlayer();
        if (owner == null || !owner.isAlive() || owner.isSpectator() || owner.level() != bot.getWorld()) {
            loop.suspend();
            stopMovement(bot);
            work.pause();
            outcome = "waiting for a living owner in the same dimension";
            return TickResult.CONTINUE;
        }
        long now = nowMs();
        loop.tick(now, memory.revision(), () -> {
            // The loop may accept a new goal and capture its next snapshot in the same tick.
            if (loop.decision().goal() == ThinkProtocol.Goal.HOLD && previousGoalVersion != loop.goalVersion()) {
                anchor = bot.getPos();
            }
            return ThinkSnapshot.capture(bot, config, loop, memory, anchor, outcome, now, work);
        });
        var decision = loop.decision();
        if (loop.goalVersion() != previousGoalVersion) {
            if (decision.goal() == ThinkProtocol.Goal.HOLD) anchor = bot.getPos();
            work.pause();
            askedForHelp = false;
            previousGoalVersion = loop.goalVersion();
            stopMovement(bot);
            owner.sendSystemMessage(Component.literal("§b[Assistant] Goal: " + decision.goal().name().toLowerCase(java.util.Locale.ROOT)));
        }
        String reply = loop.takeReply();
        if (!reply.equals("NONE")) {
            String message = switch (reply) {
                case "COMPLETE" -> "The requested goal looks complete. I've cleared it and will stay here.";
                case "RELEASED" -> "I couldn't usefully continue that goal under the current conditions, so I've cleared it. It may be incomplete; give me a new instruction to continue.";
                case "UNSUPPORTED" -> "That is outside my current think-mode skills. " + HELP;
                default -> HELP;
            };
            owner.sendSystemMessage(Component.literal("§b[Assistant] " + message));
        }
        // Protect mode reacts locally to actual recent attacks, without waiting for network latency.
        // New chat still stops combat immediately while its meaning is evaluated.
        if (decision.goal() == ThinkProtocol.Goal.PROTECT && loop.canReact(now)
                && decision.action() != ThinkProtocol.Action.RETREAT && memory.revision() == loop.consumedRevision()
                && owner.getLastHurtByMob() instanceof Monster attacker && attacker.isAlive()
                && owner.tickCount - owner.getLastHurtByMobTimestamp() <= 100) {
            decision = new ThinkProtocol.Decision(decision.goal(), ThinkProtocol.Action.ATTACK, attacker.getUUID().toString(), 1);
        }
        if (decision.action() != ThinkProtocol.Action.WORK) work.pause();
        switch (decision.action()) {
            case COMPLETE, RELEASE_GOAL -> stopMovement(bot); // normalized to HOLD by ThinkLoop
            case ASK_HELP -> {
                stopMovement(bot);
                outcome = "need clarification, materials, tools, or a reachable next step; goal retained";
                if (!askedForHelp) {
                    owner.sendSystemMessage(Component.literal("§b[Assistant] I can't find a useful next step. "
                            + "Please clarify the target or supply missing materials/tools. I'm keeping your goal; /assistant stop cancels it."));
                    askedForHelp = true;
                }
            }
            case WAIT -> { stopMovement(bot); outcome = "watching"; }
            case FOLLOW_OWNER -> walk(bot, owner.position(), 3, NavigationHelper.WALK_SPEED);
            case RETURN_TO_ANCHOR -> walk(bot, anchor, 2, NavigationHelper.WALK_SPEED);
            case ATTACK, RETREAT -> reactToTarget(bot, owner, decision);
            case WORK -> {
                NavigationHelper.stopMoving(bot);
                outcome = work.tick(bot, config, decision.work(), loop.decisionVersion());
            }
        }
        return TickResult.CONTINUE;
    }

    private void reactToTarget(AssistantBot bot, ServerPlayer owner, ThinkProtocol.Decision decision) {
        Entity entity;
        try { entity = bot.getWorld().getEntity(UUID.fromString(decision.target())); }
        catch (IllegalArgumentException e) { entity = null; }
        double radiusSquared = config.scanRadius() * config.scanRadius();
        if (!(entity instanceof Monster target) || !target.isAlive()
                || target.level() != bot.getWorld()
                || target.distanceToSqr(bot.getFakePlayer()) > radiusSquared
                || target.distanceToSqr(owner) > radiusSquared
                || !bot.getFakePlayer().hasLineOfSight(target)) {
            stopMovement(bot);
            outcome = "target lost, hidden, dead, or outside leash";
            return;
        }
        if (decision.action() == ThinkProtocol.Action.RETREAT) {
            Vec3 away = bot.getPos().subtract(target.position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 0.01) away = new Vec3(1, 0, 0);
            walk(bot, bot.getPos().add(away.normalize().scale(6)), 1, NavigationHelper.SPRINT_SPEED);
            return;
        }
        // Never attack players, pets, passive mobs, through walls, or outside melee range.
        if (bot.getPos().distanceTo(target.position()) > 3) {
            walk(bot, target.position(), 2.5, NavigationHelper.SPRINT_SPEED);
            return;
        }
        stopMovement(bot);
        LookHelper.lookAt(bot.getFakePlayer(), target.position().add(0, target.getBbHeight() / 2, 0));
        long tick = bot.getWorld().getGameTime();
        if (tick >= nextAttackTick) {
            InventoryHelper.equipBestWeapon(bot.getFakePlayer());
            bot.getFakePlayer().attack(target);
            nextAttackTick = tick + 20;
        }
        outcome = "engaging " + target.getType().getDescription().getString();
    }

    private void walk(AssistantBot bot, Vec3 target, double arriveDistance, double speed) {
        LookHelper.lookAt(bot.getFakePlayer(), target.add(0, 1, 0));
        if (bot.getPos().distanceTo(target) <= arriveDistance) {
            stopMovement(bot);
            outcome = "at destination";
            return;
        }
        Vec3 waypoint = bot.getPathfinder().getNextWaypoint(BlockPos.containing(target));
        if (waypoint == null) {
            NavigationHelper.stopMoving(bot);
            outcome = "no walkable path to destination";
            return;
        }
        NavigationHelper.moveToward(bot, waypoint, speed);
        outcome = "moving";
    }

    private void stopMovement(AssistantBot bot) {
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
    }

    @Override public void onStop(AssistantBot bot) { loop.stop(); BotTask.super.onStop(bot); }
    @Override public String getStatusString() { return loop.status() + "; " + outcome; }
}
