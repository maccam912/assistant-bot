package com.assistantbot.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildRateLimiterTest {
    @Test
    void defaultRateProducesFiveBlocksPerTaskTick() {
        BuildRateLimiter limiter = new BuildRateLimiter();

        assertEquals(5, limiter.takeBlockBudget(BuildRateLimiter.DEFAULT_BLOCKS_PER_SECOND));
        assertEquals(5, limiter.takeBlockBudget(BuildRateLimiter.DEFAULT_BLOCKS_PER_SECOND));
    }

    @Test
    void highRateHasNoConfiguredUpperLimit() {
        BuildRateLimiter limiter = new BuildRateLimiter();

        assertEquals(250_000, limiter.takeBlockBudget(1_000_000.0));
    }

    @Test
    void slowRateCarriesFractionalCreditAcrossTicks() {
        BuildRateLimiter limiter = new BuildRateLimiter();

        assertEquals(0, limiter.takeBlockBudget(1.0));
        assertEquals(0, limiter.takeBlockBudget(1.0));
        assertEquals(0, limiter.takeBlockBudget(1.0));
        assertEquals(1, limiter.takeBlockBudget(1.0));
    }

    @Test
    void fractionalRateAveragesToRequestedBlocksPerSecond() {
        BuildRateLimiter limiter = new BuildRateLimiter();
        int blocks = 0;

        for (int tick = 0; tick < 8; tick++) {
            blocks += limiter.takeBlockBudget(2.5);
        }

        assertEquals(5, blocks);
    }

    @Test
    void changedRateTakesEffectOnNextTick() {
        BuildRateLimiter limiter = new BuildRateLimiter();

        assertEquals(0, limiter.takeBlockBudget(2.0));
        assertEquals(5, limiter.takeBlockBudget(20.0));
    }

    @Test
    void validatesBoundsAndFormatsForChat() {
        assertTrue(BuildRateLimiter.isValid(BuildRateLimiter.MIN_BLOCKS_PER_SECOND));
        assertTrue(BuildRateLimiter.isValid(1_000_000_000.0));
        assertFalse(BuildRateLimiter.isValid(BuildRateLimiter.MIN_BLOCKS_PER_SECOND - 0.01));
        assertFalse(BuildRateLimiter.isValid(Double.NaN));
        assertFalse(BuildRateLimiter.isValid(Double.POSITIVE_INFINITY));
        assertEquals("20", BuildRateLimiter.format(20.0));
        assertEquals("0.25", BuildRateLimiter.format(0.25));
    }
}
