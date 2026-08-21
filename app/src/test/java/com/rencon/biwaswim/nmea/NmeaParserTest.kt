package com.rencon.biwaswim.nmea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaParserTest {

    @Test
    fun parsesValidGgaSentence() {
        val line = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
        val result = NmeaParser.parse(line)
        assertTrue(result is NmeaParseResult.Parsed)
        val event = (result as NmeaParseResult.Parsed).event
        assertTrue(event is NmeaEvent.Gga)
        val gga = event as NmeaEvent.Gga

        assertEquals(48.1173, gga.latitude ?: 0.0, 0.000001)
        assertEquals(11.516666, gga.longitude ?: 0.0, 0.000001)
        assertEquals("123519", gga.utcTime)
        assertEquals(1, gga.fixQuality)
        assertEquals(8, gga.satellitesUsed)
        assertEquals(0.9, gga.hdop ?: 0.0, 0.000001)
        assertEquals(545.4, gga.altitudeMeters ?: 0.0, 0.000001)

        // parseSentence check
        val location = NmeaParser().parseSentence(line)
        assertNotNull(location)
        assertEquals(48.1173, location!!.latitude, 0.000001)
        assertEquals(11.516666, location.longitude, 0.000001)
        assertEquals(545.4, location.altitudeMeters ?: 0.0, 0.000001)
        assertEquals(8, location.satellites)
    }

    @Test
    fun parsesValidRmcSentence() {
        val line = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
        val result = NmeaParser.parse(line)
        assertTrue(result is NmeaParseResult.Parsed)
        val event = (result as NmeaParseResult.Parsed).event
        assertTrue(event is NmeaEvent.Rmc)
        val rmc = event as NmeaEvent.Rmc

        assertEquals(48.1173, rmc.latitude ?: 0.0, 0.000001)
        assertEquals(11.516666, rmc.longitude ?: 0.0, 0.000001)
        assertEquals("A", rmc.status)
        assertEquals(22.4, rmc.speedKnots ?: 0.0, 0.000001)
        assertEquals(84.4, rmc.courseDegrees ?: 0.0, 0.000001)
        assertEquals("230394", rmc.utcDate)
    }

    @Test
    fun parsesSouthAndWestCoordinatesCorrectly() {
        val lat = NmeaParser.parseCoordinate("3351.5000", "S")
        val lon = NmeaParser.parseCoordinate("15112.5000", "W")
        assertNotNull(lat)
        assertNotNull(lon)
        assertEquals(-(33.0 + 51.5 / 60.0), lat ?: 0.0, 0.000001)
        assertEquals(-(151.0 + 12.5 / 60.0), lon ?: 0.0, 0.000001)
    }

    @Test
    fun rejectsInvalidChecksum() {
        val line = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00"
        val result = NmeaParser.parse(line)
        assertEquals(NmeaParseResult.InvalidChecksum, result)
        assertNull(NmeaParser().parseSentence(line))
    }

    @Test
    fun acceptsSentenceWithoutChecksum() {
        val line = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"
        val result = NmeaParser.parse(line)
        assertTrue(result is NmeaParseResult.Parsed)
    }

    @Test
    fun parseRawDataReturnsLocations() {
        val parser = NmeaParser()
        val data = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47\r\n".toByteArray(Charsets.US_ASCII)
        val locations = parser.parseRawData(data)
        assertEquals(1, locations.size)
        assertEquals(48.1173, locations[0].latitude, 0.000001)
    }
}
