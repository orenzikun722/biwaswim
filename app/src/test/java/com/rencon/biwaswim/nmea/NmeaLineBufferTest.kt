package com.rencon.biwaswim.nmea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaLineBufferTest {

    @Test
    fun appendsChunksAndEmitsCompleteLines() {
        val buffer = NmeaLineBuffer()

        val first = buffer.append("\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n\$GPR")
        assertEquals(1, first.size)
        assertEquals("\$GNGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47", first[0])

        val second = buffer.append("MC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A\n")
        assertEquals(1, second.size)
        assertEquals("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A", second[0])
    }

    @Test
    fun filtersOutNonNmeaLines() {
        val buffer = NmeaLineBuffer()
        val lines = buffer.append("garbage text\r\n\$GNGGA,test*00\r\nmore garbage\n")
        assertEquals(1, lines.size)
        assertEquals("\$GNGGA,test*00", lines[0])
    }

    @Test
    fun clearResetsPendingBuffer() {
        val buffer = NmeaLineBuffer()
        buffer.append("incomplete line without newline")
        buffer.clear()
        val lines = buffer.append("\$GNGGA,test*00\n")
        assertEquals(1, lines.size)
        assertEquals("\$GNGGA,test*00", lines[0])
    }
}
