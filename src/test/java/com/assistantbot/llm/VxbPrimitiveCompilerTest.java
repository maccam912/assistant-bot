package com.assistantbot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class VxbPrimitiveCompilerTest {
    @Test
    void expandsSolidAndHollowSpheres() {
        BuildStructure solid = parse("size 7 7 7", "sphere 3 3 3 2 S");
        BuildStructure hollow = parse("size 7 7 7", "sphere 3 3 3 2 S hollow=true");

        assertEquals(33, solid.getBlocks().size());
        assertEquals(26, hollow.getBlocks().size());
        assertTrue(solid.getBlocks().stream().anyMatch(b -> b.x() == 3 && b.y() == 3 && b.z() == 3));
        assertTrue(hollow.getBlocks().stream().noneMatch(b -> b.x() == 3 && b.y() == 3 && b.z() == 3));
    }

    @Test
    void expandsCylinderConePyramidTriangleAndEllipsoid() {
        assertEquals(15, parse("size 5 3 5", "cylinder 2 0 2 1 3 S").getBlocks().size());
        assertEquals(13, parse("size 5 3 5", "cone 2 0 2 2 3 S hollow=true cap=false").getBlocks().size());
        assertEquals(35, parse("size 5 3 5", "pyramid 2 0 2 2 3 S").getBlocks().size());
        assertEquals(18, parse("size 5 3 2", "triangle 0 0 0 5 3 2 S axis=x").getBlocks().size());
        assertEquals(9, parse("size 5 3 3", "ellipsoid 2 1 1 2 1 1 S").getBlocks().size());
    }

    @Test
    void staircaseCreatesDirectionalStairsAndConnectedSolidSupport() {
        BuildStructure structure = parse("size 5 4 4",
                "staircase 1 0 1 2 3 spruce up=east fill=S");
        Map<String, String> blocks = structure.getBlocks().stream().collect(Collectors.toMap(
                b -> b.x() + "," + b.y() + "," + b.z(), BuildStructure.BlockEntry::blockId));

        assertEquals(12, blocks.size());
        assertTrue(blocks.get("3,2,1").contains("spruce_stairs[facing=east"));
        assertEquals("minecraft:stone", blocks.get("3,1,1"));
    }

    @Test
    void rejectsUnknownSymbolsAndInvalidOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("size 3 3 3", "sphere 1 1 1 1 X"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("size 3 3 3", "cylinder 1 0 1 1 3 S hollow=maybe"));
    }

    private static BuildStructure parse(String size, String command) {
        return BuildStructure.parse("""
                VXB-1.1
                %s
                palette
                S = stone
                endpalette
                %s
                """.formatted(size, command));
    }
}
