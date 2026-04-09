package com.assistantbot.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a structure returned by the LLM: a list of block entries
 * with relative coordinates. Supports parsing from two compact JSON formats
 * (tuple array and layered grid), matching the third-principles-bot formats.
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

    /**
     * Parse an LLM JSON response into a BuildStructure. Auto-detects format:
     * - Tuple format: has "b" key → {"p":[...],"b":[[x,y,z,i],...]}
     * - Grid format: has "l" key → {"p":{...},"l":[...],"offset":[...]}
     *
     * @throws IllegalArgumentException if the JSON is malformed or unrecognized
     */
    public static BuildStructure parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }

        if (root.has("l")) {
            return parseGridFormat(root);
        } else if (root.has("b")) {
            return parseTupleFormat(root);
        } else {
            throw new IllegalArgumentException(
                    "Unrecognized format: expected \"b\" key (tuple array) or \"l\" key (layered grids)");
        }
    }

    /**
     * Parse tuple format: {"p":["dirt","oak_planks"],"b":[[x,y,z,i],...]}
     */
    private static BuildStructure parseTupleFormat(JsonObject root) {
        JsonArray palette = root.getAsJsonArray("p");
        JsonArray blockArray = root.getAsJsonArray("b");

        if (palette == null || blockArray == null) {
            throw new IllegalArgumentException("Tuple format requires both \"p\" and \"b\" arrays");
        }

        List<String> paletteList = new ArrayList<>();
        for (JsonElement e : palette) {
            paletteList.add(e.getAsString());
        }

        List<BlockEntry> blocks = new ArrayList<>();
        Map<String, Integer> materials = new HashMap<>();

        for (JsonElement e : blockArray) {
            JsonArray tuple = e.getAsJsonArray();
            if (tuple.size() != 4) {
                throw new IllegalArgumentException(
                        "Tuple entry must have 4 elements [x,y,z,i], got " + tuple.size());
            }

            int x = tuple.get(0).getAsInt();
            int y = tuple.get(1).getAsInt();
            int z = tuple.get(2).getAsInt();
            int idx = tuple.get(3).getAsInt();

            if (idx < 0 || idx >= paletteList.size()) {
                throw new IllegalArgumentException(
                        "Palette index " + idx + " out of range (palette has " + paletteList.size() + " entries)");
            }

            String blockId = ensureNamespace(paletteList.get(idx));
            blocks.add(new BlockEntry(x, y, z, blockId));
            materials.merge(blockId, 1, Integer::sum);
        }

        return new BuildStructure(blocks, materials);
    }

    /**
     * Parse grid format: {"p":{"a":"dirt"},"l":["row0/row1",...],"offset":[ox,oy,oz]}
     */
    private static BuildStructure parseGridFormat(JsonObject root) {
        JsonObject palette = root.getAsJsonObject("p");
        JsonArray layers = root.getAsJsonArray("l");

        if (palette == null || layers == null) {
            throw new IllegalArgumentException("Grid format requires both \"p\" and \"l\" fields");
        }

        // Parse offset (defaults to [0,0,0])
        int ox = 0, oy = 0, oz = 0;
        if (root.has("offset")) {
            JsonArray offset = root.getAsJsonArray("offset");
            if (offset.size() >= 3) {
                ox = offset.get(0).getAsInt();
                oy = offset.get(1).getAsInt();
                oz = offset.get(2).getAsInt();
            }
        }

        // Build palette map: single char -> block id
        Map<Character, String> paletteMap = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : palette.entrySet()) {
            if (entry.getKey().length() != 1) {
                throw new IllegalArgumentException(
                        "Grid palette keys must be single characters, got: \"" + entry.getKey() + "\"");
            }
            paletteMap.put(entry.getKey().charAt(0), ensureNamespace(entry.getValue().getAsString()));
        }

        List<BlockEntry> blocks = new ArrayList<>();
        Map<String, Integer> materials = new HashMap<>();

        for (int yi = 0; yi < layers.size(); yi++) {
            int y = yi + oy;
            String layerStr = layers.get(yi).getAsString();
            String[] rows = layerStr.split("/", -1);

            for (int zi = 0; zi < rows.length; zi++) {
                int z = zi + oz;
                String row = rows[zi];

                for (int xi = 0; xi < row.length(); xi++) {
                    char ch = row.charAt(xi);
                    if (ch == '.') continue; // air gap

                    int x = xi + ox;
                    String blockId = paletteMap.get(ch);
                    if (blockId == null) {
                        throw new IllegalArgumentException(
                                "Palette character '" + ch + "' not defined in palette");
                    }

                    blocks.add(new BlockEntry(x, y, z, blockId));
                    materials.merge(blockId, 1, Integer::sum);
                }
            }
        }

        return new BuildStructure(blocks, materials);
    }

    /**
     * Ensure a block name has the "minecraft:" namespace prefix.
     */
    private static String ensureNamespace(String name) {
        if (name.contains(":")) return name;
        return "minecraft:" + name;
    }
}
