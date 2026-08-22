package com.rencon.biwaswim.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredBluetoothDeviceTest {

    data class MockDeviceItem(
        val name: String,
        val address: String,
        val rssi: Int
    ) {
        val isQz1OrGnss: Boolean
            get() = name.contains("QZ1", ignoreCase = true) ||
                    name.contains("GNSS", ignoreCase = true) ||
                    name.contains("GPS", ignoreCase = true)
    }

    @Test
    fun sortsByQz1PriorityThenRssiDescending() {
        val items = listOf(
            MockDeviceItem("Headphones", "11:22:33:44:55:66", -40),
            MockDeviceItem("QZ1-A101", "AA:BB:CC:DD:EE:01", -75),
            MockDeviceItem("QZ1-B202", "AA:BB:CC:DD:EE:02", -55),
            MockDeviceItem("QZ1-C303", "AA:BB:CC:DD:EE:03", -90),
            MockDeviceItem("GNSS-X", "AA:BB:CC:DD:EE:04", -60),
            MockDeviceItem("Other-Device", "00:11:22:33:44:55", -30)
        )

        val sorted = items.sortedWith(
            compareByDescending<MockDeviceItem> { it.isQz1OrGnss }
                .thenByDescending { it.rssi }
                .thenBy { it.name }
        )

        // QZ1 / GNSS items should come first, sorted by RSSI descending (-55 > -60 > -75 > -90)
        assertEquals("QZ1-B202", sorted[0].name)
        assertEquals(-55, sorted[0].rssi)

        assertEquals("GNSS-X", sorted[1].name)
        assertEquals(-60, sorted[1].rssi)

        assertEquals("QZ1-A101", sorted[2].name)
        assertEquals(-75, sorted[2].rssi)

        assertEquals("QZ1-C303", sorted[3].name)
        assertEquals(-90, sorted[3].rssi)

        // Non-QZ1 devices sorted by RSSI descending (-30 > -40)
        assertEquals("Other-Device", sorted[4].name)
        assertEquals(-30, sorted[4].rssi)

        assertEquals("Headphones", sorted[5].name)
        assertEquals(-40, sorted[5].rssi)
    }

    @Test
    fun detectsQz1AndGnssNames() {
        val qz1 = MockDeviceItem("QZ1-9876", "AA:BB:CC:DD:EE:FF", -65)
        val lowerQz1 = MockDeviceItem("qz1-unit-2", "AA:BB:CC:DD:EE:FE", -65)
        val gnss = MockDeviceItem("GNSS_RECEIVER", "AA:BB:CC:DD:EE:FD", -65)
        val gps = MockDeviceItem("External GPS", "AA:BB:CC:DD:EE:FC", -65)
        val other = MockDeviceItem("Bluetooth Speaker", "AA:BB:CC:DD:EE:FB", -65)

        assertTrue(qz1.isQz1OrGnss)
        assertTrue(lowerQz1.isQz1OrGnss)
        assertTrue(gnss.isQz1OrGnss)
        assertTrue(gps.isQz1OrGnss)
        assertFalse(other.isQz1OrGnss)
    }
}
