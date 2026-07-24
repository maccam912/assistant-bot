package com.assistantbot.llm;

import java.util.Optional;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Resolves a VXB palette value such as
 * {@code spruce_door[half=upper,facing=south]} to an exact block state.
 */
public final class BlockStateResolver {
    private BlockStateResolver() {
    }

    public static BlockState resolve(Block block, String blockId) {
        BlockState state = block.defaultBlockState();
        int stateStart = blockId.indexOf('[');
        if (stateStart < 0) {
            return state;
        }

        int stateEnd = blockId.lastIndexOf(']');
        if (stateEnd != blockId.length() - 1 || stateEnd <= stateStart) {
            throw new IllegalArgumentException("Malformed block state: " + blockId);
        }

        String stateValues = blockId.substring(stateStart + 1, stateEnd).trim();
        if (stateValues.isEmpty()) {
            return state;
        }

        for (String assignment : stateValues.split(",")) {
            int equalsIndex = assignment.indexOf('=');
            if (equalsIndex <= 0 || equalsIndex == assignment.length() - 1) {
                throw new IllegalArgumentException(
                        "Malformed block state property '" + assignment.trim() + "' in " + blockId);
            }

            String propertyName = assignment.substring(0, equalsIndex).trim();
            String propertyValue = assignment.substring(equalsIndex + 1).trim();
            Property<?> property = block.getStateDefinition().getProperty(propertyName);
            if (property == null) {
                throw new IllegalArgumentException(
                        "Block state property '" + propertyName + "' does not exist in " + blockId);
            }
            state = setPropertyValue(state, property, propertyValue, blockId);
        }

        return state;
    }

    private static <T extends Comparable<T>> BlockState setPropertyValue(
            BlockState state, Property<T> property, String value, String blockId) {
        Optional<T> parsed = property.getValue(value);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid value '" + value + "' for property '" + property.getName() + "' in " + blockId);
        }
        return state.setValue(property, parsed.get());
    }
}
