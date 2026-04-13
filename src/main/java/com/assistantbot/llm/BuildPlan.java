package com.assistantbot.llm;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import java.util.Collections;
import java.util.List;

/**
 * Immutable data object holding a validated, BFS-sorted build plan.
 * Plans are stored in BuildPlanRegistry and referenced by integer ID.
 * Block coordinates are relative (0-based); the world-space origin is
 * provided at execute time.
 */
public class BuildPlan {
    private final int id;
    private final String description;
    private final String creatorName;
    private final List<BlockEntry> sortedBlocks;

    public BuildPlan(int id, String description, String creatorName, List<BlockEntry> sortedBlocks) {
        this.id = id;
        this.description = description;
        this.creatorName = creatorName;
        this.sortedBlocks = Collections.unmodifiableList(sortedBlocks);
    }

    public int getId() { return id; }
    public String getDescription() { return description; }
    public String getCreatorName() { return creatorName; }
    public List<BlockEntry> getSortedBlocks() { return sortedBlocks; }
    public int getBlockCount() { return sortedBlocks.size(); }
}
