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
        val isQz1: Boolean
            get() = name.startsWith("QZ1", ignoreCase = true)
    }

    @Test
    fun filtersAndSortsQz1ByRssiDescending() {
        val items = listOf(
            MockDeviceItem("Headphones", "11:22:33:44:55:66", -40),
            MockDeviceItem("QZ1-A101", "AA:BB:CC:DD:EE:01", -75),
            MockDeviceItem("QZ1-B202", "AA:BB:CC:DD:EE:02", -55),
            MockDeviceItem("QZ1-C303", "AA:BB:CC:DD:EE:03", -90),
            MockDeviceItem("GNSS-X", "AA:BB:CC:DD:EE:04", -60),
            MockDeviceItem("Other-Device", "00:11:22:33:44:55", -30)
        )

        val qz1OnlySorted = items.filter { it.isQz1 }.sortedWith(
            compareByDescending<MockDeviceItem> { it.rssi }
                .thenBy { it.name }
        )

        assertEquals(3, qz1OnlySorted.size)
        // QZ1 items sorted by RSSI descending (-55 > -75 > -90)
        assertEquals("QZ1-B202", qz1OnlySorted[0].name)
        assertEquals(-55, qz1OnlySorted[0].rssi)

        assertEquals("QZ1-A101", qz1OnlySorted[1].name)
        assertEquals(-75, qz1OnlySorted[1].rssi)

        assertEquals("QZ1-C303", qz1OnlySorted[2].name)
        assertEquals(-90, qz1OnlySorted[2].rssi)
    }

    @Test
    fun detectsQz1Prefix() {
        val qz1Upper = MockDeviceItem("QZ1-9876", "AA:BB:CC:DD:EE:FF", -65)
        val qz1Lower = MockDeviceItem("qz1-unit-2", "AA:BB:CC:DD:EE:FE", -65)
        val gnss = MockDeviceItem("GNSS_RECEIVER", "AA:BB:CC:DD:EE:FD", -65)
        val gps = MockDeviceItem("External GPS", "AA:BB:CC:DD:EE:FC", -65)
        val midQz1 = MockDeviceItem("MY-QZ1-DEVICE", "AA:BB:CC:DD:EE:FB", -65)
        val other = MockDeviceItem("Bluetooth Speaker", "AA:BB:CC:DD:EE:FA", -65)

        assertTrue(qz1Upper.isQz1)
        assertTrue(qz1Lower.isQz1)
        assertFalse(gnss.isQz1)
        assertFalse(gps.isQz1)
        assertFalse(midQz1.isQz1)
        assertFalse(other.isQz1)
    }
}

