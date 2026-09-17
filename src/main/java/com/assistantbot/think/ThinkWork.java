package com.assistantbot.think;

import com.assistantbot.bot.AssistantBot;
import com.assistantbot.util.BlockHelper;
import com.assistantbot.util.InventoryHelper;
import com.assistantbot.util.LookHelper;
import com.assistantbot.util.NavigationHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Bounded local work. Only snapshot-offered choices can reach this executor. */
public final class ThinkWork {
    private ThinkProtocol.Work mining;
    private int breakingTicks;
    private ThinkProtocol.Work lastWork;
    private long startedTick;
    private long completedDecision = -1;
    private long executingDecision;
    private String completedOutcome = "";
    private final Map<ThinkProtocol.Work, Long> blockedUntil = new HashMap<>();
    private final Deque<String> recentWork = new ArrayDeque<>();
    private final LinkedHashMap<BlockPos, String> edits = new LinkedHashMap<>();

    public void pause() { mining = null; breakingTicks = 0; lastWork = null; }

    public Map<String, ThinkProtocol.Work> capture(AssistantBot bot, TypesafeConfig config, JsonObject state) {
        long tick = bot.getWorld().getGameTime();
        blockedUntil.entrySet().removeIf(e -> e.getValue() <= tick);
        Map<String, ThinkProtocol.Work> candidates = new LinkedHashMap<>();
        JsonArray inventory = new JsonArray();
        JsonObject materials = new JsonObject();
        JsonObject totals = new JsonObject();
        int logCount = 0;
        var inv = bot.getFakePlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            JsonObject entry = new JsonObject();
            entry.addProperty("item", id);
            entry.addProperty("count", stack.getCount());
            inventory.add(entry);
            totals.addProperty(id, stack.getCount() + (totals.has(id) ? totals.get(id).getAsInt() : 0));
            if (stack.is(ItemTags.LOGS)) logCount += stack.getCount();
            if (stack.getItem() instanceof BlockItem) {
                materials.addProperty(id, stack.getCount() + (materials.has(id) ? materials.get(id).getAsInt() : 0));
            }
            String planks = planksForLog(id);
            if (planks != null) offer(candidates, new ThinkProtocol.Work("craft", 0, 0, 0, id));
        }
        state.add("inventory", inventory);
        state.add("placeable_materials", materials);
        state.add("inventory_totals", totals);
        state.addProperty("inventory_owner", "bot (separate from owner inventory); items must be picked up before use");
        state.addProperty("collected_logs", logCount);
        state.addProperty("inventory_has_free_slot", inv.getFreeSlot() >= 0);
        state.addProperty("active_work", lastWork == null ? "none" : lastWork.toString());
        state.addProperty("mining_progress_ticks", breakingTicks);
        state.addProperty("capabilities", "Survival inventory. Can chop logs by hand, pick up drops, convert logs to planks, "
                + "place inventory blocks, dig with suitable tools, and walk. Cannot craft tools, doors, beds or torches. "
                + "A basic shelter can use solid blocks and an open entrance. Work candidates may require walking first.");
        JsonArray projectEdits = new JsonArray();
        edits.forEach((pos, expected) -> {
            JsonObject edit = new JsonObject();
            edit.addProperty("x", pos.getX()); edit.addProperty("y", pos.getY()); edit.addProperty("z", pos.getZ());
            edit.addProperty("expected_block", expected);
            edit.addProperty("current_block", bot.getWorld().hasChunkAt(pos)
                    ? BuiltInRegistries.BLOCK.getKey(bot.getWorld().getBlockState(pos).getBlock()).toString() : "unknown/unloaded");
            projectEdits.add(edit);
        });
        state.add("project_edits", projectEdits);
        JsonArray history = new JsonArray();
        recentWork.forEach(history::add);
        state.add("recent_work", history);
        JsonArray failures = new JsonArray();
        blockedUntil.keySet().forEach(w -> failures.add(w.toString()));
        state.add("temporarily_blocked_work", failures);
        BlockPos center = bot.getFakePlayer().blockPosition();
        List<BlockPos> positions = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -3, -4), center.offset(4, 4, 4))) {
            if (withinLeash(bot, config, pos)) positions.add(pos.immutable());
        }
        positions.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(bot.getPos())));
        JsonArray terrain = new JsonArray();
        int digs = 0, places = 0, moves = 0, logs = 0;
        for (BlockPos pos : positions) {
            JsonArray cell = new JsonArray();
            cell.add(pos.getX()); cell.add(pos.getY()); cell.add(pos.getZ());
            if (!bot.getWorld().hasChunkAt(pos)) { cell.add("unloaded"); terrain.add(cell); continue; }
            BlockState block = bot.getWorld().getBlockState(pos);
            String id = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            cell.add(id);
            terrain.add(cell);
            if (block.is(BlockTags.LOGS) && logs < 16 && !edits.containsKey(pos) && canDig(bot, config, pos) && naturalLog(bot, pos)) {
                offer(candidates, at("chop", pos, id)); logs++;
            }
            if (digs < 64 && canDig(bot, config, pos)) {
                offer(candidates, at("dig", pos, id)); digs++;
            }
            if (places < 64 && !materials.isEmpty() && canPlace(bot, config, pos)) {
                // Position and material are independent bounded choices, avoiding their Cartesian product.
                offer(candidates, at("place", pos, "selected material")); places++;
            }
            if (moves < 16 && standable(bot, pos) && pos.distManhattan(center) >= 2) {
                offer(candidates, at("move", pos, "")); moves++;
            }
        }
        // Resource search extends beyond the small construction window. Keep terrain and choices bounded.
        int radius = Math.min(12, (int) config.scanRadius());
        List<BlockPos> trees = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -3, -radius), center.offset(radius, 6, radius))) {
            if (Math.abs(pos.getX() - center.getX()) <= 4 && Math.abs(pos.getZ() - center.getZ()) <= 4
                    && pos.getY() <= center.getY() + 4) continue;
            if (withinLeash(bot, config, pos) && bot.getWorld().hasChunkAt(pos)
                    && bot.getWorld().getBlockState(pos).is(BlockTags.LOGS) && !edits.containsKey(pos)
                    && canDig(bot, config, pos) && naturalLog(bot, pos)) trees.add(pos.immutable());
        }
        trees.sort(Comparator.comparingDouble((BlockPos p) -> Vec3.atCenterOf(p).distanceToSqr(bot.getPos()))
                .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        for (BlockPos pos : trees) {
            if (logs >= 16) break;
            offer(candidates, at("chop", pos, BuiltInRegistries.BLOCK.getKey(bot.getWorld().getBlockState(pos).getBlock()).toString()));
            logs++;
        }
        for (ItemEntity item : bot.getWorld().getEntitiesOfClass(ItemEntity.class,
                bot.getFakePlayer().getBoundingBox().inflate(Math.min(8, config.scanRadius())), e -> e.isAlive()).stream()
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(bot.getFakePlayer()))).limit(16).toList()) {
            BlockPos pos = item.blockPosition();
            if (withinLeash(bot, config, pos)) offer(candidates, at(item.getItem().is(ItemTags.LOGS) ? "pickup_wood" : "pickup", pos,
                    item.getUUID().toString()));
        }
        state.add("terrain", terrain);
        state.addProperty("terrain_format", "Each cell is [world x, world y, world z, block id]. Local moving window; omitted cells are unknown. No template.");
        JsonObject options = new JsonObject();
        candidates.forEach((id, work) -> {
            String description = work.toString();
            if (!work.kind().equals("craft")) description += " distance_from_bot="
                    + Math.round(Vec3.atCenterOf(new BlockPos(work.x(), work.y(), work.z())).distanceTo(bot.getPos()) * 10) / 10.0;
            if (work.kind().startsWith("pickup")) {
                var entity = bot.getWorld().getEntity(UUID.fromString(work.block()));
                if (entity instanceof ItemEntity item) description += " item=" + BuiltInRegistries.ITEM.getKey(item.getItem().getItem())
                        + " count=" + item.getItem().getCount();
            }
            if (work.kind().equals("craft")) description += " consumes 1 log, produces 4 " + planksForLog(work.block());
            options.addProperty(id, description);
        });
        state.add("work_candidates", options);
        return candidates;
    }

    private static ThinkProtocol.Work at(String kind, BlockPos pos, String block) {
        return new ThinkProtocol.Work(kind, pos.getX(), pos.getY(), pos.getZ(), block);
    }

    /** These vanilla conversions need no crafting table. Other recipes aren't assumed. */
    public static String planksForLog(String id) {
        if (!id.startsWith("minecraft:")) return null;
        String name = id.substring(10).replaceFirst("^stripped_", "");
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak")) {
            if (name.equals(wood + "_log") || name.equals(wood + "_wood")) return "minecraft:" + wood + "_planks";
        }
        return null;
    }

    private static ThinkProtocol.Work cooldownKey(ThinkProtocol.Work work) {
        return work.kind().equals("place") ? new ThinkProtocol.Work("place", work.x(), work.y(), work.z(), "") : work;
    }

    private void offer(Map<String, ThinkProtocol.Work> candidates, ThinkProtocol.Work work) {
        if (!blockedUntil.containsKey(cooldownKey(work))) candidates.put("work_" + candidates.size(), work);
    }

    private boolean naturalLog(AssistantBot bot, BlockPos pos) {
        // Conservative tree heuristic: nearby leaves, never block entities or unbreakable blocks.
        if (bot.getWorld().getBlockEntity(pos) != null) return false;
        for (BlockPos nearby : BlockPos.betweenClosed(pos.offset(-2, 0, -2), pos.offset(2, 5, 2))) {
            if (bot.getWorld().hasChunkAt(nearby) && bot.getWorld().getBlockState(nearby).is(BlockTags.LEAVES)) return true;
        }
        return false;
    }

    private static boolean withinLeash(AssistantBot bot, TypesafeConfig config, BlockPos pos) {
        var owner = bot.getOwnerPlayer();
        return owner != null && Vec3.atCenterOf(pos).distanceTo(owner.position()) <= config.scanRadius()
                && Vec3.atCenterOf(pos).distanceTo(bot.getPos()) <= config.scanRadius();
    }

    private boolean canPlace(AssistantBot bot, TypesafeConfig config, BlockPos pos) {
        if (!withinLeash(bot, config, pos) || !bot.getWorld().hasChunkAt(pos)
                || !bot.getWorld().getBlockState(pos).isAir() || !bot.getWorld().getWorldBorder().isWithinBounds(pos)
                || !bot.getWorld().mayInteract(bot.getFakePlayer(), pos)
                || !bot.getWorld().getEntities(bot.getFakePlayer(), new AABB(pos), e -> e.isAlive()).isEmpty()
                || bot.getFakePlayer().getBoundingBox().intersects(new AABB(pos))) return false;
        for (Direction direction : Direction.values()) {
            BlockPos support = pos.relative(direction);
            if (bot.getWorld().hasChunkAt(support) && bot.getWorld().getBlockState(support).isRedstoneConductor(bot.getWorld(), support)) return true;
        }
        return false;
    }

    private boolean canDig(AssistantBot bot, TypesafeConfig config, BlockPos pos) {
        if (!withinLeash(bot, config, pos) || !bot.getWorld().hasChunkAt(pos)
                || !bot.getWorld().mayInteract(bot.getFakePlayer(), pos)
                || !bot.getWorld().getWorldBorder().isWithinBounds(pos)) return false;
        BlockState block = bot.getWorld().getBlockState(pos);
        if (block.isAir() || !block.getFluidState().isEmpty() || block.getDestroySpeed(bot.getWorld(), pos) < 0
                || bot.getWorld().getBlockEntity(pos) != null
                || new AABB(pos).intersects(bot.getFakePlayer().getBoundingBox().move(0, -1, 0))
                || new AABB(pos).intersects(bot.getOwnerPlayer().getBoundingBox().move(0, -1, 0))) return false;
        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.relative(side);
            if (bot.getWorld().hasChunkAt(neighbor) && bot.getWorld().getBlockState(neighbor).isAir()) return true;
        }
        return false;
    }

    private boolean standable(AssistantBot bot, BlockPos pos) {
        return bot.getWorld().hasChunkAt(pos) && bot.getWorld().hasChunkAt(pos.above())
                && bot.getWorld().hasChunkAt(pos.below()) && bot.getWorld().getBlockState(pos).isAir()
                && bot.getWorld().getBlockState(pos.above()).isAir()
                && bot.getWorld().getBlockState(pos.below()).isRedstoneConductor(bot.getWorld(), pos.below());
    }

    public String tick(AssistantBot bot, TypesafeConfig config, ThinkProtocol.Work work, long decisionVersion) {
        executingDecision = decisionVersion;
        if (completedDecision == decisionVersion) return completedOutcome;
        if (work == null) { pause(); return "no suitable work"; }
        long tick = bot.getWorld().getGameTime();
        if (!work.equals(lastWork)) { mining = null; breakingTicks = 0; lastWork = work; startedTick = tick; }
        if (blockedUntil.getOrDefault(cooldownKey(work), 0L) > tick) return "waiting for a different work candidate";
        if (tick - startedTick > 200) return blocked(work, tick, "work timed out; trying another candidate");
        if (work.kind().equals("craft")) {
            String output = planksForLog(work.block());
            if (output == null) return blocked(work, tick, "unsupported recipe");
            var player = bot.getFakePlayer();
            Item input = BuiltInRegistries.ITEM.getValue(Identifier.parse(work.block()));
            Item result = BuiltInRegistries.ITEM.getValue(Identifier.parse(output));
            if (!InventoryHelper.equipItem(player, input)) return "out of logs for crafting";
            var stack = new net.minecraft.world.item.ItemStack(result, 4);
            // Require space before consuming input; never duplicate or discard leftovers.
            if (player.getInventory().getFreeSlot() < 0 && InventoryHelper.countItem(player, result) == 0)
                return blocked(work, tick, "inventory full; cannot craft planks");
            player.getMainHandItem().shrink(1);
            player.getInventory().add(stack);
            if (!stack.isEmpty()) player.drop(stack, false);
            blockedUntil.put(work, tick + 20); // one conversion per fresh decision, not per server tick
            return record("crafted 4 " + output);
        }
        BlockPos pos = new BlockPos(work.x(), work.y(), work.z());
        if (!withinLeash(bot, config, pos) || !bot.getWorld().hasChunkAt(pos)
                || !bot.getWorld().getWorldBorder().isWithinBounds(pos) || !bot.getWorld().mayInteract(bot.getFakePlayer(), pos)) {
            return blocked(work, tick, "work outside loaded, permitted area");
        }
        if (work.kind().equals("move")) {
            if (!standable(bot, pos)) return blocked(work, tick, "movement destination changed");
            if (bot.getPos().distanceTo(Vec3.atBottomCenterOf(pos)) < 1) return record("at work destination " + pos.toShortString());
            Vec3 waypoint = bot.getPathfinder().getNextWaypoint(pos);
            if (waypoint == null) return blocked(work, tick, "no path to selected destination");
            NavigationHelper.moveToward(bot, waypoint, NavigationHelper.WALK_SPEED);
            return "moving to inspect/work on nearby terrain";
        }
        if (work.kind().startsWith("pickup")) {
            var entity = bot.getWorld().getEntity(UUID.fromString(work.block()));
            if (!(entity instanceof ItemEntity item) || !item.isAlive()) return record("item no longer available; check inventory");
            if (!withinLeash(bot, config, item.blockPosition())) return "item moved outside work area";
            if (bot.getPos().distanceTo(item.position()) > 1.2) return walk(bot, item.blockPosition());
            if (!bot.getFakePlayer().hasLineOfSight(item)) return blocked(work, tick, "item hidden behind a block");
            NavigationHelper.stopMoving(bot);
            int before = item.getItem().getCount();
            String itemId = BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString();
            item.playerTouch(bot.getFakePlayer());
            if (item.isAlive() && item.getItem().getCount() == before) return blocked(work, tick, "cannot pick up item; inventory full or pickup restricted");
            return record("picked up " + itemId + "; previous drop count=" + before);
        }
        BlockState state = bot.getWorld().getBlockState(pos);
        if ((work.kind().equals("dig") || work.kind().equals("chop"))
                && (!canDig(bot, config, pos) || !BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(work.block())
                || (work.kind().equals("chop") && (!state.is(BlockTags.LOGS) || !naturalLog(bot, pos)))))
            return "block changed or cannot be dug; waiting for a fresh snapshot";
        if (work.kind().equals("place") && (!canPlace(bot, config, pos))) return "placement changed or obstructed";
        if (bot.getFakePlayer().getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > BlockHelper.REACH_DISTANCE) return walk(bot, pos);
        var hit = bot.getWorld().clip(new ClipContext(bot.getFakePlayer().getEyePosition(), Vec3.atCenterOf(pos),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot.getFakePlayer()));
        if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(pos)) return blocked(work, tick, "work hidden behind another block");
        NavigationHelper.stopMoving(bot);
        bot.getPathfinder().clearPath();
        LookHelper.lookAt(bot.getFakePlayer(), Vec3.atCenterOf(pos));
        if (work.kind().equals("dig") || work.kind().equals("chop")) {
            InventoryHelper.equipBestTool(bot.getFakePlayer(), state);
            if (state.requiresCorrectToolForDrops() && !bot.getFakePlayer().hasCorrectToolForDrops(state))
                return blocked(work, tick, "need a suitable tool to collect drops from " + work.block());
            if (!work.equals(mining)) { mining = work; breakingTicks = 0; }
            float hardness = state.getDestroySpeed(bot.getWorld(), pos);
            if (hardness < 0) return "unbreakable block";
            breakingTicks += 5;
            if (breakingTicks < BlockHelper.calculateBreakTicks(bot.getFakePlayer(), state, hardness)) return "digging " + work.block();
            pause();
            boolean removed = bot.getFakePlayer().gameMode.destroyBlock(pos);
            if (removed && work.kind().equals("dig")) rememberEdit(pos, "minecraft:air");
            return removed ? record("broke " + work.block() + " at " + pos.toShortString()) : blocked(work, tick, "block breaking denied");
        }
        if (work.kind().equals("place")) {
            Item material = BuiltInRegistries.ITEM.getValue(Identifier.parse(work.block()));
            if (!(material instanceof BlockItem) || !InventoryHelper.equipItem(bot.getFakePlayer(), material)) return "out of selected building material";
            boolean success = BlockHelper.placeBlock(bot, pos);
            if (success && !bot.getWorld().getBlockState(pos).isAir()) {
                rememberEdit(pos, BuiltInRegistries.BLOCK.getKey(bot.getWorld().getBlockState(pos).getBlock()).toString());
                return record("placed " + work.block() + " at " + pos.toShortString());
            }
            return blocked(work, tick, "placement failed; trying another candidate");
        }
        return "unsupported work";
    }

    private String blocked(ThinkProtocol.Work work, long tick, String reason) {
        blockedUntil.put(cooldownKey(work), tick + 200);
        pause();
        recentWork.addLast(reason + ": " + work);
        while (recentWork.size() > 32) recentWork.removeFirst();
        return reason;
    }

    private String record(String result) {
        pause();
        completedDecision = executingDecision;
        completedOutcome = result;
        recentWork.addLast(result);
        while (recentWork.size() > 32) recentWork.removeFirst();
        return result;
    }

    private void rememberEdit(BlockPos pos, String block) {
        edits.put(pos.immutable(), block);
        while (edits.size() > 256) edits.remove(edits.keySet().iterator().next());
    }

    private String walk(AssistantBot bot, BlockPos pos) {
        // Search nearby standing spaces at multiple heights, not inside the target block.
        for (int dy = 0; dy >= -4; dy--) for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos beside = pos.relative(side).offset(0, dy, 0);
            if (!standable(bot, beside)) continue;
            Vec3 waypoint = bot.getPathfinder().getNextWaypoint(beside);
            if (waypoint != null) {
                NavigationHelper.moveToward(bot, waypoint, NavigationHelper.WALK_SPEED);
                return "approaching work";
            }
        }
        NavigationHelper.stopMoving(bot);
        return "no walkable path to work";
    }
}
