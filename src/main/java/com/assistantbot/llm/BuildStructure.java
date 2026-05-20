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
    private VxbDiagnostics.DiagnosticResult diagnostics;

    public BuildStructure(List<BlockEntry> blocks, Map<String, Integer> materials) {
        this.blocks = blocks;
        this.materials = materials;
        this.diagnostics = new VxbDiagnostics.DiagnosticResult();
    }

    public List<BlockEntry> getBlocks() { return blocks; }
    public Map<String, Integer> getMaterials() { return materials; }
    public VxbDiagnostics.DiagnosticResult getDiagnostics() { return diagnostics; }
    public void setDiagnostics(VxbDiagnostics.DiagnosticResult diagnostics) { this.diagnostics = diagnostics; }

    /**
     * Returns all unique block IDs used in the structure.
     */
    public Set<String> getUniqueBlockIds() {
        Set<String> ids = new HashSet<>();
        for (BlockEntry entry : blocks) {
            ids.add(entry.blockId());
        }
        return ids;
    }

    /**
     * Replaces all occurrences of a block ID with a new one.
     * Since BlockEntry is a record (immutable), this rebuilds
     * affected entries in the list.
     */
    public void replaceBlockId(String oldId, String newId) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockEntry entry = blocks.get(i);
            if (entry.blockId().equals(oldId)) {
                blocks.set(i, new BlockEntry(entry.x(), entry.y(), entry.z(), newId));
            }
        }
        // Update materials map
        if (materials.containsKey(oldId)) {
            int count = materials.remove(oldId);
            materials.merge(newId, count, Integer::sum);
        }
    }

    // --- VXB-1 Parser ---

    private static final Pattern BOX_PATTERN = Pattern.compile(
            "^box\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(\\S)$");
    private static final Pattern SET_PATTERN = Pattern.compile(
            "^set\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(\\S)$");
    private static final Pattern LAYER_PATTERN = Pattern.compile(
            "^layer\\s+y\\s+(-?\\d+)(?:\\s*-\\s*(-?\\d+))?\\s+z\\s+(-?\\d+)$");
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
     *   <li>{@code layer y Y1-Y2 z Z0}/{@code endlayer} — 2D grid duplicated across Y range</li>
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

        // Normalize Unicode dash variants (en dash, em dash, minus sign) to hyphen-minus.
        // Some LLMs (e.g., Gemini Flash) emit these in range syntax like "layer y 1–12".
        cleaned = normalizeDashes(cleaned);

        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Empty VXB-1 input");
        }

        String[] lines = cleaned.split("\\r?\\n");

        // Validate header (skip potential junk before VXB-1)
        int headerLineIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.equals("VXB-1")) {
                headerLineIdx = i;
                break;
            }
            // If we find something else first, it's not a valid VXB-1
            break; 
        }

        if (headerLineIdx == -1) {
            throw new IllegalArgumentException(
                    "Missing VXB-1 header. First non-comment line must be 'VXB-1'.");
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
        int layerYMin = 0;
        int layerYMax = 0; // inclusive; same as layerYMin for single-Y layers
        int layerZ0 = 0;
        int currentLayerRow = 0;

        for (int lineNum = headerLineIdx + 1; lineNum < lines.length; lineNum++) {
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
                // Applied to all Y values in the range [layerYMin, layerYMax]
                int z = layerZ0 + currentLayerRow;
                for (int yi = layerYMin; yi <= layerYMax; yi++) {
                    // Lenient row length: use sizeX if available, otherwise use line length
                    int cols = (hasBounds) ? Math.min(line.length(), sizeX) : line.length();
                    for (int xi = 0; xi < cols; xi++) {
                        char ch = line.charAt(xi);
                        if (ch == '.' || ch == '-' || ch == ' ') {
                            // Air — remove any previously placed block at this position
                            // (last-write-wins: explicitly writing air clears the cell)
                            positionMap.remove(packPos(xi, yi, z));
                            continue;
                        }
                        String blockId = palette.get(ch);
                        if (blockId == null) {
                            // Last-ditch effort: if it's an unrecognized symbol, treat as air rather than crashing
                            positionMap.remove(packPos(xi, yi, z));
                            continue;
                        }
                        if (hasBounds && (xi >= sizeX || yi < 0 || yi >= sizeY || z < 0 || z >= sizeZ)) {
                            // Skip out of bounds but don't crash
                            continue;
                        }
                        positionMap.put(packPos(xi, yi, z), blockId);
                    }
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

            // layer (single Y or Y range)
            Matcher layerMatch = LAYER_PATTERN.matcher(line);
            if (layerMatch.matches()) {
                int y1 = Integer.parseInt(layerMatch.group(1));
                String y2Str = layerMatch.group(2); // null if single Y
                int y2 = (y2Str != null) ? Integer.parseInt(y2Str) : y1;
                layerYMin = Math.min(y1, y2);
                layerYMax = Math.max(y1, y2);
                layerZ0 = Integer.parseInt(layerMatch.group(3));
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
            // Skip air blocks — the build volume is already cleared before placing,
            // so air entries just waste time (navigate, fail, retry, skip).
            if (blockId.equals("minecraft:air") || blockId.equals("air")) {
                continue;
            }
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
     * Normalize Unicode dash-like characters to ASCII hyphen-minus.
     * LLMs sometimes emit en dash (U+2013), em dash (U+2014), or
     * minus sign (U+2212) instead of hyphen-minus (U+002D).
     */
    private static String normalizeDashes(String input) {
        return input.replace('\u2013', '-')  // en dash
                    .replace('\u2014', '-')  // em dash
                    .replace('\u2212', '-'); // minus sign
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

    // --- BFS sort for realistic placement order ---

    /**
     * Sort blocks so each one is placed only when it has a face-adjacent
     * neighbor that's already placed (or is on the ground, y=0).
     *
     * This gives the visual appearance of building with structural support:
     * ground layer first, then blocks connected to already-placed blocks.
     * Orphan blocks (floating, no path to ground) are appended at the end
     * sorted by Y ascending.
     */
    public static List<BlockEntry> sortBlocksBFS(List<BlockEntry> blocks) {
        Map<Long, BlockEntry> posMap = new HashMap<>();
        for (BlockEntry entry : blocks) {
            posMap.put(packPos(entry.x(), entry.y(), entry.z()), entry);
        }

        Set<Long> placed = new HashSet<>();
        List<BlockEntry> sorted = new ArrayList<>();
        Queue<BlockEntry> ready = new LinkedList<>();

        for (BlockEntry entry : blocks) {
            if (entry.y() == 0) {
                long key = packPos(entry.x(), entry.y(), entry.z());
                if (placed.add(key)) {
                    ready.add(entry);
                }
            }
        }

        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        while (!ready.isEmpty()) {
            BlockEntry current = ready.poll();
            sorted.add(current);

            for (int[] dir : directions) {
                int nx = current.x() + dir[0];
                int ny = current.y() + dir[1];
                int nz = current.z() + dir[2];
                long nKey = packPos(nx, ny, nz);

                if (posMap.containsKey(nKey) && placed.add(nKey)) {
                    ready.add(posMap.get(nKey));
                }
            }
        }

        List<BlockEntry> orphans = new ArrayList<>();
        for (BlockEntry entry : blocks) {
            long key = packPos(entry.x(), entry.y(), entry.z());
            if (!placed.contains(key)) {
                orphans.add(entry);
            }
        }
        orphans.sort(Comparator.comparingInt(BlockEntry::y)
                .thenComparingInt(BlockEntry::x)
                .thenComparingInt(BlockEntry::z));
        sorted.addAll(orphans);

        return sorted;
    }
}
