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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles reusable VXB-1.1 macros into transformed ordinary geometry and feature groups. */
final class VxbMacroCompiler {
    record Expansion(String vxb, List<PlacementGroup> groups) {}

    private static final Pattern MACRO = Pattern.compile(
            "^macro\\s+([A-Za-z][A-Za-z0-9_]*)\\s+size\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)$");
    private static final Pattern USE = Pattern.compile(
            "^use\\s+([A-Za-z][A-Za-z0-9_]*)\\s+at\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)(.*)$");
    private static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!$%&*+/:;?@^_~";
    private static final int MAX_INSTANCES = 256;
    private static final int MAX_EXPANDED_CELLS = 250_000;

    private VxbMacroCompiler() {}

    static Expansion expand(String source) {
        if (!hasMacroSyntax(source)) {
            return new Expansion(source, List.of());
        }

        String normalized = source.replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-');
        String[] lines = normalized.split("\\r?\\n", -1);
        Palette palette = readPalette(lines);
        Map<String, MacroDefinition> definitions = new LinkedHashMap<>();
        Set<Integer> definitionLines = new HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            Matcher match = MACRO.matcher(line);
            if (!match.matches()) continue;
            String name = match.group(1).toLowerCase(Locale.ROOT);
            if (definitions.containsKey(name)) throw new IllegalArgumentException("Duplicate macro '" + name + "'");
            int sx = positive(match.group(2), "macro size X");
            int sy = positive(match.group(3), "macro size Y");
            int sz = positive(match.group(4), "macro size Z");
            int start = i;
            List<String> body = new ArrayList<>();
            i++;
            while (i < lines.length && !lines[i].trim().equals("endmacro")) {
                String nested = lines[i].trim();
                if (nested.startsWith("macro ") || nested.startsWith("use ")) {
                    throw new IllegalArgumentException("Macros cannot contain nested macro/use commands: " + nested);
                }
                body.add(lines[i]);
                i++;
            }
            if (i >= lines.length) throw new IllegalArgumentException("Unterminated macro '" + name + "'");
            for (int lineIndex = start; lineIndex <= i; lineIndex++) definitionLines.add(lineIndex);
            definitions.put(name, compileDefinition(name, sx, sy, sz, body, palette.lines));
        }
        if (definitions.isEmpty()) throw new IllegalArgumentException("A use command requires at least one macro definition");

        List<String> output = new ArrayList<>();
        List<String> deferredFeatures = new ArrayList<>();
        List<PlacementGroup> transformedGroups = new ArrayList<>();
        Map<String, Character> generatedStates = new LinkedHashMap<>();
        int instances = 0;
        int expandedCells = 0;

        for (int i = 0; i < lines.length; i++) {
            if (definitionLines.contains(i)) continue;
            String line = lines[i].trim();
            Matcher use = USE.matcher(line);
            if (!use.matches()) {
                output.add(lines[i]);
                continue;
            }
            if (++instances > MAX_INSTANCES) throw new IllegalArgumentException("Macro instance limit exceeded (" + MAX_INSTANCES + ")");
            MacroDefinition definition = definitions.get(use.group(1).toLowerCase(Locale.ROOT));
            if (definition == null) throw new IllegalArgumentException("Unknown macro '" + use.group(1) + "'");
            int ox = integer(use.group(2), "macro instance X");
            int oy = integer(use.group(3), "macro instance Y");
            int oz = integer(use.group(4), "macro instance Z");
            Map<String, String> options = options(use.group(5).trim());
            int rotation = rotation(options.getOrDefault("rotate", "0"));
            String mirror = options.getOrDefault("mirror", "none");
            if (!mirror.equals("none") && !mirror.equals("x") && !mirror.equals("z")) {
                throw new IllegalArgumentException("macro mirror must be none, x, or z");
            }
            Transform transform = new Transform(definition.sizeX, definition.sizeZ, ox, oy, oz, rotation, mirror);
            Set<Cell> featureCells = new HashSet<>();
            for (PlacementGroup group : definition.structure.getFeatureGroups()) {
                for (BlockEntry block : group.blocks()) featureCells.add(new Cell(block.x(), block.y(), block.z()));
            }
            for (BlockEntry block : definition.structure.getBlocks()) {
                if (featureCells.contains(new Cell(block.x(), block.y(), block.z()))) continue;
                BlockEntry moved = transform.block(block);
                char symbol = symbolFor(moved.blockId(), palette, generatedStates);
                output.add(set(moved, symbol));
                expandedCells++;
            }
            for (Cell clear : definition.structure.getClearCells()) {
                Cell moved = transform.cell(clear);
                char symbol = symbolFor("minecraft:air", palette, generatedStates);
                output.add("set " + moved.x() + " " + moved.y() + " " + moved.z() + " " + symbol);
                expandedCells++;
            }
            int groupNumber = 0;
            for (PlacementGroup group : definition.structure.getFeatureGroups()) {
                List<BlockEntry> blocks = group.blocks().stream().map(transform::block).toList();
                List<Cell> supports = group.requiredSupports().stream().map(transform::cell).toList();
                String id = "m" + instances + "_" + (++groupNumber) + "_" + group.kind();
                PlacementGroup moved = new PlacementGroup(id, group.kind(), blocks, supports, group.atomic());
                transformedGroups.add(moved);
                for (BlockEntry block : blocks) {
                    char symbol = symbolFor(block.blockId(), palette, generatedStates);
                    deferredFeatures.add(set(block, symbol));
                    expandedCells++;
                }
            }
            if (expandedCells > MAX_EXPANDED_CELLS) {
                throw new IllegalArgumentException("Expanded macro cell limit exceeded (" + MAX_EXPANDED_CELLS + ")");
            }
        }

