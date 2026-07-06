package com.assistantbot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildStructureTest {
    @Test
    void parseNormalizesPaletteIdsAndCountsBoxMaterials() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-1
                size 3 2 1
                palette
                S = stone
                G = minecraft:glass
                endpalette
                box 0 0 0 1 1 0 S
                set 2 0 0 G
                """);

        assertEquals(5, structure.getBlocks().size());
        assertEquals(4, structure.getMaterials().get("minecraft:stone"));
        assertEquals(1, structure.getMaterials().get("minecraft:glass"));
        assertTrue(structure.getUniqueBlockIds().contains("minecraft:stone"));
    }

    @Test
    void layerRangesDuplicateRowsAndAirClearsPriorBlocks() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-1
                size 3 3 2
                palette
                S = stone
                W = oak_planks
                endpalette
                box 0 0 0 2 0 0 S
                layer y 0-1 z 0
                W.W
                .W.
                endlayer
                """);

        Map<String, String> blocks = byPosition(structure.getBlocks());
        assertEquals("minecraft:oak_planks", blocks.get("0,0,0"));
        assertEquals("minecraft:oak_planks", blocks.get("2,0,0"));
        assertEquals("minecraft:oak_planks", blocks.get("1,0,1"));
        assertEquals("minecraft:oak_planks", blocks.get("0,1,0"));
        assertEquals("minecraft:oak_planks", blocks.get("2,1,0"));
        assertEquals("minecraft:oak_planks", blocks.get("1,1,1"));
        assertTrue(!blocks.containsKey("1,0,0"), "air in later layer should clear prior box block");
        assertEquals(6, blocks.size());
    }

    @Test
    void parseStripsMarkdownFencesAndNormalizesUnicodeDashRanges() {
        BuildStructure structure = BuildStructure.parse("""
                ```vxb
                VXB-1
                size 1 2 1
                palette
                S = stone
                endpalette
                layer y 0\u20131 z 0
                S
                endlayer
                ```
                """);

        assertEquals(2, structure.getBlocks().size());
        assertEquals(2, structure.getMaterials().get("minecraft:stone"));
    }

    @Test
    void parseRejectsUndefinedPaletteSymbols() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                BuildStructure.parse("""
                        VXB-1
                        palette
                        S = stone
                        endpalette
                        set 0 0 0 W
                        """));

        assertTrue(error.getMessage().contains("undefined palette symbol"));
    }

    @Test
    void sortBlocksBfsPlacesGroundedConnectedBlocksBeforeOrphans() {
        List<BlockEntry> sorted = BuildStructure.sortBlocksBFS(List.of(
                new BlockEntry(4, 4, 4, "minecraft:glass"),
                new BlockEntry(0, 1, 0, "minecraft:stone"),
                new BlockEntry(0, 0, 0, "minecraft:stone"),
                new BlockEntry(0, 2, 0, "minecraft:stone")));

        assertEquals(new BlockEntry(0, 0, 0, "minecraft:stone"), sorted.get(0));
        assertEquals(new BlockEntry(0, 1, 0, "minecraft:stone"), sorted.get(1));
        assertEquals(new BlockEntry(0, 2, 0, "minecraft:stone"), sorted.get(2));
        assertEquals(new BlockEntry(4, 4, 4, "minecraft:glass"), sorted.get(3));
    }

    private static Map<String, String> byPosition(List<BlockEntry> blocks) {
        Map<String, String> result = new HashMap<>();
        for (BlockEntry block : blocks) {
            result.put(block.x() + "," + block.y() + "," + block.z(), block.blockId());
        }
        return result;
    }
}
