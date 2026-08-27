package com.assistantbot.llm;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.BuildStructure.PlacementGroup;
import java.util.ArrayList;
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
    private final List<PlacementGroup> placementGroups;
    private final List<BlockEntry> sortedBlocks;

    public BuildPlan(int id, String description, String creatorName, List<BlockEntry> sortedBlocks) {
        this(id, description, creatorName, wrapBlocks(sortedBlocks), true);
    }

    private BuildPlan(int id, String description, String creatorName,
                      List<PlacementGroup> placementGroups, boolean grouped) {
        this.id = id;
        this.description = description;
        this.creatorName = creatorName;
        this.placementGroups = List.copyOf(placementGroups);
        List<BlockEntry> flattened = new ArrayList<>();
        for (PlacementGroup group : placementGroups) flattened.addAll(group.blocks());
        this.sortedBlocks = Collections.unmodifiableList(flattened);
    }

    public static BuildPlan grouped(int id, String description, String creatorName,
                                    List<PlacementGroup> placementGroups) {
        return new BuildPlan(id, description, creatorName, placementGroups, true);
    }

    public int getId() { return id; }
    public String getDescription() { return description; }
    public String getCreatorName() { return creatorName; }
    public List<PlacementGroup> getPlacementGroups() { return placementGroups; }
    public List<BlockEntry> getSortedBlocks() { return sortedBlocks; }
    public int getBlockCount() { return sortedBlocks.size(); }

    private static List<PlacementGroup> wrapBlocks(List<BlockEntry> blocks) {
        List<PlacementGroup> result = new ArrayList<>();
        int index = 0;
        for (BlockEntry block : blocks) {
            result.add(new PlacementGroup("legacy" + (++index), "block", List.of(block), List.of(), false));
        }
        return result;
    }
}
