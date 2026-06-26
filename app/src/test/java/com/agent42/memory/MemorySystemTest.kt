package com.agent42.memory

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class MemorySystemTest {

    // MemorySystem only needs the cosineSimilarity function for these tests.
    // We pass a mock database since these tests don't touch the DB.
    private val memorySystem = MemorySystem(db = mock(AgentDatabase::class.java), embeddingModel = null)

    @Test
    fun `cosineSimilarity returns 1 for identical vectors`() {
        val a = floatArrayOf(1.0f, 0.0f, 0.0f)
        val b = floatArrayOf(1.0f, 0.0f, 0.0f)
        val result = memorySystem.cosineSimilarity(a, b)
        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `cosineSimilarity returns 0 for orthogonal vectors`() {
        val a = floatArrayOf(1.0f, 0.0f)
        val b = floatArrayOf(0.0f, 1.0f)
        val result = memorySystem.cosineSimilarity(a, b)
        assertEquals(0.0f, result, 0.001f)
    }

    @Test
    fun `cosineSimilarity handles empty vectors`() {
        val a = floatArrayOf()
        val b = floatArrayOf()
        val result = memorySystem.cosineSimilarity(a, b)
        assertEquals(0.0f, result, 0.001f)
    }

    @Test
    fun `cosineSimilarity handles mismatched sizes`() {
        val a = floatArrayOf(1.0f, 2.0f)
        val b = floatArrayOf(1.0f)
        val result = memorySystem.cosineSimilarity(a, b)
        assertEquals(0.0f, result, 0.001f)
    }

    @Test
    fun `MemoryCategory enum values are correct`() {
        val categories = MemoryCategory.values()
        assertTrue(categories.contains(MemoryCategory.FACTUAL))
        assertTrue(categories.contains(MemoryCategory.SKILL))
    }
}
