package com.assistantbot.llm;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.BuildStructure.Cell;
import com.assistantbot.llm.BuildStructure.PlacementGroup;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

/** Mechanical final-grid checks that should never be delegated to an LLM. */
public final class VxbStructureValidator {
    private static final int[][] NEIGHBORS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    private VxbStructureValidator() {}

    public static void validate(BuildStructure structure, VxbDiagnostics.DiagnosticResult result) {
        Map<Cell, BlockEntry> grid = new HashMap<>();
        for (BlockEntry block : structure.getBlocks()) {
            grid.put(new Cell(block.x(), block.y(), block.z()), block);
            validateExactState(block, result);
        }
        validateFixtureSupports(structure, grid, result);
        validateFeatureClearance(structure, grid, result);
        validateGroundedComponents(structure, grid, result);
        validateGravityBlocks(grid, result);
        validateWalkableAccess(structure, grid, result);
        validateIsolatedPanes(grid, result);
    }

    /** A single pane or bar floating in a wall reads as a mistake rather than a window. */
    private static void validateIsolatedPanes(Map<Cell, BlockEntry> grid, VxbDiagnostics.DiagnosticResult result) {
        for (Map.Entry<Cell, BlockEntry> entry : grid.entrySet()) {
            String id = entry.getValue().blockId().toLowerCase(Locale.ROOT);
            if (!id.contains("_pane") && !id.contains("iron_bars")) continue;
            boolean neighbour = false;
            for (int[] d : NEIGHBORS) {
                Cell next = new Cell(entry.getKey().x() + d[0], entry.getKey().y() + d[1], entry.getKey().z() + d[2]);
                BlockEntry other = grid.get(next);
                if (other != null && other.blockId().equals(entry.getValue().blockId())) neighbour = true;
            }
            if (!neighbour) {
                result.add(VxbDiagnostics.Severity.WARNING, "Isolated Window Pane",
                        "The pane at " + format(entry.getKey()) + " has no matching pane beside it; single panes "
                                + "read as a gap rather than a window.", null);
            }
        }
    }

    private static Cell firstCell(PlacementGroup group) {
        BlockEntry block = group.blocks().getFirst();
        return new Cell(block.x(), block.y(), block.z());
    }

    private static void validateExactState(BlockEntry entry, VxbDiagnostics.DiagnosticResult result) {
        Identifier id = Identifier.tryParse(BlockIdResolver.normalizeBaseId(entry.blockId()));
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return;
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        try {
            BlockStateResolver.resolve(block, entry.blockId());
        } catch (IllegalArgumentException e) {
            result.add(VxbDiagnostics.Severity.BLOCKER, "Invalid Minecraft Block State",
                    "At (" + entry.x() + "," + entry.y() + "," + entry.z() + "): " + e.getMessage(), null);
        }
    }

    private static void validateFixtureSupports(BuildStructure structure, Map<Cell, BlockEntry> grid,
                                                VxbDiagnostics.DiagnosticResult result) {
        for (PlacementGroup group : structure.getFeatureGroups()) {
            for (Cell support : group.requiredSupports()) {
                BlockEntry supportingBlock = grid.get(support);
                if (supportingBlock != null && isLikelySolid(supportingBlock.blockId())) continue;
                // Under 'terrain keep' the missing support may simply be untouched world terrain,
                // which the plan is allowed to lean on, so that case is advisory rather than fatal.
                VxbDiagnostics.Severity severity = structure.shouldPreserveTerrain()
                        ? VxbDiagnostics.Severity.WARNING : VxbDiagnostics.Severity.BLOCKER;
                Integer sourceLine = structure.getSourceLine(support);
                if (sourceLine == null) sourceLine = structure.getSourceLine(firstCell(group));
                result.add(severity, "Missing Fixture Support",
                        "The " + group.kind() + " at " + format(firstCell(group)) + " needs a solid block at "
                                + format(support) + ". Draw one there, or move the fixture against a wall.",
                        sourceLine);
            }
        }
    }

