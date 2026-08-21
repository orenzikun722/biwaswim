package com.rencon.biwaswim.nmea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NmeaParserTest {

    private lateinit var parser: NmeaParser

    @Before
    fun setUp() {
        parser = NmeaParser()
    }

    @Test
    fun testDmsToDecimal_latitude() {
        // 例: 35度15分30秒 -> 35 + 15/60 + 30/3600 = 35 + 0.25 + 0.0083333... = 35.2583333...
        val dms = "351530"
        val decimal = parser.dmsToDecimal(dms, CoordinateType.LATITUDE)
        val expected = 35.0 + (15.0 / 60.0) + (30.0 / 3600.0)
        assertEquals(expected, decimal, 0.0001)
    }

    @Test
    fun testDmsToDecimal_longitude() {
        // 例: 136度06分00秒 -> 136 + 6/60 + 0 = 136.1
        val dms = "1360600"
        val decimal = parser.dmsToDecimal(dms, CoordinateType.LONGITUDE)
        val expected = 136.0 + (6.0 / 60.0)
        assertEquals(expected, decimal, 0.0001)
    }

    @Test
    fun testParseValidGngllSentence() {
        // $GNGLL,3515.0000,N,13606.0000,E,123456.00,A,D*7B
        val sentence = "\$GNGLL,351500,N,1360600,E,123456.00,A,D"
        val location = parser.parseSentence(sentence)

        assertNotNull(location)
        assertEquals(35.25, location!!.latitude, 0.001)
        assertEquals(136.1, location.longitude, 0.001)
    }

    @Test
    fun testParseOtherSentenceReturnsNull() {
        val sentence = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        val location = parser.parseSentence(sentence)

        assertNull(location)
    }

    @Test
    fun testParseRawDataWithBufferingAndNewlines() {
        // データを2回に分けて送信するケース
        val chunk1 = "\$GNGLL,351500,N,13".toByteArray(Charsets.UTF_8)
        val chunk2 = "60600,E,123456.00,A,D\r\n".toByteArray(Charsets.UTF_8)

        val result1 = parser.parseRawData(chunk1)
        assertTrue(result1.isEmpty())

        val result2 = parser.parseRawData(chunk2)
        assertEquals(1, result2.size)
        assertEquals(35.25, result2[0].latitude, 0.001)
        assertEquals(136.1, result2[0].longitude, 0.001)
    }
}
