package com.assistantbot.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.assistantbot.llm.BuildPlan;
import com.assistantbot.llm.BuildStructure;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BuildTaskCenteringTest {
    @Test
    void declaredPlanSizeIsCenteredOnBotBlock() {
        BuildStructure structure = BuildStructure.parse("""
                VXB-2
                size 8 2 9
                pal
                S stone
                end
                plan y=0 x=0 z=0
                S
                """);
        BuildPlan plan = BuildPlan.grouped(1, "test", "tester", structure,
                BuildStructure.planPlacementGroups(structure));

        assertEquals(new BlockPos(96, 64, 196),
                BuildTask.centeredOrigin(new BlockPos(100, 64, 200), plan));
    }

    @Test
    void legacyPlanCentersItsActualOccupiedBounds() {
        BuildPlan plan = new BuildPlan(1, "legacy", "tester", List.of(
                new BuildStructure.BlockEntry(2, 0, 4, "minecraft:stone"),
                new BuildStructure.BlockEntry(6, 0, 8, "minecraft:stone")));

        assertEquals(new BlockPos(96, 64, 194),
                BuildTask.centeredOrigin(new BlockPos(100, 64, 200), plan));
    }
}