    private static void validateGroundedComponents(BuildStructure structure, Map<Cell, BlockEntry> grid,
                                                   VxbDiagnostics.DiagnosticResult result) {
        if (structure.isFloatingAllowed() || grid.isEmpty()) return;
        Set<Cell> reached = new HashSet<>();
        Queue<Cell> queue = new ArrayDeque<>();
        for (Cell cell : grid.keySet()) {
            if (cell.y() == 0) {
                reached.add(cell);
                queue.add(cell);
            }
        }
        while (!queue.isEmpty()) {
            Cell current = queue.remove();
            for (int[] d : NEIGHBORS) {
                Cell next = new Cell(current.x() + d[0], current.y() + d[1], current.z() + d[2]);
                if (grid.containsKey(next) && reached.add(next)) queue.add(next);
            }
        }
        if (reached.size() == grid.size()) return;

        Set<Cell> remaining = new HashSet<>(grid.keySet());
        remaining.removeAll(reached);
        int components = 0;
        Cell example = null;
        while (!remaining.isEmpty()) {
            components++;
            Cell start = remaining.iterator().next();
            if (example == null) example = start;
            queue.add(start);
            remaining.remove(start);
            while (!queue.isEmpty()) {
                Cell current = queue.remove();
                for (int[] d : NEIGHBORS) {
                    Cell next = new Cell(current.x() + d[0], current.y() + d[1], current.z() + d[2]);
                    if (remaining.remove(next)) queue.add(next);
                }
            }
        }
        result.add(VxbDiagnostics.Severity.BLOCKER, "Ungrounded Structure Component",
                (grid.size() - reached.size()) + " blocks in " + components + " component(s) are not connected to y=0; example "
                        + format(example) + ". Draw something connecting it down, or set 'ground false' if it is meant to float.",
                structure.getSourceLine(example));
    }

    private static void validateFeatureClearance(BuildStructure structure, Map<Cell, BlockEntry> grid,
                                                 VxbDiagnostics.DiagnosticResult result) {
        for (PlacementGroup group : structure.getFeatureGroups()) {
            if (group.kind().equals("door")) {
                BlockEntry lower = group.blocks().stream().min(java.util.Comparator.comparingInt(BlockEntry::y)).orElseThrow();
                String facing = stateProperty(lower.blockId(), "facing");
                int[] outside = horizontalDelta(facing);
                for (int sign : new int[]{-1, 1}) {
                    for (int dy = 0; dy <= 1; dy++) {
                        Cell clearance = new Cell(lower.x() + outside[0] * sign, lower.y() + dy,
                                lower.z() + outside[1] * sign);
                        if (inside(structure, clearance) && grid.containsKey(clearance)) {
                            result.add(VxbDiagnostics.Severity.BLOCKER, "Blocked Doorway",
                                    "The door at " + format(firstCell(group)) + " is blocked by a block at "
                                            + format(clearance) + "; a doorway needs two blocks of headroom on both sides.",
                                    structure.getSourceLine(clearance));
                        }
                    }
                }
            }
        }
    }


    private static void validateGravityBlocks(Map<Cell, BlockEntry> grid, VxbDiagnostics.DiagnosticResult result) {
        for (Map.Entry<Cell, BlockEntry> entry : grid.entrySet()) {
            String path = BlockIdResolver.stripBlockState(entry.getValue().blockId()).toLowerCase(Locale.ROOT);
            if (path.contains(":")) path = path.substring(path.indexOf(':') + 1);
            boolean gravity = path.endsWith("sand") || path.endsWith("gravel") || path.endsWith("concrete_powder")
                    || path.endsWith("anvil") || path.equals("dragon_egg");
            if (gravity && !grid.containsKey(new Cell(entry.getKey().x(), entry.getKey().y() - 1, entry.getKey().z()))) {
                result.add(VxbDiagnostics.Severity.BLOCKER, "Unsupported Gravity Block",
                        entry.getValue().blockId() + " at " + format(entry.getKey()) + " has no block below it.", null);
            }
        }
    }

