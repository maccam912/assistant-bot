package com.assistantbot.llm;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.BuildStructure.Cell;
import com.assistantbot.llm.BuildStructure.PlacementGroup;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Derives Minecraft block states from a drawn VXB-2 glyph grid.
 *
 * <p>Directional state is the single largest source of LLM error in voxel
 * formats: a model that can draw a correct staircase still gets
 * {@code facing}/{@code half}/{@code hinge}/{@code axis} wrong, and the mistake is
 * invisible in the source text. VXB-2 therefore forbids the model from writing
 * states at all and reconstructs them here from the surrounding shape, which is
 * information the drawing already contains.
 *
 * <p>Everything in this class is a pure function of the glyph grid, so the same
 * drawing always compiles to the same states, and a rotated part re-derives its
 * states rather than transforming them.
 */
public final class Vxb2Inference {

    public record Result(List<BlockEntry> blocks, List<PlacementGroup> groups,
                         Set<Cell> clearCells, List<String> notes) {}

    private static final String[] HORIZONTAL = {"north", "east", "south", "west"};

    private Vxb2Inference() {}

    public static Result infer(Vxb2Parser.Parsed parsed) {
        List<String> notes = new ArrayList<>(parsed.notes());
        Map<Cell, String> base = new LinkedHashMap<>();
        Map<Cell, Map<String, String>> cellHints = new HashMap<>();
        Set<Cell> clearCells = new HashSet<>();

        for (Map.Entry<Cell, Character> entry : sorted(parsed.grid())) {
            if (entry.getValue() == '.') {
                clearCells.add(entry.getKey());
                continue;
            }
            String blockId = parsed.palette().get(entry.getValue());
            if (blockId == null) throw new IllegalArgumentException("Palette symbol '" + entry.getValue() + "' is undefined.");
            if (path(blockId).equals("air")) {
                clearCells.add(entry.getKey());
                continue;
            }
            base.put(entry.getKey(), blockId);
            Map<String, String> hint = parsed.hints().get(entry.getValue());
            if (hint != null && !hint.isEmpty()) cellHints.put(entry.getKey(), hint);
        }

        Grid grid = new Grid(base, parsed.sizeX(), parsed.sizeY(), parsed.sizeZ());
        Set<Cell> exterior = floodExterior(grid);

        Map<Cell, String> resolved = new LinkedHashMap<>();
        List<PlacementGroup> groups = new ArrayList<>();
        Set<Cell> consumed = new HashSet<>();
        int groupIndex = 0;

        groupIndex = resolveDoors(grid, exterior, cellHints, resolved, groups, consumed, notes, groupIndex);
        groupIndex = resolveBeds(grid, resolved, groups, consumed, notes, groupIndex);

        Map<Cell, String> stairFacing = new HashMap<>();
        Map<Cell, String> stairHalf = new HashMap<>();
        for (Cell cell : grid.cells()) {
            if (consumed.contains(cell) || !isStairs(grid.get(cell)) || pinned(grid.get(cell))) continue;
            Map<String, String> hint = cellHints.getOrDefault(cell, Map.of());
            stairFacing.put(cell, hint.containsKey("up") ? direction(hint.get("up"), cell, notes) : inferAscent(grid, cell, notes));
            stairHalf.put(cell, half(hint, inferHalf(grid, cell)));
        }

        List<PlacementGroup> fixtures = new ArrayList<>();
        for (Cell cell : grid.cells()) {
            if (consumed.contains(cell)) continue;
            String blockId = grid.get(cell);
            if (pinned(blockId)) {
                resolved.put(cell, blockId);
                continue;
            }
            String path = path(blockId);
            Map<String, String> hint = cellHints.getOrDefault(cell, Map.of());
            if (isStairs(blockId)) {
                String facing = stairFacing.get(cell);
                String half = stairHalf.get(cell);
                resolved.put(cell, state(blockId, props("facing", facing, "half", half,
                        "shape", stairShape(grid, cell, facing, half, stairFacing, stairHalf))));
            } else if (isAxisBlock(path)) {
                resolved.put(cell, state(blockId, props("axis", inferAxis(grid, cell))));
            } else if (path.endsWith("_slab")) {
                resolved.put(cell, state(blockId, props("type", inferHalf(grid, cell).equals("top") ? "top" : "bottom")));
            } else if (path.endsWith("_trapdoor")) {
                String support = adjacentSolid(grid, cell);
                resolved.put(cell, state(blockId, props("facing", support == null ? "north" : opposite(support),
                        "half", inferHalf(grid, cell))));
            } else if (isTorch(path)) {
                String support = grid.isSolid(below(cell)) ? null : adjacentSolid(grid, cell);
                if (support == null) {
                    resolved.put(cell, state(blockId, props()));
                    fixtures.add(fixture(++groupIndex, cell, resolved.get(cell), below(cell)));
                } else {
                    String wallBlock = wallVariant(blockId);
                    resolved.put(cell, state(wallBlock, props("facing", opposite(support))));
                    fixtures.add(fixture(++groupIndex, cell, resolved.get(cell), step(cell, support)));
                    if (!wallBlock.equals(blockId)) {
                        notes.add("Torch at " + format(cell) + " was placed as a wall torch against the "
                                + support + " side.");
                    }
                }
            } else if (path.equals("lantern") || path.equals("soul_lantern")) {
                boolean hanging = !grid.isSolid(below(cell)) && grid.isSolid(above(cell));
                resolved.put(cell, state(blockId, props("hanging", String.valueOf(hanging))));
                fixtures.add(fixture(++groupIndex, cell, resolved.get(cell), hanging ? above(cell) : below(cell)));
            } else if (path.equals("ladder")) {
                String support = adjacentSolid(grid, cell);
                if (support == null) {
                    resolved.put(cell, state(blockId, props("facing", "north")));
                    notes.add("Ladder at " + format(cell) + " has no wall beside it; defaulted to facing north.");
                } else {
                    resolved.put(cell, state(blockId, props("facing", opposite(support))));
                    fixtures.add(fixture(++groupIndex, cell, resolved.get(cell), step(cell, support)));
                }
            } else if (FURNITURE.contains(path)) {
                resolved.put(cell, state(blockId, props("facing", hint.containsKey("facing")
                        ? direction(hint.get("facing"), cell, notes) : outwardFacing(grid, exterior, cell))));
            } else {
                resolved.put(cell, state(blockId, props()));
            }
        }
        groups.addAll(fixtures);

        List<BlockEntry> blocks = new ArrayList<>();
        for (Map.Entry<Cell, String> entry : resolved.entrySet()) {
            Cell cell = entry.getKey();
            blocks.add(new BlockEntry(cell.x(), cell.y(), cell.z(), entry.getValue()));
        }
        for (PlacementGroup group : groups) {
            for (BlockEntry block : group.blocks()) {
                if (!resolved.containsKey(new Cell(block.x(), block.y(), block.z()))) blocks.add(block);
            }
        }
        blocks.sort(Comparator.comparingInt(BlockEntry::y).thenComparingInt(BlockEntry::z).thenComparingInt(BlockEntry::x));
        clearCells.removeIf(cell -> resolved.containsKey(cell));
        return new Result(blocks, List.copyOf(groups), clearCells, notes);
    }

