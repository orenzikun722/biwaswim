package com.rencon.biwaswim.nmea

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocationOffsetManagerTest {

    @Before
    fun setUp() {
        LocationOffsetManager.setOffsetDirect(0.0, 0.0)
    }

    @Test
    fun testDefaultOffsetIsZero() {
        assertEquals(0.0, LocationOffsetManager.latitudeOffset, 0.0000001)
        assertEquals(0.0, LocationOffsetManager.longitudeOffset, 0.0000001)
        assertFalse(LocationOffsetManager.isOffsetActive)

        val (lat, lon) = LocationOffsetManager.applyOffset(35.25, 136.05)
        assertEquals(35.25, lat, 0.0000001)
        assertEquals(136.05, lon, 0.0000001)
    }

    @Test
    fun testPositiveOffsetApplication() {
        LocationOffsetManager.setOffsetDirect(0.01234, 0.05678)
        assertTrue(LocationOffsetManager.isOffsetActive)

        val (lat, lon) = LocationOffsetManager.applyOffset(35.0, 136.0)
        assertEquals(35.01234, lat, 0.0000001)
        assertEquals(136.05678, lon, 0.0000001)
    }

    @Test
    fun testNegativeOffsetApplication() {
        LocationOffsetManager.setOffsetDirect(-0.15, -0.25)
        assertTrue(LocationOffsetManager.isOffsetActive)

        val (lat, lon) = LocationOffsetManager.applyOffset(35.5, 136.5)
        assertEquals(35.35, lat, 0.0000001)
        assertEquals(136.25, lon, 0.0000001)
    }

    @Test
    fun testOffsetReset() {
        LocationOffsetManager.setOffsetDirect(0.5, 0.5)
        assertTrue(LocationOffsetManager.isOffsetActive)

        LocationOffsetManager.setOffsetDirect(0.0, 0.0)
        assertFalse(LocationOffsetManager.isOffsetActive)

        val (lat, lon) = LocationOffsetManager.applyOffset(35.0, 136.0)
        assertEquals(35.0, lat, 0.0000001)
        assertEquals(136.0, lon, 0.0000001)
    }
}
