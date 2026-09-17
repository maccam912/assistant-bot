package com.assistantbot.think;

import com.assistantbot.bot.AssistantBot;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.Vec3;

public final class ThinkSnapshot {
    private ThinkSnapshot() { }

    /** Must run on the server thread. The returned request contains JSON and strings only. */
    public static ThinkProtocol.Request capture(AssistantBot bot, TypesafeConfig config, ThinkLoop loop,
                                                ThinkMemory memory, Vec3 anchor, String outcome, long nowMs, ThinkWork work) {
        ServerPlayer owner = bot.getOwnerPlayer();
        ServerPlayer actor = bot.getFakePlayer();
        JsonObject state = new JsonObject();
        state.addProperty("dimension", bot.getWorld().dimension().toString());
        state.addProperty("world_tick", bot.getWorld().getGameTime());
        state.addProperty("raining", bot.getWorld().isRaining());
        state.add("bot", player(actor));
        state.getAsJsonObject("bot").addProperty("speed_multiplier", bot.getSpeedMultiplier());
        state.add("owner", player(owner));
        state.getAsJsonObject("owner").addProperty("distance_from_bot", bot.getPos().distanceTo(owner.position()));
        state.add("recent_owner_messages", memory.snapshot(nowMs, 0));
        state.add("new_owner_messages", memory.snapshot(nowMs, loop.consumedRevision()));
        JsonObject internal = new JsonObject();
        internal.addProperty("goal", loop.decision().goal().name());
        internal.add("anchor", position(anchor));
        internal.add("goal_instructions", loop.goalInstructions());
        internal.add("goal_focus", loop.goalFocus());
        internal.add("goal_origin", loop.goalOrigin());
        internal.addProperty("last_action", loop.decision().action().name());
        internal.addProperty("last_outcome", outcome);
        internal.addProperty("scan_radius", config.scanRadius());
        state.add("memory", internal);
        var view = bot.getWorld().clip(new net.minecraft.world.level.ClipContext(owner.getEyePosition(),
                owner.getEyePosition().add(owner.getLookAngle().scale(config.scanRadius())),
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, owner));
        if (view.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            JsonObject focus = position(Vec3.atLowerCornerOf(view.getBlockPos()));
            focus.addProperty("block", BuiltInRegistries.BLOCK.getKey(bot.getWorld().getBlockState(view.getBlockPos()).getBlock()).toString());
            focus.addProperty("face", view.getDirection().name());
            state.add("owner_looking_at", focus);
        }

        // Two local scans, never a giant bounding box across a separated owner and bot.
        var candidates = new LinkedHashMap<UUID, Monster>();
        for (ServerPlayer center : new ServerPlayer[]{actor, owner}) {
            for (Monster mob : bot.getWorld().getEntitiesOfClass(Monster.class,
                    center.getBoundingBox().inflate(config.scanRadius()), entity -> entity.isAlive())) {
                if (mob.distanceToSqr(owner) <= config.scanRadius() * config.scanRadius()
                        || mob.distanceToSqr(actor) <= config.scanRadius() * config.scanRadius()) {
                    candidates.put(mob.getUUID(), mob);
                }
            }
        }
        var hostiles = candidates.values().stream()
                .sorted(Comparator.comparingDouble(mob -> Math.min(mob.distanceToSqr(owner), mob.distanceToSqr(actor))))
                .limit(config.maxTargets()).toList();
        JsonArray entities = new JsonArray();
        for (Monster mob : hostiles) {
            JsonObject entity = new JsonObject();
            entity.addProperty("id", mob.getUUID().toString());
            entity.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
            entity.add("position", position(mob.position()));
            entity.addProperty("health", mob.getHealth());
            entity.addProperty("distance_to_owner", Math.sqrt(mob.distanceToSqr(owner)));
            entity.addProperty("distance_to_bot", Math.sqrt(mob.distanceToSqr(actor)));
            entity.addProperty("visible_to_bot", actor.hasLineOfSight(mob));
            entity.addProperty("visible_to_owner", owner.hasLineOfSight(mob));
            entity.addProperty("targeting_owner", mob.getTarget() == owner);
            entity.addProperty("targeting_bot", mob.getTarget() == actor);
            entity.addProperty("recent_attacker", recentAttacker(owner, mob) || recentAttacker(actor, mob));
            entity.addProperty("creeper_swelling", mob instanceof Creeper creeper && creeper.getSwellDir() > 0);
            entities.add(entity);
        }
        state.add("hostiles", entities);
        boolean needsWork = loop.decision().goal() == ThinkProtocol.Goal.PROJECT
                || loop.decision().goal() == ThinkProtocol.Goal.COLLECT_WOOD
                || !state.getAsJsonArray("new_owner_messages").isEmpty();
        return ThinkProtocol.request(state, config.model(), hostiles.stream().map(mob -> mob.getUUID().toString()).toList(),
                needsWork ? work.capture(bot, config, state) : java.util.Map.of());
    }

    private static boolean recentAttacker(LivingEntity player, Monster mob) {
        return player.getLastHurtByMob() == mob && player.tickCount - player.getLastHurtByMobTimestamp() <= 100;
    }

    private static JsonObject player(ServerPlayer player) {
        JsonObject result = new JsonObject();
        result.add("position", position(player.position()));
        result.addProperty("health", player.getHealth());
        result.addProperty("max_health", player.getMaxHealth());
        result.addProperty("armor", player.getArmorValue());
        result.addProperty("food", player.getFoodData().getFoodLevel());
        result.addProperty("on_fire", player.isOnFire());
        result.addProperty("in_water", player.isInWater());
        result.addProperty("held_item", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());
        result.addProperty("block_at_feet", BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(player.blockPosition()).getBlock()).toString());
        return result;
    }

    public static JsonObject position(Vec3 pos) {
        JsonObject result = new JsonObject();
        result.addProperty("x", Math.round(pos.x * 10) / 10.0);
        result.addProperty("y", Math.round(pos.y * 10) / 10.0);
        result.addProperty("z", Math.round(pos.z * 10) / 10.0);
        return result;
    }
}
