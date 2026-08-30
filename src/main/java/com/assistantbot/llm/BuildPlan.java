package com.assistantbot.llm;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.BuildStructure.PlacementGroup;
import com.assistantbot.llm.BuildStructure.Cell;
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
    private final List<Cell> clearCells;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final boolean preserveTerrain;

    public BuildPlan(int id, String description, String creatorName, List<BlockEntry> sortedBlocks) {
        this(id, description, creatorName, wrapBlocks(sortedBlocks), List.of(), -1, -1, -1, false);
    }

    private BuildPlan(int id, String description, String creatorName,
                      List<PlacementGroup> placementGroups, List<Cell> clearCells,
                      int sizeX, int sizeY, int sizeZ, boolean preserveTerrain) {
        this.id = id;
        this.description = description;
        this.creatorName = creatorName;
        this.placementGroups = List.copyOf(placementGroups);
        List<BlockEntry> flattened = new ArrayList<>();
        for (PlacementGroup group : placementGroups) flattened.addAll(group.blocks());
        this.sortedBlocks = Collections.unmodifiableList(flattened);
        this.clearCells = List.copyOf(clearCells);
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.preserveTerrain = preserveTerrain;
    }

    public static BuildPlan grouped(int id, String description, String creatorName,
                                    List<PlacementGroup> placementGroups) {
        return new BuildPlan(id, description, creatorName, placementGroups, List.of(), -1, -1, -1, false);
    }

    public static BuildPlan grouped(int id, String description, String creatorName,
                                    BuildStructure structure, List<PlacementGroup> placementGroups) {
        return new BuildPlan(id, description, creatorName, placementGroups,
                List.copyOf(structure.getClearCells()), structure.getSizeX(), structure.getSizeY(),
                structure.getSizeZ(), structure.shouldPreserveTerrain());
    }

    public int getId() { return id; }
    public String getDescription() { return description; }
    public String getCreatorName() { return creatorName; }
    public List<PlacementGroup> getPlacementGroups() { return placementGroups; }
    public List<BlockEntry> getSortedBlocks() { return sortedBlocks; }
    public int getBlockCount() { return sortedBlocks.size(); }
    public int getClearCount() { return clearCells.size(); }
    public List<Cell> getClearCells() { return clearCells; }
    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }
    public boolean shouldPreserveTerrain() { return preserveTerrain; }

    private static List<PlacementGroup> wrapBlocks(List<BlockEntry> blocks) {
        List<PlacementGroup> result = new ArrayList<>();
        int index = 0;
        for (BlockEntry block : blocks) {
            result.add(new PlacementGroup("legacy" + (++index), "block", List.of(block), List.of(), false));
        }
        return result;
    }
}
