package com.assistantbot.llm;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.BuildStructure.Cell;
import com.assistantbot.llm.BuildStructure.PlacementGroup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Expands the small VXB-1.1 semantic feature layer into ordinary VXB-1 sets. */
public final class VxbFeatureCompiler {
    public record Expansion(String vxb, List<PlacementGroup> groups, boolean allowFloating) {}

    private static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!$%&*+/:;?@^_~";

    private VxbFeatureCompiler() {}

    public static Expansion expand(String source) {
        VxbMacroCompiler.Expansion macros = VxbMacroCompiler.expand(source);
        source = macros.vxb();
        String normalized = source.replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-');
        String[] lines = normalized.split("\\r?\\n", -1);
        boolean isV11 = false;
        boolean hasFeatures = false;
        boolean allowFloating = false;
        int sizeX = -1;
        int sizeZ = -1;
        Set<Character> usedSymbols = new HashSet<>();
        Map<String, Character> existingStates = new HashMap<>();
        Map<Character, String> paletteStates = new HashMap<>();

        boolean inPalette = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.equals("VXB-1.1")) isV11 = true;
            if (line.equals("features")) hasFeatures = true;
            if (line.equals("allow_floating true")) allowFloating = true;
            if (line.startsWith("size ")) {
                String[] p = line.split("\\s+");
                if (p.length == 4) {
                    sizeX = parseInt(p[1], "size X");
                    sizeZ = parseInt(p[3], "size Z");
                }
            }
            if (line.equals("palette")) {
                inPalette = true;
            } else if (line.equals("endpalette")) {
                inPalette = false;
            } else if (inPalette) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String state = line.substring(eq + 1).trim();
                    if (key.length() == 1) {
                        usedSymbols.add(key.charAt(0));
                        existingStates.put(normalizeState(state), key.charAt(0));
                        paletteStates.put(key.charAt(0), normalizeState(state));
                    }
                }
            }
        }

        if (!isV11 && !hasFeatures && normalized.indexOf("layer y ") < 0) {
            return new Expansion(source, List.of(), false);
        }

        List<PlacementGroup> groups = new ArrayList<>(macros.groups());
        Map<String, Character> generatedStates = new LinkedHashMap<>();
        Map<Cell, String> claims = new HashMap<>();
        for (PlacementGroup group : groups) {
            for (BlockEntry block : group.blocks()) {
                Cell cell = new Cell(block.x(), block.y(), block.z());
                String prior = claims.putIfAbsent(cell, group.id());
                if (prior != null) {
                    throw new IllegalArgumentException("Feature collision at " + cell + ": " + prior + " overlaps " + group.id());
                }
            }
        }
        List<String> body = new ArrayList<>();
        boolean insideFeatures = false;
        boolean featuresComplete = false;
        boolean insideExplicitLayer = false;
        ExplicitLayer explicitLayer = null;
        List<String> explicitRows = new ArrayList<>();
        int featureIndex = 0;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.equals("VXB-1.1")) {
                body.add("VXB-1");
                continue;
            }
            if (line.equals("allow_floating true") || line.equals("allow_floating false")) {
                continue;
            }
            if (line.equals("features")) {
                insideFeatures = true;
                continue;
            }
            if (line.equals("endfeatures")) {
                insideFeatures = false;
                featuresComplete = true;
                continue;
            }
            if (insideFeatures) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                featureIndex++;
                PlacementGroup group = parseFeature("f" + featureIndex, line);
                for (BlockEntry block : group.blocks()) {
                    Cell cell = new Cell(block.x(), block.y(), block.z());
                    String prior = claims.putIfAbsent(cell, group.id());
                    if (prior != null) {
                        throw new IllegalArgumentException("Feature collision at " + cell + ": " + prior + " overlaps " + group.id());
                    }
                }
                groups.add(group);
                for (BlockEntry block : group.blocks()) {
                    char symbol = symbolFor(block.blockId(), existingStates, generatedStates, usedSymbols);
                    body.add("set " + block.x() + " " + block.y() + " " + block.z() + " " + symbol);
                }
                continue;
            }

            if (featuresComplete && !line.isEmpty() && !line.startsWith("#")) {
                throw new IllegalArgumentException("features must be the final VXB section; base geometry cannot overwrite reserved feature cells");
            }

            if (VxbPrimitiveCompiler.isPrimitive(line)) {
                body.addAll(VxbPrimitiveCompiler.expand(line, paletteStates,
                        state -> symbolFor(state, existingStates, generatedStates, usedSymbols)));
                continue;
            }

            ExplicitLayer parsedLayer = parseExplicitLayer(line);
            if (parsedLayer != null) {
                if (sizeX < 1 || sizeZ < 1) {
                    throw new IllegalArgumentException("size must appear before an explicit layer");
                }
                insideExplicitLayer = true;
                explicitLayer = parsedLayer;
                explicitRows.clear();
                continue;
            }
            if (insideExplicitLayer) {
                if (line.equals("endlayer")) {
                    emitExplicitLayer(body, explicitLayer, explicitRows, sizeX, sizeZ);
                    insideExplicitLayer = false;
                    explicitLayer = null;
                    explicitRows = new ArrayList<>();
                } else if (!line.startsWith("#") && !line.isEmpty()) {
                    explicitRows.add(line);
                }
                continue;
            }
            body.add(raw);
        }

        if (insideFeatures) throw new IllegalArgumentException("Unterminated features section");
        if (insideExplicitLayer) throw new IllegalArgumentException("Unterminated explicit layer");

        if (!generatedStates.isEmpty()) {
            int endPalette = -1;
            for (int i = 0; i < body.size(); i++) {
                if (body.get(i).trim().equals("endpalette")) {
                    endPalette = i;
                    break;
                }
            }
            if (endPalette < 0) throw new IllegalArgumentException("Semantic features require a palette section");
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, Character> entry : generatedStates.entrySet()) {
                entries.add(entry.getValue() + " = " + entry.getKey());
            }
            body.addAll(endPalette, entries);
        }

        return new Expansion(String.join("\n", body), List.copyOf(groups), allowFloating);
    }

    private static PlacementGroup parseFeature(String id, String line) {
        String[] p = line.split("\\s+");
        if (p.length < 4) throw new IllegalArgumentException("Invalid feature '" + line + "'");
        String kind = p[0].toLowerCase(Locale.ROOT);
        int x = parseInt(p[1], kind + " x");
        int y = parseInt(p[2], kind + " y");
        int z = parseInt(p[3], kind + " z");
        Map<String, String> options = options(p, 4);
        List<BlockEntry> blocks = new ArrayList<>();
        List<Cell> supports = new ArrayList<>();

        switch (kind) {
            case "door" -> {
                requireLength(p, 5, line);
                String family = familyBlock(p[4], "door");
                String outside = direction(required(options, "outside", line));
                String hinge = options.getOrDefault("hinge", "left");
                if (!hinge.equals("left") && !hinge.equals("right")) throw new IllegalArgumentException("door hinge must be left or right");
                String common = ",facing=" + outside + ",hinge=" + hinge + ",open=false,powered=false";
                blocks.add(block(x, y, z, family + "[half=lower" + common + "]"));
                blocks.add(block(x, y + 1, z, family + "[half=upper" + common + "]"));
                supports.add(new Cell(x, y - 1, z));
            }
            case "bed" -> {
                requireLength(p, 5, line);
                String color = p[4].toLowerCase(Locale.ROOT).replace("_bed", "");
                String head = direction(required(options, "head", line));
                int[] delta = delta(head);
                blocks.add(block(x, y, z, color + "_bed[part=foot,facing=" + head + ",occupied=false]"));
                blocks.add(block(x + delta[0], y, z + delta[1], color + "_bed[part=head,facing=" + head + ",occupied=false]"));
                supports.add(new Cell(x, y - 1, z));
                supports.add(new Cell(x + delta[0], y - 1, z + delta[1]));
            }
            case "stair" -> {
                requireLength(p, 5, line);
                String family = familyBlock(p[4], "stairs");
                String up = direction(required(options, "up", line));
                String half = options.getOrDefault("half", "bottom");
                if (!half.equals("bottom") && !half.equals("top")) throw new IllegalArgumentException("stair half must be bottom or top");
                blocks.add(block(x, y, z, family + "[facing=" + up + ",half=" + half + ",shape=straight,waterlogged=false]"));
                supports.add(new Cell(x, y - 1, z));
            }
            case "wall_torch" -> {
                String wall = direction(required(options, "wall", line));
                blocks.add(block(x, y, z, "wall_torch[facing=" + opposite(wall) + "]"));
                int[] d = delta(wall);
                supports.add(new Cell(x + d[0], y, z + d[1]));
            }
            case "ladder" -> {
                String wall = direction(required(options, "wall", line));
                blocks.add(block(x, y, z, "ladder[facing=" + opposite(wall) + ",waterlogged=false]"));
                int[] d = delta(wall);
                supports.add(new Cell(x + d[0], y, z + d[1]));
            }
            case "lantern" -> {
                boolean hanging = Boolean.parseBoolean(options.getOrDefault("hanging", "false"));
                blocks.add(block(x, y, z, "lantern[hanging=" + hanging + ",waterlogged=false]"));
                supports.add(new Cell(x, hanging ? y + 1 : y - 1, z));
            }
            case "trapdoor" -> {
                requireLength(p, 5, line);
                String family = familyBlock(p[4], "trapdoor");
                String front = direction(required(options, "front", line));
                String half = options.getOrDefault("half", "bottom");
                blocks.add(block(x, y, z, family + "[facing=" + front + ",half=" + half + ",open=false,powered=false,waterlogged=false]"));
                supports.add(new Cell(x, y - 1, z));
            }
            case "tall_plant" -> {
                requireLength(p, 5, line);
                String type = p[4].toLowerCase(Locale.ROOT);
                blocks.add(block(x, y, z, type + "[half=lower]"));
                blocks.add(block(x, y + 1, z, type + "[half=upper]"));
                supports.add(new Cell(x, y - 1, z));
            }
            default -> throw new IllegalArgumentException("Unknown semantic feature '" + kind + "'");
        }
        return new PlacementGroup(id, kind, List.copyOf(blocks), List.copyOf(supports), blocks.size() > 1);
    }

    private static ExplicitLayer parseExplicitLayer(String line) {
        if (!line.startsWith("layer y ") || !line.contains(" x ") || !line.contains(" w ") || !line.contains(" d ")) return null;
        String[] p = line.split("\\s+");
        if (p.length != 12 || !p[3].equals("x") || !p[5].equals("z") || !p[7].equals("w") || !p[9].equals("d")) {
            // Also accept the natural ten-token spelling: layer y 1 x 0 z 0 w 9 d 7
            if (p.length != 11 || !p[3].equals("x") || !p[5].equals("z") || !p[7].equals("w") || !p[9].equals("d")) {
                throw new IllegalArgumentException("Explicit layer must be: layer y Y[-Y] x X z Z w W d D");
            }
        }
        String y = p[2];
        int dash = y.indexOf('-', 1);
        int y1 = parseInt(dash < 0 ? y : y.substring(0, dash), "layer y");
        int y2 = parseInt(dash < 0 ? y : y.substring(dash + 1), "layer y");
        return new ExplicitLayer(y1, y2, parseInt(p[4], "layer x"), parseInt(p[6], "layer z"),
                parseInt(p[8], "layer width"), parseInt(p[10], "layer depth"));
    }

    private static void emitExplicitLayer(List<String> body, ExplicitLayer layer, List<String> rows, int sizeX, int sizeZ) {
        if (rows.size() != layer.depth) throw new IllegalArgumentException("Explicit layer expected " + layer.depth + " rows, got " + rows.size());
        if (layer.x < 0 || layer.z < 0 || layer.x + layer.width > sizeX || layer.z + layer.depth > sizeZ) {
            throw new IllegalArgumentException("Explicit layer exceeds declared size");
        }
        String air = ".".repeat(sizeX);
        body.add("layer y " + layer.y1 + (layer.y1 == layer.y2 ? "" : "-" + layer.y2) + " z 0");
        for (int z = 0; z < sizeZ; z++) {
            if (z < layer.z || z >= layer.z + layer.depth) {
                body.add(air);
            } else {
                String row = rows.get(z - layer.z);
                if (row.length() != layer.width) throw new IllegalArgumentException("Explicit layer row must contain exactly " + layer.width + " characters");
                body.add(".".repeat(layer.x) + row + ".".repeat(sizeX - layer.x - layer.width));
            }
        }
        body.add("endlayer");
    }

    private static char symbolFor(String state, Map<String, Character> existing, Map<String, Character> generated, Set<Character> used) {
        String normalized = normalizeState(state);
        Character found = existing.get(normalized);
        if (found != null) return found;
        found = generated.get(normalized);
        if (found != null) return found;
        for (int i = 0; i < SYMBOLS.length(); i++) {
            char candidate = SYMBOLS.charAt(i);
            if (candidate != '.' && candidate != '-' && used.add(candidate)) {
                generated.put(normalized, candidate);
                return candidate;
            }
        }
        throw new IllegalArgumentException("Palette exhausted while expanding semantic features");
    }

    private static Map<String, String> options(String[] parts, int start) {
        Map<String, String> result = new HashMap<>();
        for (int i = start; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) result.put(parts[i].substring(0, eq).toLowerCase(Locale.ROOT), parts[i].substring(eq + 1).toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static String required(Map<String, String> options, String key, String line) {
        String value = options.get(key);
        if (value == null) throw new IllegalArgumentException("Feature requires " + key + "=...: " + line);
        return value;
    }

    private static String familyBlock(String token, String suffix) {
        String value = token.toLowerCase(Locale.ROOT);
        if (!value.endsWith("_" + suffix)) value += "_" + suffix;
        return value;
    }

    private static String direction(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "north", "south", "east", "west" -> value.toLowerCase(Locale.ROOT);
            default -> throw new IllegalArgumentException("Direction must be north, south, east, or west: " + value);
        };
    }

    private static String opposite(String value) {
        return switch (direction(value)) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
    }

    private static int[] delta(String value) {
        return switch (direction(value)) {
            case "north" -> new int[]{0, -1};
            case "south" -> new int[]{0, 1};
            case "east" -> new int[]{1, 0};
            default -> new int[]{-1, 0};
        };
    }

    private static BlockEntry block(int x, int y, int z, String state) {
        return new BlockEntry(x, y, z, normalizeState(state));
    }

    private static String normalizeState(String state) {
        String value = state.trim().toLowerCase(Locale.ROOT);
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value);
        }
    }

    private static void requireLength(String[] parts, int length, String line) {
        if (parts.length < length) throw new IllegalArgumentException("Incomplete feature: " + line);
    }

    private record ExplicitLayer(int y1, int y2, int x, int z, int width, int depth) {}
}
