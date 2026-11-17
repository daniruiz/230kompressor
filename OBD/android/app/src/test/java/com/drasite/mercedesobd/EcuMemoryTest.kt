package com.drasite.mercedesobd

import org.junit.Assert.*
import org.junit.Test

class EcuMemoryTest {
    @Test fun onlyTheLatestFamilyIsStoredAndSurvivesNewMemoryInstances() {
        val storage = HashMap<String, String>()
        fun memory() = EcuMemory(storage::get) { key, family -> storage[key] = family }
        assertNull(memory().preferred())
        memory().remember("SIM4LE")
        assertEquals("SIM4LE", memory().preferred())
        memory().remember("ME2.1")
        assertEquals("ME2.1", memory().preferred())
        assertEquals(mapOf("last_ecu_family" to "ME2.1"), storage)
        memory().remember("invalid")
        assertEquals(mapOf("last_ecu_family" to "ME2.1"), storage)
    }

    @Test fun missingCorruptOrUnreadablePreferencesFallBackToDetection() {
        for (value in listOf(null, "invalid", ""))
            assertNull(EcuMemory({ value }, { _, _ -> }).preferred())
        val unreadable = EcuMemory({ throw IllegalStateException("corrupt") }, { _, _ -> })
        assertNull(unreadable.preferred())
    }
}
