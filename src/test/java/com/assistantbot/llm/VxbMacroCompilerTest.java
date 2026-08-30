package com.assistantbot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VxbMacroCompilerTest {
    @Test
    void reusesAndRotatesAHouseWithAtomicFeatures() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-1.1
                size 10 4 10
                palette
                S = stone
                endpalette
                macro hut size 3 3 2
                box 0 0 0 2 0 1 S
                set 0 1 0 S
                features
                door 1 1 1 spruce outside=south hinge=left
                endfeatures
                endmacro
                use hut at 0 0 0
                use hut at 5 0 0 rotate=90 mirror=x
                """);

        assertEquals(18, structure.getBlocks().size());
        assertEquals(2, structure.getFeatureGroups().size());
        assertTrue(structure.getBlocks().stream().anyMatch(block -> block.x() == 1 && block.z() == 1
                && block.blockId().contains("facing=south") && block.blockId().contains("hinge=left")));
        assertTrue(structure.getBlocks().stream().anyMatch(block -> block.x() == 6 && block.z() == 1
                && block.blockId().contains("facing=east") && block.blockId().contains("hinge=right")));
        assertTrue(structure.getFeatureGroups().stream().allMatch(group -> group.atomic()));
    }

    @Test
    void rotatesDirectionalAxisStates() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-1.1
                size 3 2 3
                palette
                L = oak_log[axis=x]
                endpalette
                macro beam size 2 1 1
                box 0 0 0 1 0 0 L
                endmacro
                use beam at 0 0 0 rotate=90
                """);

        assertTrue(structure.getBlocks().stream().allMatch(block -> block.blockId().contains("axis=z")));
        assertTrue(structure.getBlocks().stream().anyMatch(block -> block.x() == 0 && block.z() == 1));
    }
}
