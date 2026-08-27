package com.assistantbot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistantbot.llm.BuildStructure.PlacementGroup;
import java.util.List;
import org.junit.jupiter.api.Test;

class Vxb11CompilerTest {
    @Test
    void semanticDoorOverwritesWallAndStaysAtomic() {
        VxbCompiler.Compilation compilation = VxbCompiler.compile("""
                VXB-1.1
                name door_test
                size 3 4 3
                axes x=east y=up z=south
                allow_floating false
                palette
                S = stone
                endpalette
                box 0 0 0 2 0 2 S
                box 0 1 2 2 2 2 S
                features
                door 1 1 2 spruce outside=south
                endfeatures
                """);

        BuildStructure structure = compilation.structure();
        assertEquals(2, structure.getFeatureGroups().getFirst().blocks().size());
        assertTrue(structure.getFeatureGroups().getFirst().atomic());
        assertTrue(structure.getBlocks().stream().anyMatch(block -> block.x() == 1 && block.y() == 1
                && block.z() == 2 && block.blockId().contains("spruce_door[half=lower")));
        List<PlacementGroup> operations = BuildStructure.planPlacementGroups(structure);
        assertTrue(operations.getLast().atomic());
        assertTrue(VxbPreviewRenderer.renderPngDataUrl(structure).startsWith("data:image/png;base64,"));
    }

    @Test
    void featureCollisionIsACompilerBlocker() {
        VxbCompiler.CompilationException error = assertThrows(VxbCompiler.CompilationException.class, () ->
                VxbCompiler.compile("""
                        VXB-1.1
                        name collision
                        size 3 4 3
                        palette
                        S = stone
                        endpalette
                        box 0 0 0 2 0 2 S
                        features
                        door 1 1 2 spruce outside=south
                        lantern 1 2 2 hanging=false
                        endfeatures
                        """));
        assertTrue(error.getMessage().contains("Feature collision"));
    }

    @Test
    void explicitLayerUsesLocalWidthDepthAndOffsets() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-1.1
                name local_layer
                size 5 2 4
                palette
                S = stone
                endpalette
                box 0 0 0 4 0 3 S
                layer y 1 x 1 z 1 w 3 d 2
                S.S
                .S.
                endlayer
                """);
        assertTrue(structure.getBlocks().stream().anyMatch(b -> b.x() == 1 && b.y() == 1 && b.z() == 1));
        assertTrue(structure.getBlocks().stream().anyMatch(b -> b.x() == 3 && b.y() == 1 && b.z() == 1));
        assertTrue(structure.getBlocks().stream().noneMatch(b -> b.x() == 0 && b.y() == 1));
    }

    @Test
    void ungroundedComponentIsRejectedUnlessIntentional() {
        VxbCompiler.CompilationException error = assertThrows(VxbCompiler.CompilationException.class, () ->
                VxbCompiler.compile("""
                        VXB-1.1
                        name floating
                        size 2 3 2
                        palette
                        S = stone
                        endpalette
                        set 1 2 1 S
                        """));
        assertTrue(error.getMessage().contains("Ungrounded Structure Component"));

        VxbCompiler.Compilation accepted = VxbCompiler.compile("""
                VXB-1.1
                name floating
                size 2 3 2
                allow_floating true
                palette
                S = stone
                endpalette
                set 1 2 1 S
                """);
        assertEquals(1, accepted.structure().getBlocks().size());
    }

    @Test
    void invalidStatePropertyIsRejectedBeforeBuild() {
        VxbCompiler.CompilationException error = assertThrows(VxbCompiler.CompilationException.class, () ->
                VxbCompiler.compile("""
                        VXB-1
                        size 1 1 1
                        palette
                        S = oak_stairs[facing=upside_down]
                        endpalette
                        set 0 0 0 S
                        """));
        assertTrue(error.getMessage().contains("Invalid Minecraft Block State"));
    }

    @Test
    void patchChangesOnlyAddressedLines() {
        String source = "VXB-1.1\nname old\nsize 1 1 1";
        String result = VxbPatcher.apply(source, """
                VXP-1
                replace-line 2 name new
                insert-after 3 allow_floating false
                end
                """);
        assertEquals("VXB-1.1\nname new\nsize 1 1 1\nallow_floating false", result);
    }
}
