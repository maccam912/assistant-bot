package com.assistantbot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.BuildStructure.Cell;
import com.assistantbot.llm.BuildStructure.PlacementGroup;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class Vxb2CompilerTest {

    /** The worked example shipped in the model prompt. If this breaks, the prompt lies. */
    static final String CABIN = """
            VXB-2
            name spruce_cabin
            size 9 7 7

            pal
            C cobblestone
            P spruce_planks
            L spruce_log
            G glass_pane
            ^ spruce_stairs
            D spruce_door
            b red_bed
            * torch
            end

            plan y=0
            z0 CCCCCCCCC
            z1 CCCCCCCCC
            z2 CCCCCCCCC
            z3 CCCCCCCCC
            z4 CCCCCCCCC
            z5 CCCCCCCCC
            z6 CCCCCCCCC

            face south z=6 y=1..6
            y6 .........
            y5 .........
            y4 ^^^^^^^^^
            y3 LPPPPPPPL
            y2 LGGPDPGGL
            y1 LPPPDPPPL

            face south z=0 y=1..6
            y6 .........
            y5 .........
            y4 ^^^^^^^^^
            y3 LPPPPPPPL
            y2 LGGPPPGGL
            y1 LPPPPPPPL

            face west x=0 y=1..6
            y6 ..PPP..
            y5 .^PPP^.
            y4 ^PPPPP^
            y3 LPPPPPL
            y2 LGGPGGL
            y1 LPPPPPL

            face west x=8 y=1..6
            y6 ..PPP..
            y5 .^PPP^.
            y4 ^PPPPP^
            y3 LPPPPPL
            y2 LGGPGGL
            y1 LPPPPPL

            plan y=5 x=1..7 z=1
            z1 ^^^^^^^

            plan y=5 x=1..7 z=5
            z5 ^^^^^^^

            plan y=6 x=1..7 z=2..4
            z2 PPPPPPP
            z3 PPPPPPP
            z4 PPPPPPP

            plan y=1 x=4 z=1..2
            z1 b
            z2 b

            plan y=2 x=3 z=1
            z1 *
            """;

    @Test
    void promptExampleCompilesAndInfersEveryBlockState() {
        VxbCompiler.Compilation compilation = VxbCompiler.compile(CABIN);
        BuildStructure structure = compilation.structure();
        assertFalse(compilation.diagnostics().hasBlockers());

        // The door was drawn as two stacked glyphs and becomes one atomic two-block group
        // facing the side the exterior flood fill reached.
        PlacementGroup door = group(structure, "door");
        assertEquals(2, door.blocks().size());
        assertTrue(door.atomic());
        assertTrue(door.blocks().getFirst().blockId().contains("facing=south"), door.blocks().getFirst().blockId());

        // The bed's head is placed against the wall it was drawn beside.
        PlacementGroup bed = group(structure, "bed");
        assertTrue(bed.blocks().stream().anyMatch(b -> b.blockId().contains("part=head") && b.z() == 1));
        assertTrue(bed.blocks().stream().allMatch(b -> b.blockId().contains("facing=north")));

        // A torch drawn off the floor becomes a wall torch pointing away from its support.
        assertEquals("minecraft:wall_torch[facing=south]", at(structure, 3, 2, 1).blockId());

        // Roof stairs slope toward the ridge rather than defaulting to north.
        assertTrue(at(structure, 4, 4, 0).blockId().contains("facing=south"), at(structure, 4, 4, 0).blockId());
        assertTrue(at(structure, 4, 4, 6).blockId().contains("facing=north"), at(structure, 4, 4, 6).blockId());

        // Vertical corner logs are recognised as pillars.
        assertEquals("minecraft:spruce_log[axis=y]", at(structure, 0, 1, 0).blockId());
    }

    @Test
    void facesReadFromOutsideSoAsymmetricDetailLandsWhereItWasDrawn() {
        // The north and east faces run backwards along their axis because that is how
        // they look when you stand in front of them. Anything symmetric hides a mistake
        // here, so this fixture is deliberately off-centre.
        String tower = """
                VXB-2
                size 5 4 5
                pal
                S stone
                G glass
                end
                plan y=0
                SSSSS
                SSSSS
                SSSSS
                SSSSS
                SSSSS
                face north z=0 y=1..3
                y3 SSSSS
                y2 SSSGS
                y1 SSSSS
                face east x=4 y=1..3
                y3 SSSSS
                y2 SSSGS
                y1 SSSSS
                """;
        BuildStructure structure = BuildStructure.parse(tower);
        assertEquals("minecraft:glass", at(structure, 1, 2, 0).blockId());
        assertEquals("minecraft:glass", at(structure, 4, 2, 1).blockId());
    }

    @Test
    void disagreeingViewsAreReportedWithBothGlyphs() {
        VxbCompiler.CompilationException error = assertThrows(VxbCompiler.CompilationException.class,
                () -> VxbCompiler.compile("""
                        VXB-2
                        size 3 2 3
                        pal
                        S stone
                        P oak_planks
                        end
                        plan y=0
                        SSS
                        SSS
                        SSS
                        plan y=1
                        P..
                        ...
                        ...
                        face south z=0 y=1
                        y1 S..
                        """));
        assertTrue(error.getMessage().contains("Views disagree at (0,1,0)"), error.getMessage());
    }

    @Test
    void rowWidthMismatchNamesTheRowAndTheExpectedWidth() {
        VxbCompiler.CompilationException error = assertThrows(VxbCompiler.CompilationException.class,
                () -> VxbCompiler.compile("""
                        VXB-2
                        size 4 1 2
                        pal
                        S stone
                        end
                        plan y=0
                        SSSS
                        SSS
                        """));
        assertTrue(error.getMessage().contains("exactly 4 characters wide but is 3"), error.getMessage());
    }

    @Test
    void rowLabelsAreCheckedAgainstTheCoordinateTheyClaim() {
        VxbCompiler.CompilationException error = assertThrows(VxbCompiler.CompilationException.class,
                () -> VxbCompiler.compile("""
                        VXB-2
                        size 2 1 2
                        pal
                        S stone
                        end
                        plan y=0
                        z0 SS
                        z2 SS
                        """));
        assertTrue(error.getMessage().contains("does not match this row's coordinate z=1"), error.getMessage());
    }

    @Test
    void aMissingRowIsReportedWhereTheSliceRunsOutRatherThanFurtherDown() {
        VxbCompiler.CompilationException error = assertThrows(VxbCompiler.CompilationException.class,
                () -> VxbCompiler.compile("""
                        VXB-2
                        size 2 2 2
                        pal
                        S stone
                        end
                        plan y=0
                        SS
                        plan y=1
                        SS
                        SS
                        """));
        assertTrue(error.getMessage().contains("began a new section"), error.getMessage());
    }

    @Test
    void stairRunsPickUpTheDirectionTheyClimb() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 5 4 3
                pal
                S stone
                ^ oak_stairs
                end
                plan y=0
                SSSSS
                SSSSS
                SSSSS
                plan y=1 x=1..3 z=1
                ^SS
                plan y=2 x=2..3 z=1
                ^S
                plan y=3 x=3 z=1
                ^
                """);
        assertTrue(at(structure, 1, 1, 1).blockId().contains("facing=east"), at(structure, 1, 1, 1).blockId());
        assertTrue(at(structure, 2, 2, 1).blockId().contains("facing=east"), at(structure, 2, 2, 1).blockId());
        assertTrue(at(structure, 3, 3, 1).blockId().contains("facing=east"), at(structure, 3, 3, 1).blockId());
    }

    @Test
    void pillarAxisFollowsTheDirectionTheBlockRunsIn() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 3 3 3
                pal
                S stone
                L oak_log
                end
                plan y=0
                SSS
                SSS
                SSS
                plan y=1 x=0 z=0
                L
                plan y=2 x=0 z=0
                L
                plan y=1 x=0..2 z=1
                LLL
                """);
        assertEquals("minecraft:oak_log[axis=y]", at(structure, 0, 1, 0).blockId());
        assertEquals("minecraft:oak_log[axis=x]", at(structure, 1, 1, 1).blockId());
    }

    @Test
    void palettteHintOverridesInferenceWhenTheAuthorInsists() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 3 2 3
                pal
                S stone
                > oak_stairs up=west
                end
                plan y=0
                SSS
                SSS
                SSS
                plan y=1 x=1 z=1
                >
                """);
        assertTrue(at(structure, 1, 1, 1).blockId().contains("facing=west"), at(structure, 1, 1, 1).blockId());
    }

    @Test
    void partsAreStampedAsRotatedGlyphGrids() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 3 2 3
                pal
                S stone
                M oak_planks
                end
                part marker 3 2 3
                plan y=0
                SSS
                SSS
                SSS
                plan y=1 x=0 z=0
                M
                end
                at 0 0 0 marker turn=90
                """);
        assertEquals("minecraft:oak_planks", at(structure, 2, 1, 0).blockId());
        assertTrue(structure.getBlocks().stream().noneMatch(b -> b.y() == 1 && b.x() == 0 && b.z() == 0));
    }

    @Test
    void floatingGeometryNeedsAnExplicitGroundFalse() {
        String floating = """
                VXB-2
                size 1 3 1
                %s
                pal
                S stone
                end
                plan y=2
                S
                """;
        VxbCompiler.CompilationException error = assertThrows(VxbCompiler.CompilationException.class,
                () -> VxbCompiler.compile(floating.formatted("")));
        assertTrue(error.getMessage().contains("Ungrounded Structure Component"), error.getMessage());
        assertEquals(1, VxbCompiler.compile(floating.formatted("ground false")).structure().getBlocks().size());
    }

    @Test
    void drawnAirBecomesExcavationWhenTerrainIsKept() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 3 3 3
                terrain keep
                pal
                S stone
                end
                plan y=0
                SSS
                SSS
                SSS
                plan y=1 x=1 z=1
                .
                """);
        assertTrue(structure.shouldPreserveTerrain());
        assertTrue(structure.getClearCells().contains(new Cell(1, 1, 1)));
    }

    @Test
    void compiledStructureEchoesBackInTheAuthorsOwnPalette() {
        String echo = VxbPreviewRenderer.render(VxbCompiler.compile(CABIN).structure());
        assertTrue(echo.contains("plan y=0"), echo);
        assertTrue(echo.contains("z0 CCCCCCCCC"), echo);
        assertTrue(echo.contains("LGGPDPGGL"), echo);
    }

    @Test
    void patchChangesOnlyAddressedLines() {
        String result = VxbPatcher.apply("VXB-2\nname old\nsize 1 1 1", """
                VXP-1
                replace-line 2 name new
                insert-after 3 ground false
                end
                """);
        assertEquals("VXB-2\nname new\nsize 1 1 1\nground false", result);
    }

    private static BlockEntry at(BuildStructure structure, int x, int y, int z) {
        Optional<BlockEntry> found = structure.getBlocks().stream()
                .filter(b -> b.x() == x && b.y() == y && b.z() == z).findFirst();
        return found.orElseThrow(() -> new AssertionError("No block at (" + x + "," + y + "," + z + ")"));
    }

    private static PlacementGroup group(BuildStructure structure, String kind) {
        return structure.getFeatureGroups().stream().filter(g -> g.kind().equals(kind)).findFirst()
                .orElseThrow(() -> new AssertionError("No " + kind + " group was compiled"));
    }
}
