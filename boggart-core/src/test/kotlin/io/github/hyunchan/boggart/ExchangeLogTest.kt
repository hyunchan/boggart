package io.github.hyunchan.boggart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExchangeLogTest {
    private val request = BoggartRequest("GET", "https://a.io/")
    private fun exchange(id: Long) = Exchange(id, id, request, null, null, Provenance.Original, null)

    @Test
    fun `drops oldest when over capacity`() {
        val log = ExchangeLog(capacity = 3)
        (1L..5L).forEach { log.add(exchange(it)) }
        assertEquals(listOf(3L, 4L, 5L), log.exchanges.value.map { it.id })
    }

    @Test
    fun `shrinking capacity trims`() {
        val log = ExchangeLog(capacity = 5)
        (1L..5L).forEach { log.add(exchange(it)) }
        log.capacity = 2
        assertEquals(listOf(4L, 5L), log.exchanges.value.map { it.id })
        log.clear()
        assertTrue(log.exchanges.value.isEmpty())
    }

    @Test
    fun `capacity must be positive`() {
        assertFailsWith<IllegalArgumentException> { ExchangeLog(capacity = 0) }
    }
}
