package io.github.franticplant.koiterminal.devd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushTest {
    @Test
    fun writesAtTheAcknowledgedOffset() {
        assertEquals(ChunkAction.WRITE, chunkAction(0, 0, 1024, 4096))
        assertEquals(ChunkAction.WRITE, chunkAction(1024, 1024, 1024, 4096))
    }

    @Test
    fun acceptsARepeatOfAnAcknowledgedChunk() {
        assertEquals(ChunkAction.REPEAT, chunkAction(2048, 1024, 1024, 4096))
        assertEquals(ChunkAction.REPEAT, chunkAction(1024, 0, 1024, 4096))
    }

    @Test
    fun rejectsGapsOverlapsAndOverflows() {
        assertNull(chunkAction(1024, 2048, 1024, 4096))
        assertNull(chunkAction(1024, 512, 1024, 4096))
        assertNull(chunkAction(0, 0, 2048, 1024))
        assertNull(chunkAction(0, -1, 1024, 4096))
    }
}
