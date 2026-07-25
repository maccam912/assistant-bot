package com.assistantbot.task;

import java.math.BigDecimal;

/**
 * Converts a build rate expressed in blocks per second into a per-task-tick
 * work budget. Fractional credit is carried between ticks, allowing rates
 * below the four task ticks per second.
 */
public final class BuildRateLimiter {
    public static final double DEFAULT_BLOCKS_PER_SECOND = 20.0;
    public static final double MIN_BLOCKS_PER_SECOND = 0.25;

    private static final double TASK_TICKS_PER_SECOND = 4.0;
    private static final double ROUNDING_EPSILON = 1.0e-9;

    private double blockCredit;

    /**
     * Adds this tick's fractional credit and returns the number of block
     * operations that may be performed now.
     */
    public int takeBlockBudget(double blocksPerSecond) {
        if (!isValid(blocksPerSecond)) {
            throw new IllegalArgumentException(
                    "Build speed must be at least " + format(MIN_BLOCKS_PER_SECOND)
                            + " blocks per second");
        }

        blockCredit += blocksPerSecond / TASK_TICKS_PER_SECOND;
        int budget = (int) Math.floor(blockCredit + ROUNDING_EPSILON);
        blockCredit = Math.max(0.0, blockCredit - budget);
        return budget;
    }

    public static boolean isValid(double blocksPerSecond) {
        return Double.isFinite(blocksPerSecond)
                && blocksPerSecond >= MIN_BLOCKS_PER_SECOND;
    }

    public static String format(double blocksPerSecond) {
        return BigDecimal.valueOf(blocksPerSecond).stripTrailingZeros().toPlainString();
    }
}
