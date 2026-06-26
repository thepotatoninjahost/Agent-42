package com.agent42.reasoning

import com.agent42.memory.ReasoningMode
import org.junit.Assert.*
import org.junit.Test

class ReasoningEngineTest {

    @Test
    fun `ReasoningMode enum has all expected values`() {
        val modes = ReasoningMode.values()
        assertTrue(modes.contains(ReasoningMode.DIRECT))
        assertTrue(modes.contains(ReasoningMode.CHAIN_OF_THOUGHT))
        assertTrue(modes.contains(ReasoningMode.DECOMPOSE))
        assertTrue(modes.contains(ReasoningMode.REFLECTIVE))
    }

    @Test
    fun `ReasoningOutput Done stores interactionId, mode, and confidence`() {
        val done = ReasoningOutput.Done(interactionId = 42L, mode = ReasoningMode.DIRECT, confidence = 0.85f)
        assertEquals(42L, done.interactionId)
        assertEquals(ReasoningMode.DIRECT, done.mode)
        assertEquals(0.85f, done.confidence, 0.001f)
    }

    @Test
    fun `ReasoningOutput Chunk stores text`() {
        val chunk = ReasoningOutput.Chunk("hello")
        assertEquals("hello", chunk.text)
    }

    @Test
    fun `ReasoningStep defaults are correct`() {
        val step = ReasoningStep(description = "test", prompt = "prompt")
        assertNull(step.result)
        assertEquals(0f, step.confidence, 0.001f)
    }
}
