package com.assistantbot.llm;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global in-memory registry of build plans. Plans survive for the server's
 * lifetime (no persistence). IDs are globally unique auto-incrementing integers.
 * Thread-safe: multiple bots can store/read plans concurrently.
 *
 * No eviction — plans accumulate until server restart. Intentional for V1;
 * each plan is a few KB at most.
 */
public class BuildPlanRegistry {
    private static final BuildPlanRegistry INSTANCE = new BuildPlanRegistry();
    private final Map<Integer, BuildPlan> plans = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private BuildPlanRegistry() {}

    public static BuildPlanRegistry getInstance() { return INSTANCE; }

    /**
     * Store a plan and assign it the next available ID.
     *
     * @param description plan description
     * @param creatorName name of the player who created the plan
     * @param sortedBlocks validated, BFS-sorted block list
     * @return the assigned plan ID
     */
    public int store(String description, String creatorName,
                     List<BuildStructure.BlockEntry> sortedBlocks) {
        int id = nextId.getAndIncrement();
        BuildPlan plan = new BuildPlan(id, description, creatorName, sortedBlocks);
        plans.put(id, plan);
        return id;
    }

    /**
     * Look up a plan by ID.
     * @return the plan, or null if not found
     */
    public BuildPlan get(int id) {
        return plans.get(id);
    }

    /**
     * Get all stored plans (unmodifiable view).
     */
    public Collection<BuildPlan> getAll() {
        return Collections.unmodifiableCollection(plans.values());
    }
}
