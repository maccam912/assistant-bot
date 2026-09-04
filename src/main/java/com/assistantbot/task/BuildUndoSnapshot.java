package com.assistantbot.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The exact block states that existed before the latest build first touched
 * each position. A position is captured only once, so clearing it and later
 * placing a build block there still restores the pre-clear state.
 */
public final class BuildUndoSnapshot {
    record Entry(BlockPos pos, BlockState state) {}

    private final ServerLevel world;
    private final Map<BlockPos, BlockState> originalStates = new LinkedHashMap<>();

    public BuildUndoSnapshot(ServerLevel world) {
        this.world = world;
    }

    public void capture(BlockPos pos) {
        capture(pos, world.getBlockState(pos));
    }

    void capture(BlockPos pos, BlockState state) {
        originalStates.putIfAbsent(pos.immutable(), state);
    }

    public int size() {
        return originalStates.size();
    }

    ServerLevel world() {
        return world;
    }

    List<Entry> reverseEntries() {
        List<Entry> entries = new ArrayList<>(originalStates.size());
        for (Map.Entry<BlockPos, BlockState> entry : originalStates.entrySet()) {
            entries.add(new Entry(entry.getKey(), entry.getValue()));
        }
        Collections.reverse(entries);
        return entries;
    }
}
