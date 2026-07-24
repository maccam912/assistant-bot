package com.assistantbot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockStateResolverTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void resolvesDoorHalfAndFacingFromPaletteState() {
        BlockState state = BlockStateResolver.resolve(
                Blocks.SPRUCE_DOOR,
                "minecraft:spruce_door[half=upper,facing=south]");

        assertEquals("upper", state.getValue(
                Blocks.SPRUCE_DOOR.getStateDefinition().getProperty("half")).toString());
        assertEquals("south", state.getValue(
                Blocks.SPRUCE_DOOR.getStateDefinition().getProperty("facing")).toString());
    }

    @Test
    void rejectsUnknownPropertiesAndValues() {
        assertThrows(IllegalArgumentException.class, () ->
                BlockStateResolver.resolve(Blocks.SPRUCE_DOOR, "spruce_door[axis=y]"));
        assertThrows(IllegalArgumentException.class, () ->
                BlockStateResolver.resolve(Blocks.SPRUCE_DOOR, "spruce_door[half=middle]"));
    }
}
