package com.assistantbot.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Expands compact VXB-1.1 geometry primitives into ordinary set commands. */
final class VxbPrimitiveCompiler {
    @FunctionalInterface
    interface StateSymbolResolver {
        char resolve(String blockState);
    }

    private static final Set<String> PRIMITIVES = Set.of(
            "sphere", "ellipsoid", "cylinder", "cone", "pyramid", "triangle", "staircase");

    private VxbPrimitiveCompiler() {}

    static boolean isPrimitive(String line) {
        int space = line.indexOf(' ');
        String command = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        return PRIMITIVES.contains(command);
    }

    static List<String> expand(String line, Map<Character, String> palette,
                               StateSymbolResolver stateSymbols) {
        String[] p = line.split("\\s+");
        String kind = p[0].toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "sphere" -> sphere(p, line, palette);
            case "ellipsoid" -> ellipsoid(p, line, palette);
            case "cylinder" -> cylinder(p, line, palette);
            case "cone" -> cone(p, line, palette);
            case "pyramid" -> pyramid(p, line, palette);
            case "triangle" -> triangle(p, line, palette);
            case "staircase" -> staircase(p, line, palette, stateSymbols);
            default -> throw new IllegalArgumentException("Unknown geometry primitive '" + kind + "'");
        };
    }

    private static List<String> sphere(String[] p, String line, Map<Character, String> palette) {
        requireLength(p, 6, line);
        int cx = integer(p[1], "sphere center X");
        int cy = integer(p[2], "sphere center Y");
        int cz = integer(p[3], "sphere center Z");
        int radius = positive(p[4], "sphere radius");
        char symbol = paletteSymbol(p[5], palette, line);
        boolean hollow = bool(options(p, 6), "hollow", false, line);
        return emit(ellipsoidCells(cx, cy, cz, radius, radius, radius, hollow), symbol);
    }

    private static List<String> ellipsoid(String[] p, String line, Map<Character, String> palette) {
        requireLength(p, 8, line);
        int cx = integer(p[1], "ellipsoid center X");
        int cy = integer(p[2], "ellipsoid center Y");
        int cz = integer(p[3], "ellipsoid center Z");
        int rx = positive(p[4], "ellipsoid X radius");
        int ry = positive(p[5], "ellipsoid Y radius");
        int rz = positive(p[6], "ellipsoid Z radius");
        char symbol = paletteSymbol(p[7], palette, line);
        boolean hollow = bool(options(p, 8), "hollow", false, line);
        return emit(ellipsoidCells(cx, cy, cz, rx, ry, rz, hollow), symbol);
    }

    private static Set<Pos> ellipsoidCells(int cx, int cy, int cz, int rx, int ry, int rz,
                                           boolean hollow) {
        Set<Pos> filled = new LinkedHashSet<>();
        for (int y = cy - ry; y <= cy + ry; y++) {
            for (int z = cz - rz; z <= cz + rz; z++) {
                for (int x = cx - rx; x <= cx + rx; x++) {
                    if (insideEllipsoid(x - cx, y - cy, z - cz, rx, ry, rz)) {
                        filled.add(new Pos(x, y, z));
                    }
                }
            }
        }
        return hollow ? boundary(filled) : filled;
    }

    private static boolean insideEllipsoid(int x, int y, int z, int rx, int ry, int rz) {
        double distance = square((double) x / rx) + square((double) y / ry) + square((double) z / rz);
        return distance <= 1.0 + 1.0e-9;
    }

    private static List<String> cylinder(String[] p, String line, Map<Character, String> palette) {
        requireLength(p, 7, line);
        int cx = integer(p[1], "cylinder center X");
        int y0 = integer(p[2], "cylinder base Y");
        int cz = integer(p[3], "cylinder center Z");
        int radius = positive(p[4], "cylinder radius");
        int height = positive(p[5], "cylinder height");
        char symbol = paletteSymbol(p[6], palette, line);
        Map<String, String> options = options(p, 7);
        boolean hollow = bool(options, "hollow", false, line);
        boolean caps = bool(options, "caps", true, line);

        Set<Pos> cells = new LinkedHashSet<>();
        for (int y = y0; y < y0 + height; y++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int x = cx - radius; x <= cx + radius; x++) {
                    if (!insideCircle(x - cx, z - cz, radius)) continue;
                    boolean side = !insideCircle(x - cx + 1, z - cz, radius)
                            || !insideCircle(x - cx - 1, z - cz, radius)
                            || !insideCircle(x - cx, z - cz + 1, radius)
                            || !insideCircle(x - cx, z - cz - 1, radius);
                    boolean cap = caps && (y == y0 || y == y0 + height - 1);
                    if (!hollow || side || cap) cells.add(new Pos(x, y, z));
                }
            }
        }
        return emit(cells, symbol);
    }

    private static List<String> cone(String[] p, String line, Map<Character, String> palette) {
        requireLength(p, 7, line);
        int cx = integer(p[1], "cone center X");
        int y0 = integer(p[2], "cone base Y");
        int cz = integer(p[3], "cone center Z");
        int radius = positive(p[4], "cone radius");
        int height = positive(p[5], "cone height");
        char symbol = paletteSymbol(p[6], palette, line);
        Map<String, String> options = options(p, 7);
        boolean hollow = bool(options, "hollow", false, line);
        boolean cap = bool(options, "cap", true, line);

        Set<Pos> cells = new LinkedHashSet<>();
        for (int level = 0; level < height; level++) {
            double layerRadius = height == 1 ? radius : radius * (height - 1.0 - level) / (height - 1.0);
            int extent = (int) Math.ceil(layerRadius);
            for (int z = cz - extent; z <= cz + extent; z++) {
                for (int x = cx - extent; x <= cx + extent; x++) {
                    if (!insideCircle(x - cx, z - cz, layerRadius)) continue;
                    boolean side = !insideCircle(x - cx + 1, z - cz, layerRadius)
                            || !insideCircle(x - cx - 1, z - cz, layerRadius)
                            || !insideCircle(x - cx, z - cz + 1, layerRadius)
                            || !insideCircle(x - cx, z - cz - 1, layerRadius);
                    if (!hollow || side || (cap && level == 0)) cells.add(new Pos(x, y0 + level, z));
                }
            }
        }
        return emit(cells, symbol);
    }

    private static List<String> pyramid(String[] p, String line, Map<Character, String> palette) {
        requireLength(p, 7, line);
        int cx = integer(p[1], "pyramid center X");
        int y0 = integer(p[2], "pyramid base Y");
        int cz = integer(p[3], "pyramid center Z");
        int radius = positive(p[4], "pyramid radius");
        int height = positive(p[5], "pyramid height");
        char symbol = paletteSymbol(p[6], palette, line);
        Map<String, String> options = options(p, 7);
        boolean hollow = bool(options, "hollow", false, line);
        boolean cap = bool(options, "cap", true, line);

        Set<Pos> cells = new LinkedHashSet<>();
        for (int level = 0; level < height; level++) {
            int layerRadius = height == 1 ? radius
                    : (int) Math.ceil(radius * (height - 1.0 - level) / (height - 1.0));
            for (int z = cz - layerRadius; z <= cz + layerRadius; z++) {
                for (int x = cx - layerRadius; x <= cx + layerRadius; x++) {
                    boolean side = x == cx - layerRadius || x == cx + layerRadius
                            || z == cz - layerRadius || z == cz + layerRadius;
                    if (!hollow || side || (cap && level == 0)) cells.add(new Pos(x, y0 + level, z));
                }
            }
        }
        return emit(cells, symbol);
    }

    private static List<String> triangle(String[] p, String line, Map<Character, String> palette) {
        requireLength(p, 8, line);
        int x0 = integer(p[1], "triangle minimum X");
        int y0 = integer(p[2], "triangle base Y");
        int z0 = integer(p[3], "triangle minimum Z");
        int base = positive(p[4], "triangle base");
        int height = positive(p[5], "triangle height");
        int depth = positive(p[6], "triangle depth");
        char symbol = paletteSymbol(p[7], palette, line);
        Map<String, String> options = options(p, 8);
        String axis = options.getOrDefault("axis", "x").toLowerCase(Locale.ROOT);
        if (!axis.equals("x") && !axis.equals("z")) {
            throw new IllegalArgumentException("triangle axis must be x or z: " + line);
        }
        boolean hollow = bool(options, "hollow", false, line);
        boolean caps = bool(options, "caps", true, line);

        Set<Pos> cells = new LinkedHashSet<>();
        for (int level = 0; level < height; level++) {
            for (int cross = 0; cross < base; cross++) {
                if (!insideTriangle(cross, level, base, height)) continue;
                boolean outline = !insideTriangle(cross - 1, level, base, height)
                        || !insideTriangle(cross + 1, level, base, height)
                        || !insideTriangle(cross, level - 1, base, height)
                        || !insideTriangle(cross, level + 1, base, height);
                for (int extrusion = 0; extrusion < depth; extrusion++) {
                    if (hollow && !outline && !(caps && (extrusion == 0 || extrusion == depth - 1))) continue;
                    int x = axis.equals("x") ? x0 + cross : x0 + extrusion;
                    int z = axis.equals("x") ? z0 + extrusion : z0 + cross;
                    cells.add(new Pos(x, y0 + level, z));
                }
            }
        }
        return emit(cells, symbol);
    }

    private static boolean insideTriangle(int cross, int level, int base, int height) {
        if (cross < 0 || cross >= base || level < 0 || level >= height) return false;
        double center = (base - 1) / 2.0;
        double halfWidth = height == 1 ? center
                : center * (height - 1.0 - level) / (height - 1.0);
        return Math.abs(cross - center) <= halfWidth + 0.5 + 1.0e-9;
    }

    private static List<String> staircase(String[] p, String line, Map<Character, String> palette,
                                          StateSymbolResolver stateSymbols) {
        requireLength(p, 7, line);
        int x0 = integer(p[1], "staircase X");
        int y0 = integer(p[2], "staircase Y");
        int z0 = integer(p[3], "staircase Z");
        int width = positive(p[4], "staircase width");
        int steps = positive(p[5], "staircase steps");
        String material = p[6].toLowerCase(Locale.ROOT);
        Map<String, String> options = options(p, 7);
        String up = direction(required(options, "up", line));
        String half = options.getOrDefault("half", "bottom").toLowerCase(Locale.ROOT);
        if (!half.equals("bottom") && !half.equals("top")) {
            throw new IllegalArgumentException("staircase half must be bottom or top: " + line);
        }
        String support = options.getOrDefault("support", "solid").toLowerCase(Locale.ROOT);
        if (!support.equals("solid") && !support.equals("none")) {
            throw new IllegalArgumentException("staircase support must be solid or none: " + line);
        }

        Character fillSymbol = null;
        if (support.equals("solid")) {
            fillSymbol = paletteSymbol(required(options, "fill", line), palette, line);
        }
        String stairId = material.contains(":") ? material : "minecraft:" + material;
        if (!stairId.endsWith("_stairs")) stairId += "_stairs";
        char stairSymbol = stateSymbols.resolve(stairId + "[facing=" + up + ",half=" + half
                + ",shape=straight,waterlogged=false]");

        int[] forward = directionDelta(up);
        int[] across = (forward[0] == 0) ? new int[]{1, 0} : new int[]{0, 1};
        List<String> result = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            for (int w = 0; w < width; w++) {
                int x = x0 + forward[0] * step + across[0] * w;
                int z = z0 + forward[1] * step + across[1] * w;
                if (fillSymbol != null) {
                    for (int y = y0; y < y0 + step; y++) result.add(set(x, y, z, fillSymbol));
                }
                result.add(set(x, y0 + step, z, stairSymbol));
            }
        }
        return result;
    }

    private static Set<Pos> boundary(Set<Pos> filled) {
        Set<Pos> shell = new LinkedHashSet<>();
        int[][] neighbors = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (Pos cell : filled) {
            for (int[] d : neighbors) {
                if (!filled.contains(new Pos(cell.x + d[0], cell.y + d[1], cell.z + d[2]))) {
                    shell.add(cell);
                    break;
                }
            }
        }
        return shell;
    }

    private static List<String> emit(Set<Pos> cells, char symbol) {
        List<String> result = new ArrayList<>(cells.size());
        for (Pos cell : cells) result.add(set(cell.x, cell.y, cell.z, symbol));
        return result;
    }

    private static String set(int x, int y, int z, char symbol) {
        return "set " + x + " " + y + " " + z + " " + symbol;
    }

    private static boolean insideCircle(int x, int z, double radius) {
        return x * (double) x + z * (double) z <= radius * radius + 1.0e-9;
    }

    private static double square(double value) {
        return value * value;
    }

    private static Map<String, String> options(String[] parts, int start) {
        Map<String, String> result = new HashMap<>();
        for (int i = start; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            if (equals < 1 || equals == parts[i].length() - 1) {
                throw new IllegalArgumentException("Expected key=value option, got '" + parts[i] + "'");
            }
            String key = parts[i].substring(0, equals).toLowerCase(Locale.ROOT);
            String prior = result.putIfAbsent(key, parts[i].substring(equals + 1));
            if (prior != null) throw new IllegalArgumentException("Duplicate option '" + key + "'");
        }
        return result;
    }

    private static boolean bool(Map<String, String> options, String key, boolean defaultValue, String line) {
        String value = options.get(key);
        if (value == null) return defaultValue;
        value = value.toLowerCase(Locale.ROOT);
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException(key + " must be true or false: " + line);
    }

    private static String required(Map<String, String> options, String key, String line) {
        String value = options.get(key);
        if (value == null) throw new IllegalArgumentException("Primitive requires " + key + "=...: " + line);
        return value;
    }

    private static char paletteSymbol(String token, Map<Character, String> palette, String line) {
        if (token.length() != 1 || !palette.containsKey(token.charAt(0))) {
            throw new IllegalArgumentException("Primitive uses undefined palette symbol '" + token + "': " + line);
        }
        return token.charAt(0);
    }

    private static int integer(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
    }

    private static int positive(String value, String label) {
        int parsed = integer(value, label);
        if (parsed < 1) throw new IllegalArgumentException(label + " must be at least 1");
        return parsed;
    }

    private static void requireLength(String[] parts, int length, String line) {
        if (parts.length < length) throw new IllegalArgumentException("Incomplete primitive: " + line);
    }

    private static String direction(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "north", "south", "east", "west" -> normalized;
            default -> throw new IllegalArgumentException("Direction must be north, south, east, or west: " + value);
        };
    }

    private static int[] directionDelta(String value) {
        return switch (direction(value)) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "east" -> new int[]{1, 0};
            default -> new int[]{-1, 0};
        };
    }

    private record Pos(int x, int y, int z) {}
}
