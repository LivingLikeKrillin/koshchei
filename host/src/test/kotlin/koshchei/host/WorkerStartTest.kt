package koshchei.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerStartTest {
    @Test
    fun `off is refused, since koshchei has nothing else to run`() {
        val e = assertFailsWith<IllegalStateException> { workerConfig(emptyMap()) }
        assertEquals(true, e.message!!.contains("KOSHCHEI_PICASSO"))
        val explicit = assertFailsWith<IllegalStateException> { workerConfig(mapOf("KOSHCHEI_PICASSO" to "off")) }
        assertEquals(true, explicit.message!!.contains("KOSHCHEI_PICASSO"))
    }

    @Test
    fun `mock is accepted`() {
        workerConfig(mapOf("KOSHCHEI_PICASSO" to "mock"))
    }
}