    // --- multi-cell fixtures --------------------------------------------------

    private static int resolveDoors(Grid grid, Set<Cell> exterior, Map<Cell, Map<String, String>> cellHints,
                                    Map<Cell, String> resolved, List<PlacementGroup> groups, Set<Cell> consumed,
                                    List<String> notes, int groupIndex) {
        List<Cell> doorCells = new ArrayList<>();
        for (Cell cell : grid.cells()) {
            if (isDoor(grid.get(cell)) && !pinned(grid.get(cell))) doorCells.add(cell);
        }
        Set<Cell> handled = new HashSet<>();
        for (Cell cell : doorCells) {
            if (handled.contains(cell)) continue;
            Cell lower = cell;
            Cell under = below(cell);
            while (doorCells.contains(under) && sameBlock(grid, under, cell) && !handled.contains(under)) {
                lower = under;
                under = below(lower);
            }
            Cell upper = above(lower);
            boolean drawnUpper = doorCells.contains(upper) && sameBlock(grid, upper, lower);
            if (!drawnUpper) {
                if (grid.get(upper) != null) {
                    notes.add("Door at " + format(lower) + " is only one block tall and the cell above is occupied; "
                            + "drew the upper half anyway.");
                }
                notes.add("Door at " + format(lower) + " was drawn one block tall; its upper half was added.");
            }
            handled.add(lower);
            handled.add(upper);
            consumed.add(lower);
            consumed.add(upper);

            String blockId = grid.get(lower);
            Map<String, String> hint = cellHints.getOrDefault(lower, Map.of());
            String pinnedOutside = hint.containsKey("outside") ? hint.get("outside") : hint.get("facing");
            String outside = pinnedOutside != null ? direction(pinnedOutside, lower, notes)
                    : doorOutside(grid, exterior, lower);
            String hinge = doorHinge(grid, lower, outside);
            String lowerState = state(blockId, props("half", "lower", "facing", outside, "hinge", hinge,
                    "open", "false", "powered", "false"));
            String upperState = state(blockId, props("half", "upper", "facing", outside, "hinge", hinge,
                    "open", "false", "powered", "false"));
            resolved.put(lower, lowerState);
            resolved.put(upper, upperState);
            groups.add(new PlacementGroup("d" + (++groupIndex), "door",
                    List.of(new BlockEntry(lower.x(), lower.y(), lower.z(), lowerState),
                            new BlockEntry(upper.x(), upper.y(), upper.z(), upperState)),
                    List.of(below(lower)), true));
        }
        return groupIndex;
    }

