package com.assistantbot.llm;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-phase compiler diagnostic and architectural linting engine for VXB-1.
 * Runs blocker syntax checks and heuristic semantic audits to ensure clean renders
 * and survival-compliant Minecraft placements.
 */
public class VxbDiagnostics {

    public enum Severity {
        BLOCKER,
        WARNING
    }

    public record Diagnostic(Severity severity, String checkName, String message, Integer lineNum) {
        @Override
        public String toString() {
            String prefix = severity == Severity.BLOCKER ? "§c[Blocker] " : "§e[Warning] ";
            String lineStr = lineNum != null ? " (Line " + lineNum + ")" : "";
            return prefix + checkName + ": " + message + lineStr;
        }

        public String toLlmReportString() {
            String severityStr = severity == Severity.BLOCKER ? "BLOCKER ERROR" : "WARNING";
            String lineStr = lineNum != null ? " at line " + lineNum : "";
            return "- [" + severityStr + "] " + checkName + ": " + message + lineStr;
        }
    }

    public static class DiagnosticResult {
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private boolean hasBlockers = false;
        private boolean hasWarnings = false;

        public void add(Severity severity, String checkName, String message, Integer lineNum) {
            diagnostics.add(new Diagnostic(severity, checkName, message, lineNum));
            if (severity == Severity.BLOCKER) {
                hasBlockers = true;
            } else {
                hasWarnings = true;
            }
        }

        public List<Diagnostic> getDiagnostics() { return diagnostics; }
        public boolean hasBlockers() { return hasBlockers; }
        public boolean hasWarnings() { return hasWarnings; }

        public List<Diagnostic> getBlockers() {
            List<Diagnostic> blockers = new ArrayList<>();
            for (Diagnostic d : diagnostics) {
                if (d.severity() == Severity.BLOCKER) blockers.add(d);
            }
            return blockers;
        }

        public List<Diagnostic> getWarnings() {
            List<Diagnostic> warnings = new ArrayList<>();
            for (Diagnostic d : diagnostics) {
                if (d.severity() == Severity.WARNING) warnings.add(d);
            }
            return warnings;
        }

        public String getLlmReport() {
            if (diagnostics.isEmpty()) {
                return "No diagnostics issues found.";
            }
            StringBuilder sb = new StringBuilder();
            for (Diagnostic d : diagnostics) {
                sb.append(d.toLlmReportString()).append("\n");
            }
            return sb.toString();
        }
    }

    public record BlockInfo(char symbol, String blockId, int lineNum, int x, int y, int z) {}

