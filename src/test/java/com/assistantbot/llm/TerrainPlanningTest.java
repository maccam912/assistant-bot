package com.assistantbot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerrainPlanningTest {
    @Test
    void keepModeCarvesOnlyTheCellsDrawnAsAir() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 3 1 1
                terrain keep
                pal
                S stone
                end
                plan y=0
                S.S
                """);

        assertTrue(structure.shouldPreserveTerrain());
        assertEquals(2, structure.getBlocks().size());
        assertEquals(1, structure.getClearCells().size());
        assertTrue(structure.getClearCells().contains(new BuildStructure.Cell(1, 0, 0)));
    }

    @Test
    void anAirPaletteSymbolCarvesJustLikeADot() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 3 1 1
                terrain keep
                pal
                S stone
                A air
                end
                plan y=0
                SAS
                """);

        assertEquals(2, structure.getBlocks().size());
        assertTrue(structure.getClearCells().contains(new BuildStructure.Cell(1, 0, 0)));
    }

    @Test
    void keepModeAllowsExcavationOnlyPlans() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 3 3 3
                terrain keep
                pal
                A air
                end
                plan y=0..2
                AAA
                AAA
                AAA
                """);

        assertTrue(structure.getBlocks().isEmpty());
        assertEquals(27, structure.getClearCells().size());
    }

    @Test
    void userMessageIncludesTerrainWhenAvailable() {
        String message = LlmClient.buildUserMessage("a cliff house", "surface_height_rows:\n0 4");
        assertTrue(message.contains("a cliff house"));
        assertTrue(message.contains("surface_height_rows"));
        assertFalse(LlmClient.buildUserMessage("hut", "").contains("SITE TERRAIN"));
    }
}
