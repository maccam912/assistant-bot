package com.assistantbot.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LlmClientProgressTest {

    @Test
    void summaryIncludesStepAndDetail() {
        var progress = new LlmClient.Progress("repairing", "turn 2/8 — applying a draft patch");

        assertEquals("repairing: turn 2/8 — applying a draft patch", progress.summary());
    }

    @Test
    void summaryOmitsMissingDetail() {
        assertEquals("starting", new LlmClient.Progress("starting", null).summary());
    }

    @Test
    void rejectsBlankStep() {
        assertThrows(IllegalArgumentException.class, () -> new LlmClient.Progress(" ", "detail"));
    }
}