    private static final Pattern BOX_PATTERN = Pattern.compile(
            "^box\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(\\S)$");
    private static final Pattern SET_PATTERN = Pattern.compile(
            "^set\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(\\S)$");
    private static final Pattern LAYER_PATTERN = Pattern.compile(
            "^layer\\s+y\\s+(-?\\d+)(?:\\s*-\\s*(-?\\d+))?\\s+z\\s+(-?\\d+)$");
    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "^size\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)$");

    /**
     * Run the comprehensive multi-phase diagnostics on a VXB-1 text.
     */
    public static DiagnosticResult run(String vxb) {
        DiagnosticResult result = new DiagnosticResult();

        // 1. Preprocess & normalize
        String cleaned = stripCodeFences(vxb).trim();
        cleaned = normalizeDashes(cleaned);

        if (cleaned.isEmpty()) {
            result.add(Severity.BLOCKER, "VXB-1 Header Check", "Empty VXB-1 input string", null);
            return result;
        }

        String[] lines = cleaned.split("\\r?\\n");

        // 2. VXB-1 Header Check
        int firstNonEmptyLineIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                firstNonEmptyLineIdx = i;
                break;
            }
        }

        if (firstNonEmptyLineIdx == -1 || !lines[firstNonEmptyLineIdx].trim().equals("VXB-1")) {
            result.add(Severity.BLOCKER, "VXB-1 Header Check",
                    "Missing VXB-1 header. The file must begin with 'VXB-1' as the first non-comment line.",
                    firstNonEmptyLineIdx != -1 ? firstNonEmptyLineIdx + 1 : 1);
            // Can't reliably run other checks if the header is broken or it's not VXB-1
            return result;
        }

        // State trackers
        Map<Character, String> palette = new HashMap<>();
        Set<Character> usedSymbols = new HashSet<>();
        int sizeX = -1, sizeY = -1, sizeZ = -1;
        boolean hasBounds = false;

        // packed location -> block details
        Map<Long, BlockInfo> blockGrid = new LinkedHashMap<>();

        boolean inPalette = false;
        boolean inLayer = false;
        int layerYMin = 0;
        int layerYMax = 0;
        int layerZ0 = 0;
        int currentLayerRow = 0;
        int layerStartLineNum = -1;

        // Step through the file
        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String raw = lines[i];
            String line = raw.trim();

            // Skip blank lines and comments
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            // --- Palette Section Check ---
            if (line.equals("palette")) {
                if (inPalette) {
                    result.add(Severity.BLOCKER, "Palette Format Validation",
                            "Nested palette declaration is invalid.", lineNum);
                }
                inPalette = true;
                continue;
            }

            if (line.equals("endpalette")) {
                if (!inPalette) {
                    result.add(Severity.BLOCKER, "Palette Format Validation",
                            "Orphaned 'endpalette' without a starting 'palette' block.", lineNum);
                }
                inPalette = false;
                if (palette.isEmpty()) {
                    result.add(Severity.BLOCKER, "Palette Format Validation",
                            "Palette section is completely empty.", lineNum);
                }
                continue;
            }

            if (inPalette) {
                parseAndValidatePaletteLine(line, palette, result, lineNum);
                continue;
            }

            // --- Layer Section Check ---
            if (line.equals("endlayer")) {
                if (!inLayer) {
                    result.add(Severity.BLOCKER, "Layer Declaration Integrity",
                            "Orphaned 'endlayer' without a starting 'layer' block.", lineNum);
                } else {
                    inLayer = false;
                    // Layer Row-Count Depth Assert
                    if (hasBounds && currentLayerRow != sizeZ) {
                        result.add(Severity.BLOCKER, "Layer Row-Count Depth Assert",
                                "Layer depth mismatch: parsed " + currentLayerRow + " rows, but size specifies exactly " + sizeZ + " rows.",
                                layerStartLineNum);
                    }
                }
                continue;
            }

            if (inLayer) {
                // Check layer row
                int z = layerZ0 + currentLayerRow;

                // Layer Row-Length Columns Assert
                if (hasBounds && line.length() != sizeX) {
                    result.add(Severity.WARNING, "Layer Row-Length Columns Assert",
                            "Layer row length mismatch: got " + line.length() + " characters, but size specifies exactly " + sizeX + " columns. This row will be truncated or padded with air.",
                            lineNum);
                }

                int cols = (hasBounds) ? Math.min(line.length(), sizeX) : line.length();
                for (int xi = 0; xi < cols; xi++) {
                    char ch = line.charAt(xi);
                    usedSymbols.add(ch);

                    if (ch == '.' || ch == '-' || ch == ' ') {
                        // Air placement clears any block at this position
                        for (int yi = layerYMin; yi <= layerYMax; yi++) {
                            blockGrid.remove(packPos(xi, yi, z));
                        }
                        continue;
                    }

                    String blockId = palette.get(ch);
                    if (blockId == null) {
                        result.add(Severity.WARNING, "Undeclared Palette Symbols",
                                "Character symbol '" + ch + "' is used in layer but not declared in the palette section. Defaulting to air.",
                                lineNum);
                        blockId = "minecraft:air";
                    }

                    // Bounding-Box Bounds Enforcement
                    if (hasBounds) {
                        for (int yi = layerYMin; yi <= layerYMax; yi++) {
                            if (xi >= sizeX || yi < 0 || yi >= sizeY || z < 0 || z >= sizeZ) {
                                result.add(Severity.BLOCKER, "Bounding-Box Bounds Enforcement",
                                        "Coordinates out of bounds: (" + xi + "," + yi + "," + z + ") is outside size limits [0, " + (sizeX-1) + "]x[0, " + (sizeY-1) + "]x[0, " + (sizeZ-1) + "].",
                                        lineNum);
                            } else {
                                if (!blockId.equals("minecraft:air")) {
                                    blockGrid.put(packPos(xi, yi, z), new BlockInfo(ch, blockId, lineNum, xi, yi, z));
                                }
                            }
                        }
                    } else {
                        // Place without bounds check if size is unknown
                        for (int yi = layerYMin; yi <= layerYMax; yi++) {
                            if (!blockId.equals("minecraft:air")) {
                                blockGrid.put(packPos(xi, yi, z), new BlockInfo(ch, blockId, lineNum, xi, yi, z));
                            }
                        }
                    }
                }
                currentLayerRow++;
                continue;
            }

            // --- Non-section commands & metadata ---
            if (line.startsWith("name ") || line.startsWith("axes ") || line.startsWith("origin ")) {
                continue;
            }

            // Size
            Matcher sizeMatch = SIZE_PATTERN.matcher(line);
            if (sizeMatch.matches()) {
                sizeX = Integer.parseInt(sizeMatch.group(1));
                sizeY = Integer.parseInt(sizeMatch.group(2));
                sizeZ = Integer.parseInt(sizeMatch.group(3));
                hasBounds = true;
                continue;
            }

            // Box Command
            Matcher boxMatch = BOX_PATTERN.matcher(line);
            if (boxMatch.matches()) {
                int x1 = Integer.parseInt(boxMatch.group(1));
                int y1 = Integer.parseInt(boxMatch.group(2));
                int z1 = Integer.parseInt(boxMatch.group(3));
                int x2 = Integer.parseInt(boxMatch.group(4));
                int y2 = Integer.parseInt(boxMatch.group(5));
                int z2 = Integer.parseInt(boxMatch.group(6));
                char sym = boxMatch.group(7).charAt(0);

                usedSymbols.add(sym);
                String blockId = palette.get(sym);
                if (blockId == null) {
                    result.add(Severity.WARNING, "Undeclared Palette Symbols",
                            "Character symbol '" + sym + "' in box command is not declared in the palette. Defaulting to air.",
                            lineNum);
                    blockId = "minecraft:air";
                }

                int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
                int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
                int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

                // Bounding-Box Bounds Enforcement
                if (hasBounds) {
                    if (minX < 0 || maxX >= sizeX || minY < 0 || maxY >= sizeY || minZ < 0 || maxZ >= sizeZ) {
                        result.add(Severity.BLOCKER, "Bounding-Box Bounds Enforcement",
                                "Box coordinates (" + minX + "," + minY + "," + minZ + ") to (" + maxX + "," + maxY + "," + maxZ + ") exceed size limits [0, " + (sizeX-1) + "]x[0, " + (sizeY-1) + "]x[0, " + (sizeZ-1) + "].",
                                lineNum);
                    }
                }

                // Place in grid (clamped to bounds to prevent infinite memory usage on huge coords)
                if (hasBounds) {
                    minX = Math.max(0, Math.min(minX, sizeX - 1));
                    maxX = Math.max(0, Math.min(maxX, sizeX - 1));
                    minY = Math.max(0, Math.min(minY, sizeY - 1));
                    maxY = Math.max(0, Math.min(maxY, sizeY - 1));
                    minZ = Math.max(0, Math.min(minZ, sizeZ - 1));
                    maxZ = Math.max(0, Math.min(maxZ, sizeZ - 1));
                }

                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int x = minX; x <= maxX; x++) {
                            if (blockId.equals("minecraft:air")) {
                                blockGrid.remove(packPos(x, y, z));
                            } else {
                                blockGrid.put(packPos(x, y, z), new BlockInfo(sym, blockId, lineNum, x, y, z));
                            }
                        }
                    }
                }
                continue;
            }

            // Set Command
            Matcher setMatch = SET_PATTERN.matcher(line);
            if (setMatch.matches()) {
                int x = Integer.parseInt(setMatch.group(1));
                int y = Integer.parseInt(setMatch.group(2));
                int z = Integer.parseInt(setMatch.group(3));
                char sym = setMatch.group(4).charAt(0);

                usedSymbols.add(sym);
                String blockId = palette.get(sym);
                if (blockId == null) {
                    result.add(Severity.WARNING, "Undeclared Palette Symbols",
                            "Character symbol '" + sym + "' in set command is not declared in the palette. Defaulting to air.",
                            lineNum);
                    blockId = "minecraft:air";
                }

                // Bounding-Box Bounds Enforcement
                if (hasBounds) {
                    if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) {
                        result.add(Severity.BLOCKER, "Bounding-Box Bounds Enforcement",
                                "Set coordinates (" + x + "," + y + "," + z + ") exceed size limits [0, " + (sizeX-1) + "]x[0, " + (sizeY-1) + "]x[0, " + (sizeZ-1) + "].",
                                lineNum);
                    } else {
                        if (blockId.equals("minecraft:air")) {
                            blockGrid.remove(packPos(x, y, z));
                        } else {
                            blockGrid.put(packPos(x, y, z), new BlockInfo(sym, blockId, lineNum, x, y, z));
                        }
                    }
                } else {
                    if (blockId.equals("minecraft:air")) {
                        blockGrid.remove(packPos(x, y, z));
                    } else {
                        blockGrid.put(packPos(x, y, z), new BlockInfo(sym, blockId, lineNum, x, y, z));
                    }
                }
                continue;
            }

            // Layer Block Declaration
            Matcher layerMatch = LAYER_PATTERN.matcher(line);
            if (layerMatch.matches()) {
                int y1 = Integer.parseInt(layerMatch.group(1));
                String y2Str = layerMatch.group(2);
                int y2 = (y2Str != null) ? Integer.parseInt(y2Str) : y1;
                layerYMin = Math.min(y1, y2);
                layerYMax = Math.max(y1, y2);
                layerZ0 = Integer.parseInt(layerMatch.group(3));
                currentLayerRow = 0;
                inLayer = true;
                layerStartLineNum = lineNum;

                // Bounding-Box Bounds Enforcement
                if (hasBounds) {
                    if (layerYMin < 0 || layerYMax >= sizeY || layerZ0 < 0 || layerZ0 >= sizeZ) {
                        result.add(Severity.BLOCKER, "Bounding-Box Bounds Enforcement",
                                "Layer Y-level range (" + layerYMin + "-" + layerYMax + ") or starting Z (" + layerZ0 + ") exceeds size limits.",
                                lineNum);
                    }
                }
                continue;
            }

            // If we get here, it's an unrecognized command
            if (!line.equals("palette") && !line.equals("endpalette") && !line.equals("endlayer") && !line.equals("VXB-1")) {
                result.add(Severity.WARNING, "Layer Declaration Integrity",
                        "Unrecognized command or text line outside sections: '" + line + "'", lineNum);
            }
        }

        // Clean-up Integrity Checks
        if (inPalette) {
            result.add(Severity.BLOCKER, "Palette Format Validation",
                    "Unterminated palette section (missing 'endpalette').", null);
        }
        if (inLayer) {
            result.add(Severity.BLOCKER, "Layer Declaration Integrity",
                    "Unterminated layer section (missing 'endlayer').", layerStartLineNum);
        }

        // --- Once-Over Semantic Audits (Warnings) ---
        if (!palette.isEmpty()) {
            // 1. Unused Palette Declarations
            for (Character sym : palette.keySet()) {
                if (sym == '.') continue; // standard air symbol
                if (!usedSymbols.contains(sym)) {
                    result.add(Severity.WARNING, "Unused Palette Declarations",
                            "Palette symbol '" + sym + "' (" + palette.get(sym) + ") was declared but never used in any layout commands.",
                            null);
                }
            }
        }

        // 2. Door Alignments, Torches, and Panes
        for (BlockInfo info : blockGrid.values()) {
            String id = info.blockId().toLowerCase();

            // Double-Tall Door Alignments
            if (id.contains("door") && !id.contains("trapdoor")) {
                boolean isLower = id.contains("half=lower") || id.contains("lower");
                boolean isUpper = id.contains("half=upper") || id.contains("upper");

                if (isLower) {
                    BlockInfo upperInfo = blockGrid.get(packPos(info.x(), info.y() + 1, info.z()));
                    if (upperInfo == null || !upperInfo.blockId().toLowerCase().contains("door") || !upperInfo.blockId().toLowerCase().contains("upper")) {
                        result.add(Severity.WARNING, "Double-Tall Door Alignments",
                                "Standard door lower-half at (" + info.x() + "," + info.y() + "," + info.z() + ") is missing its corresponding upper-half directly on the Y-level above it.",
                                info.lineNum());
                    }
                } else if (isUpper) {
                    BlockInfo lowerInfo = blockGrid.get(packPos(info.x(), info.y() - 1, info.z()));
                    if (lowerInfo == null || !lowerInfo.blockId().toLowerCase().contains("door") || !lowerInfo.blockId().toLowerCase().contains("lower")) {
                        result.add(Severity.WARNING, "Double-Tall Door Alignments",
                                "Standard door upper-half at (" + info.x() + "," + info.y() + "," + info.z() + ") is missing its corresponding lower-half directly on the Y-level below it.",
                                info.lineNum());
                    }
                }
            }

            // Floating Torch Validator
            if (id.contains("torch") && !id.contains("torchflower")) {
                boolean supportUnder = isSolid(blockGrid.get(packPos(info.x(), info.y() - 1, info.z())));
                boolean supportNorth = isSolid(blockGrid.get(packPos(info.x(), info.y(), info.z() - 1)));
                boolean supportSouth = isSolid(blockGrid.get(packPos(info.x(), info.y(), info.z() + 1)));
                boolean supportEast = isSolid(blockGrid.get(packPos(info.x() + 1, info.y(), info.z())));
                boolean supportWest = isSolid(blockGrid.get(packPos(info.x() - 1, info.y(), info.z())));

                if (!supportUnder && !supportNorth && !supportSouth && !supportEast && !supportWest) {
                    result.add(Severity.WARNING, "Floating Torch Validator",
                            "Floating torch '" + info.blockId() + "' at (" + info.x() + "," + info.y() + "," + info.z() + ") has no supporting solid block underneath or on any of its horizontal sides.",
                            info.lineNum());
                }
            }

            // Isolated Window Panes
            if (id.contains("glass_pane")) {
                boolean connNorth = isSolidOrPane(blockGrid.get(packPos(info.x(), info.y(), info.z() - 1)));
                boolean connSouth = isSolidOrPane(blockGrid.get(packPos(info.x(), info.y(), info.z() + 1)));
                boolean connEast = isSolidOrPane(blockGrid.get(packPos(info.x() + 1, info.y(), info.z())));
                boolean connWest = isSolidOrPane(blockGrid.get(packPos(info.x() - 1, info.y(), info.z())));

                if (!connNorth && !connSouth && !connEast && !connWest) {
                    result.add(Severity.WARNING, "Isolated Window Panes",
                            "Isolated glass pane at (" + info.x() + "," + info.y() + "," + info.z() + ") forms an awkward 'thin cross' visually. Connect it flush to a solid block or contiguous panes.",
                            info.lineNum());
                }
            }
        }

        return result;
    }

    private static void parseAndValidatePaletteLine(String line, Map<Character, String> palette, DiagnosticResult result, int lineNum) {
        int eqIdx = line.indexOf('=');
        if (eqIdx < 0) {
            result.add(Severity.BLOCKER, "Palette Format Validation",
                    "Palette line is missing '=': '" + line + "'. Expected format: Symbol = BlockState", lineNum);
            return;
        }

        String keyPart = line.substring(0, eqIdx).trim();
        String valuePart = line.substring(eqIdx + 1).trim();

        if (keyPart.length() != 1) {
            result.add(Severity.BLOCKER, "Palette Format Validation",
                    "Palette key must be a single character, got: '" + keyPart + "'", lineNum);
            return;
        }

        if (valuePart.isEmpty()) {
            result.add(Severity.BLOCKER, "Palette Format Validation",
                    "Palette key '" + keyPart + "' has an empty block ID/state definition.", lineNum);
            return;
        }

        char symbol = keyPart.charAt(0);
        String fullBlockId = ensureNamespace(valuePart);
        palette.put(symbol, fullBlockId);

        // Registry Validation
        String baseId = BlockIdResolver.normalizeBaseId(fullBlockId);
        if (!BlockIdResolver.isValidBlockId(fullBlockId)) {
            result.add(Severity.BLOCKER, "Invalid Minecraft Block ID",
                    "Block '" + baseId + "' does not exist in the Minecraft 1.21.11 block registry.", lineNum);
        }

    }

    private static boolean isSolid(BlockInfo block) {
        if (block == null) return false;
        String id = block.blockId().toLowerCase();
        return !id.contains("air")
                && !id.contains("glass_pane")
                && !id.contains("door")
                && !id.contains("torch")
                && !id.contains("lantern")
                && !id.contains("chain")
                && !id.contains("ladder")
                && !id.contains("water")
                && !id.contains("lava")
                && !id.contains("grass")
                && !id.contains("flower")
                && !id.contains("fern")
                && !id.contains("sapling")
                && !id.contains("button")
                && !id.contains("pressure_plate")
                && !id.contains("lever")
                && !id.contains("rail")
                && !id.contains("vine")
                && !id.contains("carpet")
                && !id.contains("fire")
                && !id.contains("sign");
    }

    private static boolean isSolidOrPane(BlockInfo block) {
        if (block == null) return false;
        if (block.blockId().toLowerCase().contains("glass_pane")) return true;
        return isSolid(block);
    }

    private static String ensureNamespace(String name) {
        if (name.contains(":")) return name;
        return "minecraft:" + name;
    }

    private static String stripCodeFences(String input) {
        String s = input.trim();
        if (s.startsWith("```")) {
            int newline = s.indexOf('\n');
            if (newline >= 0) {
                s = s.substring(newline + 1);
            }
        }
        if (s.endsWith("```")) {
            int lastNewline = s.lastIndexOf('\n');
            if (lastNewline >= 0 && s.substring(lastNewline + 1).trim().equals("```")) {
                s = s.substring(0, lastNewline);
            }
        }
        return s;
    }

    private static String normalizeDashes(String input) {
        return input.replace('\u2013', '-')
                    .replace('\u2014', '-')
                    .replace('\u2212', '-');
    }

    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
