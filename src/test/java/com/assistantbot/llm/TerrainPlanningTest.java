package com.assistantbot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TerrainPlanningTest {
    @Test
    void preserveModeRetainsOnlyExplicitAirAsCarveCells() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-1.1
                size 3 1 1
                terrain_mode preserve
                palette
                S = stone
                A = air
                endpalette
                box 0 0 0 2 0 0 S
                set 1 0 0 A
                """);

        assertTrue(structure.shouldPreserveTerrain());
        assertEquals(2, structure.getBlocks().size());
        assertEquals(1, structure.getClearCells().size());
        assertTrue(structure.getClearCells().contains(new BuildStructure.Cell(1, 0, 0)));
    }

    @Test
    void layerDotsRemovePlanGeometryWithoutCarvingPreservedTerrain() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-1.1
                size 3 1 1
                terrain_mode preserve
                palette
                S = stone
                endpalette
                box 0 0 0 2 0 0 S
                layer y 0 z 0
                S.S
                endlayer
                """);

        assertEquals(2, structure.getBlocks().size());
        assertTrue(structure.getClearCells().isEmpty());
    }

    @Test
    void preserveModeAllowsExcavationOnlyPlans() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-1.1
                size 3 3 3
                terrain_mode preserve
                palette
                A = air
                endpalette
                box 0 0 0 2 2 2 A
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
