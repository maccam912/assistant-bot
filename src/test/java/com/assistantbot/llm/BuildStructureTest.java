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
    void paletteIdsAreNamespacedAndMaterialsCounted() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 3 2 1
                pal
                S stone
                G minecraft:glass
                end
                plan y=0
                SSG
                plan y=1
                SS.
                """);

        assertEquals(5, structure.getBlocks().size());
        assertEquals(4, structure.getMaterials().get("minecraft:stone"));
        assertEquals(1, structure.getMaterials().get("minecraft:glass"));
        assertTrue(structure.getUniqueBlockIds().contains("minecraft:stone"));
    }

    @Test
    void aPlaneRangeDrawsIdenticalLevelsOnce() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 3 3 2
                pal
                W oak_planks
                end
                plan y=0..1
                W.W
                .W.
                """);

        Map<String, String> blocks = byPosition(structure.getBlocks());
        assertEquals("minecraft:oak_planks", blocks.get("0,0,0"));
        assertEquals("minecraft:oak_planks", blocks.get("2,0,0"));
        assertEquals("minecraft:oak_planks", blocks.get("1,0,1"));
        assertEquals("minecraft:oak_planks", blocks.get("0,1,0"));
        assertEquals("minecraft:oak_planks", blocks.get("2,1,0"));
        assertEquals("minecraft:oak_planks", blocks.get("1,1,1"));
        assertTrue(!blocks.containsKey("1,0,0"), "'.' is air, not a hole in the drawing");
        assertEquals(6, blocks.size());
    }

    @Test
    void parseStripsMarkdownFencesAndNormalizesUnicodeDashRanges() {
        BuildStructure structure = BuildStructure.parse("""
                ```vxb
                VXB-2
                size 1 2 1
                pal
                S stone
                end
                plan y=0\u20131
                S
                ```
                """);

        assertEquals(2, structure.getBlocks().size());
        assertEquals(2, structure.getMaterials().get("minecraft:stone"));
    }

    @Test
    void parseRejectsUndefinedPaletteSymbols() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                BuildStructure.parse("""
                        VXB-2
                        size 1 1 1
                        pal
                        S stone
                        end
                        plan y=0
                        W
                        """));

        assertTrue(error.getMessage().contains("Symbol 'W' is not in the palette"), error.getMessage());
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

    @Test
    void sortBlocksBfsDefersFinishingBlocksUntilAfterEntireStructure() {
        BlockEntry glass = new BlockEntry(0, 1, 0, "minecraft:glass_pane");
        BlockEntry fence = new BlockEntry(1, 0, 0, "oak_fence");
        BlockEntry stair = new BlockEntry(2, 2, 0, "minecraft:oak_stairs[facing=east]");
        BlockEntry highOrphan = new BlockEntry(5, 5, 5, "minecraft:stone");

        List<BlockEntry> sorted = BuildStructure.sortBlocksBFS(List.of(
                glass,
                fence,
                stair,
                highOrphan,
                new BlockEntry(0, 0, 0, "minecraft:stone")));

        assertTrue(sorted.indexOf(highOrphan) < sorted.indexOf(glass));
        assertTrue(sorted.indexOf(highOrphan) < sorted.indexOf(fence));
        assertTrue(sorted.indexOf(highOrphan) < sorted.indexOf(stair));
    }

    @Test
    void sortBlocksBfsPlacesDoorHalvesTogetherAtTheVeryEnd() {
        BlockEntry lowerDoor = new BlockEntry(
                2, 1, 4, "minecraft:spruce_door[half=lower,facing=south]");
        BlockEntry upperDoor = new BlockEntry(
                2, 2, 4, "minecraft:spruce_door[half=upper,facing=south]");
        BlockEntry window = new BlockEntry(0, 1, 0, "minecraft:glass_pane");

        List<BlockEntry> sorted = BuildStructure.sortBlocksBFS(List.of(
                upperDoor,
                window,
                new BlockEntry(0, 0, 0, "minecraft:stone"),
                lowerDoor));

        assertEquals(window, sorted.get(sorted.size() - 3));
        assertEquals(lowerDoor, sorted.get(sorted.size() - 2));
        assertEquals(upperDoor, sorted.get(sorted.size() - 1));
    }

    private static Map<String, String> byPosition(List<BlockEntry> blocks) {
        Map<String, String> result = new HashMap<>();
        for (BlockEntry block : blocks) {
            result.put(block.x() + "," + block.y() + "," + block.z(), block.blockId());
        }
        return result;
    }
}