    private static int resolveBeds(Grid grid, Map<Cell, String> resolved, List<PlacementGroup> groups,
                                   Set<Cell> consumed, List<String> notes, int groupIndex) {
        Set<Cell> handled = new HashSet<>();
        for (Cell cell : grid.cells()) {
            if (consumed.contains(cell) || handled.contains(cell)) continue;
            String blockId = grid.get(cell);
            if (!path(blockId).endsWith("_bed") || pinned(blockId)) continue;
            Cell partner = null;
            for (String direction : HORIZONTAL) {
                Cell candidate = step(cell, direction);
                if (!handled.contains(candidate) && sameBlock(grid, candidate, cell)) {
                    partner = candidate;
                    break;
                }
            }
            if (partner == null) {
                notes.add("Bed at " + format(cell) + " is only one block long; a bed needs two adjacent cells. "
                        + "It was placed as a plain block.");
                resolved.put(cell, state(blockId, props()));
                handled.add(cell);
                continue;
            }
            // The head goes against whichever end is backed by a wall, so the bed reads as furniture.
            Cell foot = cell;
            Cell head = partner;
            if (!backedByWall(grid, head, direction(foot, head)) && backedByWall(grid, foot, direction(head, foot))) {
                foot = partner;
                head = cell;
            }
            String facing = direction(foot, head);
            String footState = state(blockId, props("part", "foot", "facing", facing, "occupied", "false"));
            String headState = state(blockId, props("part", "head", "facing", facing, "occupied", "false"));
            resolved.put(foot, footState);
            resolved.put(head, headState);
            handled.add(foot);
            handled.add(head);
            consumed.add(foot);
            consumed.add(head);
            groups.add(new PlacementGroup("b" + (++groupIndex), "bed",
                    List.of(new BlockEntry(foot.x(), foot.y(), foot.z(), footState),
                            new BlockEntry(head.x(), head.y(), head.z(), headState)),
                    List.of(below(foot), below(head)), true));
        }
        return groupIndex;
    }

    private static PlacementGroup fixture(int index, Cell cell, String blockState, Cell support) {
        return new PlacementGroup("x" + index, "fixture",
                List.of(new BlockEntry(cell.x(), cell.y(), cell.z(), blockState)), List.of(support), false);
    }

    // --- directional reasoning -----------------------------------------------

