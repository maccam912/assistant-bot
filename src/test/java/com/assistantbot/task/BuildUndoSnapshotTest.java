package com.assistantbot.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class BuildUndoSnapshotTest {
    @Test
    void firstStateWinsWhenABuildClearsThenPlacesAtTheSamePosition() {
        BuildUndoSnapshot snapshot = new BuildUndoSnapshot(null);
        BlockPos pos = new BlockPos(4, 70, 9);

        snapshot.capture(pos, Blocks.GRASS_BLOCK.defaultBlockState());
        snapshot.capture(pos, Blocks.AIR.defaultBlockState());

        List<BuildUndoSnapshot.Entry> entries = snapshot.reverseEntries();
        assertEquals(1, entries.size());
        assertEquals(pos, entries.getFirst().pos());
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), entries.getFirst().state());
    }

    @Test
    void restoreOrderReversesTheOrderPositionsWereFirstChanged() {
        BuildUndoSnapshot snapshot = new BuildUndoSnapshot(null);
        BlockPos first = new BlockPos(0, 0, 0);
        BlockPos second = new BlockPos(0, 1, 0);
        snapshot.capture(first, Blocks.STONE.defaultBlockState());
        snapshot.capture(second, Blocks.OAK_PLANKS.defaultBlockState());

        List<BuildUndoSnapshot.Entry> entries = snapshot.reverseEntries();
        assertEquals(second, entries.get(0).pos());
        assertEquals(first, entries.get(1).pos());
    }
}