        if (!deferredFeatures.isEmpty()) {
            int featureStart = indexOfTrimmed(output, "features");
            output.addAll(featureStart < 0 ? output.size() : featureStart, deferredFeatures);
        }
        if (!generatedStates.isEmpty()) {
            int endPalette = indexOfTrimmed(output, "endpalette");
            if (endPalette < 0) throw new IllegalArgumentException("Macros require a global palette section");
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, Character> entry : generatedStates.entrySet()) {
                entries.add(entry.getValue() + " = " + entry.getKey());
            }
            output.addAll(endPalette, entries);
        }
        return new Expansion(String.join("\n", output), List.copyOf(transformedGroups));
    }

    private static boolean hasMacroSyntax(String source) {
        for (String raw : source.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.startsWith("#") && (line.startsWith("macro ") || line.startsWith("use "))) return true;
        }
        return false;
    }

    private static MacroDefinition compileDefinition(String name, int sx, int sy, int sz,
                                                     List<String> body, List<String> paletteLines) {
        List<String> source = new ArrayList<>();
        source.add("VXB-1.1");
        source.add("name macro_" + name);
        source.add("size " + sx + " " + sy + " " + sz);
        source.add("allow_floating true");
        source.add("terrain_mode preserve");
        source.add("palette");
        source.addAll(paletteLines);
        source.add("endpalette");
        source.addAll(body);
        BuildStructure structure = BuildStructure.parse(String.join("\n", source));
        return new MacroDefinition(sx, sz, structure);
    }

    private static Palette readPalette(String[] lines) {
        boolean inside = false;
        List<String> paletteLines = new ArrayList<>();
        Map<String, Character> byState = new HashMap<>();
        Set<Character> used = new HashSet<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.equals("palette")) {
                inside = true;
                continue;
            }
            if (line.equals("endpalette")) {
                inside = false;
                continue;
            }
            if (!inside || line.isEmpty() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals < 1) continue;
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if (key.length() == 1) {
                char symbol = key.charAt(0);
                paletteLines.add(line);
                used.add(symbol);
                byState.put(normalizeState(value), symbol);
            }
        }
        if (paletteLines.isEmpty()) throw new IllegalArgumentException("Macros require a non-empty global palette");
        return new Palette(paletteLines, byState, used);
    }

    private static char symbolFor(String state, Palette palette, Map<String, Character> generated) {
        String normalized = normalizeState(state);
        Character found = palette.byState.get(normalized);
        if (found != null) return found;
        found = generated.get(normalized);
        if (found != null) return found;
        for (int i = 0; i < SYMBOLS.length(); i++) {
            char candidate = SYMBOLS.charAt(i);
            if (candidate != '.' && candidate != '-' && palette.used.add(candidate)) {
                generated.put(normalized, candidate);
                return candidate;
            }
        }
        throw new IllegalArgumentException("Palette exhausted while expanding macros");
    }

    private static String transformState(String state, int rotation, String mirror) {
        int bracket = state.indexOf('[');
        if (bracket < 0) return state;
        String base = state.substring(0, bracket);
        String properties = state.substring(bracket + 1, state.lastIndexOf(']'));
        List<String> transformed = new ArrayList<>();
        for (String assignment : properties.split(",")) {
            String[] pair = assignment.trim().split("=", 2);
            if (pair.length != 2) {
                transformed.add(assignment.trim());
                continue;
            }
            String key = pair[0];
            String value = pair[1];
            if (isDirection(key)) key = transformDirection(key, rotation, mirror);
            if (key.equals("facing") && isDirection(value)) value = transformDirection(value, rotation, mirror);
            if (key.equals("axis") && (rotation == 90 || rotation == 270)) {
                if (value.equals("x")) value = "z";
                else if (value.equals("z")) value = "x";
            }
            if (!mirror.equals("none") && key.equals("hinge")) value = swapLeftRight(value);
            if (!mirror.equals("none") && key.equals("shape")) value = swapLeftRight(value);
            transformed.add(key + "=" + value);
        }
        return base + "[" + String.join(",", transformed) + "]";
    }

    private static String swapLeftRight(String value) {
        if (value.endsWith("_left")) return value.substring(0, value.length() - 5) + "_right";
        if (value.endsWith("_right")) return value.substring(0, value.length() - 6) + "_left";
        if (value.equals("left")) return "right";
        if (value.equals("right")) return "left";
        return value;
    }

    private static String transformDirection(String direction, int rotation, String mirror) {
        String[] clockwise = {"north", "east", "south", "west"};
        int index = switch (direction) {
            case "east" -> 1;
            case "south" -> 2;
            case "west" -> 3;
            default -> 0;
        };
        String result = clockwise[(index + rotation / 90) % 4];
        if (mirror.equals("x")) {
            if (result.equals("east")) result = "west";
            else if (result.equals("west")) result = "east";
        } else if (mirror.equals("z")) {
            if (result.equals("north")) result = "south";
            else if (result.equals("south")) result = "north";
        }
        return result;
    }

    private static boolean isDirection(String value) {
        return value.equals("north") || value.equals("south") || value.equals("east") || value.equals("west");
    }

    private static Map<String, String> options(String suffix) {
        Map<String, String> result = new HashMap<>();
        if (suffix.isBlank()) return result;
        for (String token : suffix.split("\\s+")) {
            int equals = token.indexOf('=');
            if (equals < 1 || equals == token.length() - 1) {
                throw new IllegalArgumentException("Macro instance options must be key=value: " + token);
            }
            String key = token.substring(0, equals).toLowerCase(Locale.ROOT);
            String prior = result.putIfAbsent(key, token.substring(equals + 1).toLowerCase(Locale.ROOT));
            if (prior != null) throw new IllegalArgumentException("Duplicate macro option '" + key + "'");
        }
        return result;
    }

    private static int rotation(String value) {
        int rotation = integer(value, "macro rotation");
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new IllegalArgumentException("macro rotate must be 0, 90, 180, or 270");
        }
        return rotation;
    }

    private static int indexOfTrimmed(List<String> lines, String target) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).trim().equals(target)) return i;
        return -1;
    }

    private static String set(BlockEntry block, char symbol) {
        return "set " + block.x() + " " + block.y() + " " + block.z() + " " + symbol;
    }

    private static String normalizeState(String state) {
        String value = state.trim().toLowerCase(Locale.ROOT);
        return value.contains(":") ? value : "minecraft:" + value;
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

    private record Palette(List<String> lines, Map<String, Character> byState, Set<Character> used) {}
    private record MacroDefinition(int sizeX, int sizeZ, BuildStructure structure) {}

    private record Transform(int sourceSizeX, int sourceSizeZ, int ox, int oy, int oz,
                             int rotation, String mirror) {
        Cell cell(Cell cell) {
            int x = cell.x();
            int z = cell.z();
            int rotatedX;
            int rotatedZ;
            int width;
            int depth;
            switch (rotation) {
                case 90 -> {
                    rotatedX = sourceSizeZ - 1 - z;
                    rotatedZ = x;
                    width = sourceSizeZ;
                    depth = sourceSizeX;
                }
                case 180 -> {
                    rotatedX = sourceSizeX - 1 - x;
                    rotatedZ = sourceSizeZ - 1 - z;
                    width = sourceSizeX;
                    depth = sourceSizeZ;
                }
                case 270 -> {
                    rotatedX = z;
                    rotatedZ = sourceSizeX - 1 - x;
                    width = sourceSizeZ;
                    depth = sourceSizeX;
                }
                default -> {
                    rotatedX = x;
                    rotatedZ = z;
                    width = sourceSizeX;
                    depth = sourceSizeZ;
                }
            }
            if (mirror.equals("x")) rotatedX = width - 1 - rotatedX;
            if (mirror.equals("z")) rotatedZ = depth - 1 - rotatedZ;
            return new Cell(ox + rotatedX, oy + cell.y(), oz + rotatedZ);
        }

        BlockEntry block(BlockEntry block) {
            Cell moved = cell(new Cell(block.x(), block.y(), block.z()));
            return new BlockEntry(moved.x(), moved.y(), moved.z(),
                    transformState(block.blockId(), rotation, mirror));
        }
    }
}
