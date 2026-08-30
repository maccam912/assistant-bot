package com.assistantbot.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable, prompt-sized survey of the terrain around a proposed build center. */
public final class TerrainSnapshot {
    static final int RADIUS = 10;
    private static final int SCAN_BELOW = 6;
    private static final int SCAN_ABOVE = 24;
    private static final int[] OCCUPANCY_LEVELS = {0, 3, 6, 10, 16, 24};
    private static final String MATERIAL_SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private TerrainSnapshot() {}

    public static String capture(ServerLevel world, BlockPos center) {
        int diameter = RADIUS * 2 + 1;
        int[][] heights = new int[diameter][diameter];
        String[][] surface = new String[diameter][diameter];
        String[][] floor = new String[diameter][diameter];
        char[][][] occupancy = new char[OCCUPANCY_LEVELS.length][diameter][diameter];

        for (int row = 0; row < diameter; row++) {
            int dz = row - RADIUS;
            for (int col = 0; col < diameter; col++) {
                int dx = col - RADIUS;
                floor[row][col] = blockId(world.getBlockState(center.offset(dx, -1, dz)));
                int surfaceY = -SCAN_BELOW - 1;
                String surfaceId = "minecraft:air";
                for (int relativeY = SCAN_ABOVE; relativeY >= -SCAN_BELOW; relativeY--) {
                    BlockState state = world.getBlockState(center.offset(dx, relativeY, dz));
                    if (!state.isAir()) {
                        surfaceY = relativeY;
                        surfaceId = blockId(state);
                        break;
                    }
                }
                heights[row][col] = surfaceY;
                surface[row][col] = surfaceId;
                for (int slice = 0; slice < OCCUPANCY_LEVELS.length; slice++) {
                    BlockState state = world.getBlockState(center.offset(dx, OCCUPANCY_LEVELS[slice], dz));
                    occupancy[slice][row][col] = state.isAir() ? '.'
                            : state.getFluidState().isEmpty() ? '#' : '~';
                }
            }
        }
        return format(center, heights, surface, floor, occupancy);
    }

    static String format(BlockPos center, int[][] heights, String[][] surface, String[][] floor,
                         char[][][] occupancy) {
        Map<String, Character> materialSymbols = new LinkedHashMap<>();
        assignSymbols(surface, materialSymbols);
        assignSymbols(floor, materialSymbols);

        StringBuilder out = new StringBuilder();
        out.append("SITE TERRAIN SNAPSHOT\n")
                .append("Build marker: world ").append(center.toShortString())
                .append(". The engine horizontally centers the declared VXB size on this marker; local y=0 maps to marker y=")
                .append(center.getY()).append(".\n")
                .append("Rows run north-to-south (z offsets -").append(RADIUS).append("..").append(RADIUS)
                .append("); columns run west-to-east (x offsets -").append(RADIUS).append("..").append(RADIUS).append(").\n")
                .append("For a declared size, terrain offset dx = local x - floor(sizeX/2), and dz = local z - floor(sizeZ/2).\n")
                .append("Surface heights are relative to local y=0; ").append(-SCAN_BELOW - 1)
                .append(" means no non-air block was found in the sampled vertical range.\n")
                .append("surface_height_rows:\n");
        for (int[] row : heights) {
            for (int col = 0; col < row.length; col++) {
                if (col > 0) out.append(' ');
                out.append(row[col]);
            }
            out.append('\n');
        }

        out.append("material_legend:");
        for (Map.Entry<String, Character> entry : materialSymbols.entrySet()) {
            out.append(' ').append(entry.getValue()).append('=').append(entry.getKey());
        }
        out.append("\nsurface_material_rows:\n");
        appendMaterialGrid(out, surface, materialSymbols);
        out.append("floor_material_rows_at_y=-1:\n");
        appendMaterialGrid(out, floor, materialSymbols);
        out.append("occupancy_legend: .=air #=block ~=fluid\n");
        for (int slice = 0; slice < OCCUPANCY_LEVELS.length; slice++) {
            out.append("occupancy_at_y=").append(OCCUPANCY_LEVELS[slice]).append(":\n");
            for (char[] row : occupancy[slice]) out.append(row).append('\n');
        }
        out.append("Use this survey to choose foundations, stilts, terraces, retaining walls, entrances, and materials that fit the site. ")
                .append("Use terrain_mode preserve when terrain should remain inside unused parts of the build volume; explicitly write an air palette symbol where tunnels, rooms, or doorways must be carved. ")
                .append("Use terrain_mode replace when the whole rectangular build volume should be cleared. A '.' in a layer removes planned geometry but does not explicitly carve preserved world terrain.");
        return out.toString();
    }

    private static void assignSymbols(String[][] grid, Map<String, Character> symbols) {
        for (String[] row : grid) {
            for (String id : row) {
                if (!symbols.containsKey(id) && symbols.size() < MATERIAL_SYMBOLS.length()) {
                    symbols.put(id, MATERIAL_SYMBOLS.charAt(symbols.size()));
                }
            }
        }
    }

    private static void appendMaterialGrid(StringBuilder out, String[][] grid, Map<String, Character> symbols) {
        for (String[] row : grid) {
            for (String id : row) out.append(symbols.getOrDefault(id, '?'));
            out.append('\n');
        }
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }
}
