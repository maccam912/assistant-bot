package com.assistantbot.llm;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a structure returned by the LLM in VXB-1 format: a list of block
 * entries with relative coordinates. Parses the VXB-1 text format which uses
 * palette symbols, box/set primitives, and layer grids with last-write-wins
 * semantics.
 *
 * @see <a href="../../minecraft_vxb_spec.md">VXB-1 specification</a>
 */
public class BuildStructure {

    /**
     * A single block in the structure, with coordinates relative to the build origin.
     */
    public record BlockEntry(int x, int y, int z, String blockId) {}

    private final List<BlockEntry> blocks;
    private final Map<String, Integer> materials;

    public BuildStructure(List<BlockEntry> blocks, Map<String, Integer> materials) {
        this.blocks = blocks;
        this.materials = materials;
    }

    public List<BlockEntry> getBlocks() { return blocks; }
    public Map<String, Integer> getMaterials() { return materials; }

    // --- VXB-1 Parser ---

    private static final Pattern BOX_PATTERN = Pattern.compile(
            "^box\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(\\S)$");
    private static final Pattern SET_PATTERN = Pattern.compile(
            "^set\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(\\S)$");
    private static final Pattern LAYER_PATTERN = Pattern.compile(
            "^layer\\s+y\\s+(-?\\d+)\\s+z\\s+(-?\\d+)$");
    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "^size\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)$");
    private static final Pattern ORIGIN_PATTERN = Pattern.compile(
            "^origin\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)$");

    /**
     * Parse a VXB-1 format string into a BuildStructure. The format uses:
     * <ul>
     *   <li>{@code palette}/{@code endpalette} — symbol-to-block-ID mapping</li>
     *   <li>{@code box x1 y1 z1 x2 y2 z2 S} — inclusive cuboid fill</li>
     *   <li>{@code set x y z S} — single block placement</li>
     *   <li>{@code layer y Y z Z0}/{@code endlayer} — 2D character grid at fixed Y</li>
     * </ul>
     * Later commands overwrite earlier ones (last-write-wins).
     *
     * @param vxb the VXB-1 text content (may include leading/trailing whitespace or
     *            markdown code fences which are stripped)
     * @return parsed BuildStructure
     * @throws IllegalArgumentException if the format is invalid
     */
    public static BuildStructure parse(String vxb) {
        // Strip markdown code fences if the LLM wrapped the output
        String cleaned = stripCodeFences(vxb).trim();

        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Empty VXB-1 input");
        }

        String[] lines = cleaned.split("\\r?\\n");

        // Validate header
        if (!lines[0].trim().equals("VXB-1")) {
            throw new IllegalArgumentException(
                    "Missing VXB-1 header. First line must be 'VXB-1', got: '" + lines[0].trim() + "'");
        }

        // State
        Map<Character, String> palette = new HashMap<>();
        int sizeX = -1, sizeY = -1, sizeZ = -1;
        boolean hasBounds = false;

        // Use a position map for last-write-wins semantics
        // key = packed position, value = block ID
        Map<Long, String> positionMap = new LinkedHashMap<>();

        boolean inPalette = false;
        boolean inLayer = false;
        int layerY = 0;
        int layerZ0 = 0;
        int currentLayerRow = 0;

        for (int lineNum = 1; lineNum < lines.length; lineNum++) {
            String raw = lines[lineNum];
            String line = raw.trim();

            // Skip blank lines and comments
            if (line.isEmpty() || line.startsWith("#")) continue;

            // --- Palette section ---
            if (line.equals("palette")) {
                inPalette = true;
                continue;
            }
            if (line.equals("endpalette")) {
                inPalette = false;
                if (palette.isEmpty()) {
                    throw new IllegalArgumentException("Empty palette section");
                }
                continue;
            }
            if (inPalette) {
                parsePaletteEntry(line, palette, lineNum);
                continue;
            }

            // --- Layer section ---
            if (line.equals("endlayer")) {
                inLayer = false;
                continue;
            }
            if (inLayer) {
                // Each line in a layer is a row of symbols at z = layerZ0 + currentLayerRow
                int z = layerZ0 + currentLayerRow;
                for (int xi = 0; xi < line.length(); xi++) {
                    char ch = line.charAt(xi);
                    if (ch == '.') {
                        // Air — remove any previously placed block at this position
                        // (last-write-wins: explicitly writing air clears the cell)
                        positionMap.remove(packPos(xi, layerY, z));
                        continue;
                    }
                    String blockId = palette.get(ch);
                    if (blockId == null) {
                        throw new IllegalArgumentException(
                                "Line " + (lineNum + 1) + ": undefined palette symbol '" + ch + "'");
                    }
                    if (hasBounds && (xi >= sizeX || layerY < 0 || layerY >= sizeY || z < 0 || z >= sizeZ)) {
                        throw new IllegalArgumentException(
                                "Line " + (lineNum + 1) + ": coordinate (" + xi + "," + layerY + "," + z
                                        + ") out of declared size bounds (" + sizeX + "," + sizeY + "," + sizeZ + ")");
                    }
                    positionMap.put(packPos(xi, layerY, z), blockId);
                }
                currentLayerRow++;
                continue;
            }

            // --- Non-section commands ---

            // name (ignored, informational)
            if (line.startsWith("name ")) continue;

            // axes (ignored, we always use x=east y=up z=south)
            if (line.startsWith("axes ")) continue;

            // origin
            Matcher originMatch = ORIGIN_PATTERN.matcher(line);
            if (originMatch.matches()) {
                // Origin is informational — the actual world-space anchor is provided by
                // BuildTask. We parse but don't use it (build is always relative).
                continue;
            }

            // size
            Matcher sizeMatch = SIZE_PATTERN.matcher(line);
            if (sizeMatch.matches()) {
                sizeX = Integer.parseInt(sizeMatch.group(1));
                sizeY = Integer.parseInt(sizeMatch.group(2));
                sizeZ = Integer.parseInt(sizeMatch.group(3));
                hasBounds = true;
                continue;
            }

            // box
            Matcher boxMatch = BOX_PATTERN.matcher(line);
            if (boxMatch.matches()) {
                int x1 = Integer.parseInt(boxMatch.group(1));
                int y1 = Integer.parseInt(boxMatch.group(2));
                int z1 = Integer.parseInt(boxMatch.group(3));
                int x2 = Integer.parseInt(boxMatch.group(4));
                int y2 = Integer.parseInt(boxMatch.group(5));
                int z2 = Integer.parseInt(boxMatch.group(6));
                char sym = boxMatch.group(7).charAt(0);

                String blockId = palette.get(sym);
                if (blockId == null) {
                    throw new IllegalArgumentException(
                            "Line " + (lineNum + 1) + ": undefined palette symbol '" + sym + "' in box command");
                }

                // Normalize so x1<=x2, y1<=y2, z1<=z2
                int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
                int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
                int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

                if (hasBounds) {
                    if (maxX >= sizeX || maxY >= sizeY || maxZ >= sizeZ || minX < 0 || minY < 0 || minZ < 0) {
                        throw new IllegalArgumentException(
                                "Line " + (lineNum + 1) + ": box coordinates out of declared size bounds");
                    }
                }

                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int x = minX; x <= maxX; x++) {
                            positionMap.put(packPos(x, y, z), blockId);
                        }
                    }
                }
                continue;
            }

            // set
            Matcher setMatch = SET_PATTERN.matcher(line);
            if (setMatch.matches()) {
                int x = Integer.parseInt(setMatch.group(1));
                int y = Integer.parseInt(setMatch.group(2));
                int z = Integer.parseInt(setMatch.group(3));
                char sym = setMatch.group(4).charAt(0);

                String blockId = palette.get(sym);
                if (blockId == null) {
                    throw new IllegalArgumentException(
                            "Line " + (lineNum + 1) + ": undefined palette symbol '" + sym + "' in set command");
                }

                if (hasBounds && (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ)) {
                    throw new IllegalArgumentException(
                            "Line " + (lineNum + 1) + ": set coordinate (" + x + "," + y + "," + z
                                    + ") out of declared size bounds");
                }

                positionMap.put(packPos(x, y, z), blockId);
                continue;
            }

            // layer
            Matcher layerMatch = LAYER_PATTERN.matcher(line);
            if (layerMatch.matches()) {
                layerY = Integer.parseInt(layerMatch.group(1));
                layerZ0 = Integer.parseInt(layerMatch.group(2));
                currentLayerRow = 0;
                inLayer = true;
                continue;
            }

            // Unknown line — skip with warning (lenient parsing for LLM output)
            // Don't throw; LLMs sometimes emit extra commentary lines
        }

        if (inPalette) {
            throw new IllegalArgumentException("Unterminated palette section (missing 'endpalette')");
        }
        if (inLayer) {
            throw new IllegalArgumentException("Unterminated layer section (missing 'endlayer')");
        }

        if (palette.isEmpty()) {
            throw new IllegalArgumentException("No palette defined in VXB-1 input");
        }

        // Convert position map to block entries and compute materials
        List<BlockEntry> blocks = new ArrayList<>();
        Map<String, Integer> materials = new HashMap<>();

        for (Map.Entry<Long, String> entry : positionMap.entrySet()) {
            long packed = entry.getKey();
            String blockId = entry.getValue();
            int[] coords = unpackPos(packed);
            blocks.add(new BlockEntry(coords[0], coords[1], coords[2], blockId));
            materials.merge(blockId, 1, Integer::sum);
        }

        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("VXB-1 parsed successfully but produced no blocks");
        }

        return new BuildStructure(blocks, materials);
    }

    /**
     * Parse a single palette entry like ". = minecraft:air" or "C = cobblestone".
     */
    private static void parsePaletteEntry(String line, Map<Character, String> palette, int lineNum) {
        // Expected: "X = block_id" or "X = minecraft:block_id[state=value]"
        // Flexible: allow varying whitespace around '='
        int eqIdx = line.indexOf('=');
        if (eqIdx < 0) {
            throw new IllegalArgumentException(
                    "Line " + (lineNum + 1) + ": palette entry missing '=': " + line);
        }

        String keyPart = line.substring(0, eqIdx).trim();
        String valuePart = line.substring(eqIdx + 1).trim();

        if (keyPart.length() != 1) {
            throw new IllegalArgumentException(
                    "Line " + (lineNum + 1) + ": palette key must be a single character, got: '" + keyPart + "'");
        }

        if (valuePart.isEmpty()) {
            throw new IllegalArgumentException(
                    "Line " + (lineNum + 1) + ": palette entry has empty block ID");
        }

        char symbol = keyPart.charAt(0);
        String blockId = ensureNamespace(valuePart);

        // Allow air in the palette (it's used by '.' convention but could be mapped to other symbols too)
        palette.put(symbol, blockId);
    }

    /**
     * Strip markdown code fences that LLMs sometimes wrap around their output.
     * Handles ```text, ```vxb, ```, etc.
     */
    private static String stripCodeFences(String input) {
        String s = input.trim();
        // Strip leading fence: ```anything\n
        if (s.startsWith("```")) {
            int newline = s.indexOf('\n');
            if (newline >= 0) {
                s = s.substring(newline + 1);
            }
        }
        // Strip trailing fence: \n```
        if (s.endsWith("```")) {
            int lastNewline = s.lastIndexOf('\n');
            if (lastNewline >= 0 && s.substring(lastNewline + 1).trim().equals("```")) {
                s = s.substring(0, lastNewline);
            }
        }
        return s;
    }

    /**
     * Ensure a block name has the "minecraft:" namespace prefix.
     * Preserves block state syntax like "spruce_log[axis=y]".
     */
    private static String ensureNamespace(String name) {
        if (name.contains(":")) return name;
        return "minecraft:" + name;
    }

    /**
     * Pack 3 ints into a long for HashMap key.
     * Supports coordinates from -1048576 to 1048575 (21 bits each).
     */
    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /**
     * Unpack a long back into [x, y, z] coordinates.
     */
    private static int[] unpackPos(long packed) {
        int x = (int) ((packed >> 42) & 0x1FFFFF);
        int y = (int) ((packed >> 21) & 0x1FFFFF);
        int z = (int) (packed & 0x1FFFFF);
        // Sign-extend from 21 bits
        if ((x & 0x100000) != 0) x |= ~0x1FFFFF;
        if ((y & 0x100000) != 0) y |= ~0x1FFFFF;
        if ((z & 0x100000) != 0) z |= ~0x1FFFFF;
        return new int[]{x, y, z};
    }
}