    /** Advisory flood fill over two-block-tall walkable spaces; doors and stairs count as passable. */
    private static void validateWalkableAccess(BuildStructure structure, Map<Cell, BlockEntry> grid,
                                               VxbDiagnostics.DiagnosticResult result) {
        int sx = structure.getSizeX(), sy = structure.getSizeY(), sz = structure.getSizeZ();
        if (sx < 1 || sy < 3 || sz < 1 || sx > 50 || sy > 50 || sz > 50) return;
        Set<Cell> walkable = new HashSet<>();
        for (int y = 1; y < sy - 1; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    Cell feet = new Cell(x, y, z);
                    Cell head = new Cell(x, y + 1, z);
                    BlockEntry feetBlock = grid.get(feet);
                    BlockEntry headBlock = grid.get(head);
                    boolean passableBody = isPlayerPassable(feetBlock) && isPlayerPassable(headBlock);
                    BlockEntry below = grid.get(new Cell(x, y - 1, z));
                    boolean footing = below != null && isLikelySolid(below.blockId());
                    if (feetBlock != null && (feetBlock.blockId().contains("stairs") || feetBlock.blockId().contains("ladder"))) footing = true;
                    if (passableBody && footing) walkable.add(feet);
                }
            }
        }
        if (walkable.isEmpty()) return;

        Set<Cell> reached = new HashSet<>();
        Queue<Cell> queue = new ArrayDeque<>();
        for (Cell cell : walkable) {
            if (cell.x() == 0 || cell.x() == sx - 1 || cell.z() == 0 || cell.z() == sz - 1) {
                reached.add(cell);
                queue.add(cell);
            }
        }
        int[][] horizontal = {{1,0},{-1,0},{0,1},{0,-1}};
        while (!queue.isEmpty()) {
            Cell current = queue.remove();
            for (int[] d : horizontal) {
                for (int dy = -1; dy <= 1; dy++) {
                    Cell next = new Cell(current.x() + d[0], current.y() + dy, current.z() + d[1]);
                    if (walkable.contains(next) && reached.add(next)) queue.add(next);
                }
            }
        }
        int inaccessible = walkable.size() - reached.size();
        if (inaccessible > 0) {
            result.add(VxbDiagnostics.Severity.WARNING, "Potentially Inaccessible Interior",
                    inaccessible + " walkable cell(s) are not connected to a boundary entrance in the simplified player-space check.", null);
        }
    }

    private static boolean isPlayerPassable(BlockEntry block) {
        if (block == null) return true;
        String id = block.blockId().toLowerCase(Locale.ROOT);
        return id.contains("door") || id.contains("ladder") || id.contains("stairs")
                || id.contains("trapdoor") || id.contains("carpet") || id.contains("torch");
    }

    private static boolean inside(BuildStructure structure, Cell cell) {
        return cell.x() >= 0 && cell.x() < structure.getSizeX()
                && cell.y() >= 0 && cell.y() < structure.getSizeY()
                && cell.z() >= 0 && cell.z() < structure.getSizeZ();
    }

    private static String stateProperty(String blockId, String property) {
        int start = blockId.indexOf('['), end = blockId.lastIndexOf(']');
        if (start < 0 || end <= start) return "north";
        for (String assignment : blockId.substring(start + 1, end).split(",")) {
            String[] p = assignment.trim().split("=", 2);
            if (p.length == 2 && p[0].equals(property)) return p[1];
        }
        return "north";
    }

    private static int[] horizontalDelta(String facing) {
        return switch (facing) {
            case "south" -> new int[]{0, 1};
            case "east" -> new int[]{1, 0};
            case "west" -> new int[]{-1, 0};
            default -> new int[]{0, -1};
        };
    }

    private static boolean isLikelySolid(String blockId) {
        String id = blockId.toLowerCase(Locale.ROOT);
        return !id.contains("air") && !id.contains("torch") && !id.contains("ladder")
                && !id.contains("door") && !id.contains("lantern") && !id.contains("chain")
                && !id.contains("glass_pane") && !id.contains("flower") && !id.contains("grass")
                && !id.contains("carpet") && !id.contains("water") && !id.contains("lava");
    }

    private static String format(Cell cell) {
        return "(" + cell.x() + "," + cell.y() + "," + cell.z() + ")";
    }
}