    /**
     * Picks the ascent direction of a stair from the mass around it: stairs lean
     * into whatever they climb toward, whether that is the next tread of a
     * staircase or the next course of a roof slope.
     */
    private static String inferAscent(Grid grid, Cell cell, List<String> notes) {
        // A run of stairs — a staircase or a roof slope — steps diagonally, so the
        // neighbouring treads name the ascent unambiguously. A wall beside a stair
        // never produces that diagonal, which is what keeps a step from turning to
        // face the wall it stands against.
        String best = null;
        int bestRun = 0;
        for (String direction : HORIZONTAL) {
            int run = 0;
            if (isStairs(grid.get(above(step(cell, direction))))) run++;
            if (isStairs(grid.get(below(step(cell, opposite(direction)))))) run++;
            if (run > bestRun) {
                bestRun = run;
                best = direction;
            }
        }
        if (best != null) return best;

        // A lone stair has no run to follow, so fall back to the surrounding mass:
        // it leans into whatever is solid ahead or rising ahead of it.
        int bestScore = 0;
        for (String direction : HORIZONTAL) {
            Cell forward = step(cell, direction);
            Cell backward = step(cell, opposite(direction));
            int score = 0;
            if (grid.isSolid(forward)) score += 3;
            if (grid.isSolid(above(forward))) score += 3;
            if (!grid.isSolid(backward)) score += 2;
            if (!grid.isSolid(above(backward))) score += 2;
            if (grid.isSolid(below(backward))) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = direction;
            }
        }
        if (best == null) {
            notes.add("The stair at " + format(cell) + " has nothing around it to slope toward, so it faces north. "
                    + "Give its palette symbol an 'up=' hint if that is wrong.");
            return "north";
        }
        return best;
    }

    private static String half(Map<String, String> hint, String inferred) {
        String value = hint.containsKey("top") ? "top" : hint.get("half");
        if (value == null) return inferred;
        return value.equals("top") || value.equals("true") ? "top" : "bottom";
    }

    private static String direction(String value, Cell cell, List<String> notes) {
        for (String candidate : HORIZONTAL) {
            if (candidate.equals(value)) return candidate;
        }
        notes.add("'" + value + "' near " + format(cell) + " is not a compass direction; used north instead.");
        return "north";
    }

    /** Upside-down placement: nothing underneath to sit on, but a ceiling to hang from. */
    private static String inferHalf(Grid grid, Cell cell) {
        return !grid.isSolid(below(cell)) && grid.isSolid(above(cell)) ? "top" : "bottom";
    }

    private static String inferAxis(Grid grid, Cell cell) {
        int vertical = run(grid, cell, 0, 1, 0);
        int east = run(grid, cell, 1, 0, 0);
        int south = run(grid, cell, 0, 0, 1);
        if (vertical >= east && vertical >= south) return "y";
        return east >= south ? "x" : "z";
    }

    private static int run(Grid grid, Cell cell, int dx, int dy, int dz) {
        int length = 1;
        for (int sign : new int[]{1, -1}) {
            Cell probe = new Cell(cell.x() + dx * sign, cell.y() + dy * sign, cell.z() + dz * sign);
            while (sameBlock(grid, probe, cell)) {
                length++;
                probe = new Cell(probe.x() + dx * sign, probe.y() + dy * sign, probe.z() + dz * sign);
            }
        }
        return length;
    }

    /** Mirrors Minecraft's own corner-stair rule so drawn roofs and terraces mitre correctly. */
    private static String stairShape(Grid grid, Cell cell, String facing, String half,
                                     Map<Cell, String> facings, Map<Cell, String> halves) {
        Cell behind = step(cell, facing);
        String behindFacing = facings.get(behind);
        if (behindFacing != null && half.equals(halves.get(behind)) && !sameAxis(behindFacing, facing)
                && canTakeShape(step(cell, opposite(behindFacing)), facing, half, facings, halves)) {
            return behindFacing.equals(counterClockwise(facing)) ? "outer_left" : "outer_right";
        }
        Cell front = step(cell, opposite(facing));
        String frontFacing = facings.get(front);
        if (frontFacing != null && half.equals(halves.get(front)) && !sameAxis(frontFacing, facing)
                && canTakeShape(step(cell, frontFacing), facing, half, facings, halves)) {
            return frontFacing.equals(counterClockwise(facing)) ? "inner_left" : "inner_right";
        }
        return "straight";
    }

    private static boolean canTakeShape(Cell neighbour, String facing, String half,
                                        Map<Cell, String> facings, Map<Cell, String> halves) {
        String neighbourFacing = facings.get(neighbour);
        return neighbourFacing == null || !neighbourFacing.equals(facing) || !half.equals(halves.get(neighbour));
    }

    /**
     * A door's {@code facing} is its outward side. Rather than trusting the model
     * to say which side is outdoors, flood the air around the build: the side that
     * reaches open sky is the exterior.
     */
    private static String doorOutside(Grid grid, Set<Cell> exterior, Cell lower) {
        List<String> open = new ArrayList<>();
        for (String direction : HORIZONTAL) {
            if (!grid.isSolid(step(lower, direction))) open.add(direction);
        }
        for (String direction : open) {
            if (exterior.contains(step(lower, direction))) return direction;
        }
        return open.isEmpty() ? "north" : open.getFirst();
    }

    private static String doorHinge(Grid grid, Cell lower, String outside) {
        Cell left = step(lower, counterClockwise(outside));
        Cell right = step(lower, clockwise(outside));
        boolean doorLeft = isDoor(grid.get(left));
        boolean doorRight = isDoor(grid.get(right));
        if (doorLeft && !doorRight) return "right";
        if (doorRight && !doorLeft) return "left";
        return grid.isSolid(right) && !grid.isSolid(left) ? "right" : "left";
    }

    private static String outwardFacing(Grid grid, Set<Cell> exterior, Cell cell) {
        String fallback = null;
        for (String direction : HORIZONTAL) {
            Cell front = step(cell, direction);
            if (grid.isSolid(front)) continue;
            if (fallback == null) fallback = direction;
            if (!exterior.contains(front)) return direction; // face into the room, not out of the building
        }
        return fallback == null ? "north" : fallback;
    }

    private static boolean backedByWall(Grid grid, Cell cell, String direction) {
        return grid.isSolid(step(cell, direction));
    }

    private static String adjacentSolid(Grid grid, Cell cell) {
        for (String direction : HORIZONTAL) {
            if (grid.isSolid(step(cell, direction))) return direction;
        }
        return null;
    }

    private static Set<Cell> floodExterior(Grid grid) {
        Set<Cell> exterior = new HashSet<>();
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        Cell start = new Cell(-1, -1, -1);
        exterior.add(start);
        queue.add(start);
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            Cell current = queue.remove();
            for (int[] offset : steps) {
                Cell next = new Cell(current.x() + offset[0], current.y() + offset[1], current.z() + offset[2]);
                if (next.x() < -1 || next.y() < -1 || next.z() < -1
                        || next.x() > grid.sizeX() || next.y() > grid.sizeY() || next.z() > grid.sizeZ()) continue;
                // Any drawn block seals the flood, doors and panes included: an opening
                // that a player can walk through is still a wall as far as "which side is
                // outdoors" is concerned.
                if (grid.get(next) != null || !exterior.add(next)) continue;
                queue.add(next);
            }
        }
        return exterior;
    }

    // --- block families -------------------------------------------------------

    private static final Set<String> FURNITURE = Set.of("furnace", "blast_furnace", "smoker", "chest",
            "trapped_chest", "ender_chest", "barrel", "loom", "lectern", "stonecutter", "grindstone",
            "carved_pumpkin", "jack_o_lantern", "campfire", "soul_campfire", "beehive", "bee_nest",
            "observer", "dispenser", "dropper", "anvil", "chipped_anvil", "damaged_anvil");

    private static final Set<String> AXIS_BLOCKS = Set.of("bone_block", "hay_block", "basalt",
            "polished_basalt", "purpur_pillar", "quartz_pillar", "ochre_froglight", "verdant_froglight",
            "pearlescent_froglight", "deepslate", "muddy_mangrove_roots", "chain");

    private static boolean isAxisBlock(String path) {
        return AXIS_BLOCKS.contains(path) || path.endsWith("_log") || path.endsWith("_wood")
                || path.endsWith("_stem") || path.endsWith("_hyphae");
    }

    private static boolean isStairs(String blockId) {
        return blockId != null && path(blockId).endsWith("_stairs");
    }

    private static boolean isDoor(String blockId) {
        if (blockId == null) return false;
        String path = path(blockId);
        return path.endsWith("_door") && !path.endsWith("_trapdoor");
    }

    private static boolean isTorch(String path) {
        return path.equals("torch") || path.equals("soul_torch") || path.equals("redstone_torch");
    }

    private static String wallVariant(String blockId) {
        return switch (path(blockId)) {
            case "torch" -> "minecraft:wall_torch";
            case "soul_torch" -> "minecraft:soul_wall_torch";
            case "redstone_torch" -> "minecraft:redstone_wall_torch";
            default -> blockId;
        };
    }

    // --- state assembly -------------------------------------------------------

    private static LinkedHashMap<String, String> props(String... pairs) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i + 1] != null) map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * Applies inferred properties, dropping any the block does not actually have.
     * Filtering here keeps a compiler guess from turning into a bogus "invalid
     * block state" blocker that the model has no way to fix.
     */
    private static String state(String blockId, Map<String, String> properties) {
        String baseId = BlockIdResolver.normalizeBaseId(blockId);
        if (properties.isEmpty()) return baseId;
        Identifier id = Identifier.tryParse(baseId);
        Block block = id != null && BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.getValue(id) : null;
        StringBuilder out = new StringBuilder(baseId);
        boolean first = true;
        for (Map.Entry<String, String> property : properties.entrySet()) {
            if (block != null) {
                Property<?> definition = block.getStateDefinition().getProperty(property.getKey());
                if (definition == null || definition.getValue(property.getValue()).isEmpty()) continue;
            }
            out.append(first ? "[" : ",").append(property.getKey()).append('=').append(property.getValue());
            first = false;
        }
        if (!first) out.append(']');
        return out.toString();
    }

    private static boolean pinned(String blockId) {
        return blockId != null && blockId.indexOf('[') >= 0;
    }

    private static boolean sameBlock(Grid grid, Cell candidate, Cell reference) {
        String left = grid.get(candidate);
        return left != null && left.equals(grid.get(reference));
    }

    static String path(String blockId) {
        String value = BlockIdResolver.stripBlockState(blockId).trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    private static List<Map.Entry<Cell, Character>> sorted(Map<Cell, Character> grid) {
        List<Map.Entry<Cell, Character>> entries = new ArrayList<>(grid.entrySet());
        entries.sort(Comparator.<Map.Entry<Cell, Character>>comparingInt(e -> e.getKey().y())
                .thenComparingInt(e -> e.getKey().z())
                .thenComparingInt(e -> e.getKey().x()));
        return entries;
    }

    private static Cell above(Cell cell) { return new Cell(cell.x(), cell.y() + 1, cell.z()); }

    private static Cell below(Cell cell) { return new Cell(cell.x(), cell.y() - 1, cell.z()); }

    private static Cell step(Cell cell, String direction) {
        return switch (direction) {
            case "north" -> new Cell(cell.x(), cell.y(), cell.z() - 1);
            case "south" -> new Cell(cell.x(), cell.y(), cell.z() + 1);
            case "east" -> new Cell(cell.x() + 1, cell.y(), cell.z());
            default -> new Cell(cell.x() - 1, cell.y(), cell.z());
        };
    }

    private static String direction(Cell from, Cell to) {
        if (to.z() < from.z()) return "north";
        if (to.z() > from.z()) return "south";
        return to.x() > from.x() ? "east" : "west";
    }

    private static String opposite(String direction) {
        return switch (direction) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
    }

    private static String clockwise(String direction) {
        return switch (direction) {
            case "north" -> "east";
            case "east" -> "south";
            case "south" -> "west";
            default -> "north";
        };
    }

    private static String counterClockwise(String direction) {
        return clockwise(clockwise(clockwise(direction)));
    }

    private static boolean sameAxis(String left, String right) {
        boolean leftNorthSouth = left.equals("north") || left.equals("south");
        boolean rightNorthSouth = right.equals("north") || right.equals("south");
        return leftNorthSouth == rightNorthSouth;
    }

    private static String format(Cell cell) {
        return "(" + cell.x() + "," + cell.y() + "," + cell.z() + ")";
    }

    /** Read-only view of the drawn blocks with the solidity test used by every rule above. */
    private record Grid(Map<Cell, String> blocks, int sizeX, int sizeY, int sizeZ) {
        String get(Cell cell) { return blocks.get(cell); }

        List<Cell> cells() { return List.copyOf(blocks.keySet()); }

        boolean isSolid(Cell cell) {
            String blockId = blocks.get(cell);
            if (blockId == null) return false;
            String path = path(blockId);
            if (path.equals("air") || path.endsWith("_door") || path.equals("ladder") || path.equals("chain")
                    || path.endsWith("_torch") || path.equals("torch") || path.equals("lantern")
                    || path.equals("soul_lantern") || path.endsWith("_pane") || path.endsWith("_bed")
                    || path.endsWith("_carpet") || path.endsWith("_button") || path.endsWith("_sign")
                    || path.endsWith("_banner") || path.endsWith("_pressure_plate") || path.equals("iron_bars")
                    || path.endsWith("_sapling") || path.endsWith("_flower") || path.equals("water")
                    || path.equals("lava") || path.endsWith("_rail") || path.equals("rail")) {
                return false;
            }
            return true;
        }
    }
}
