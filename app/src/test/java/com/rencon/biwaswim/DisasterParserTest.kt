package com.rencon.biwaswim

import com.rencon.biwaswim.disaster.model.DisasterAlertMessage
import com.rencon.biwaswim.disaster.model.disasterJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisasterParserTest {

    @Test
    fun testParseEEWMessage() {
        val jsonString = """
        {
          "type": "eew",
          "matchedScale": 40,
          "scaleHuman": "4",
          "summary": "【緊急地震速報】最大予想震度4 / 震源: 宮城県沖 M5.0 (第1報)",
          "timestamp": "2026/09/02 12:15:00",
          "data": {
            "id": "test_eew_001",
            "code": 556,
            "cancelled": false,
            "issue": {
              "time": "2026/09/02 12:15:00",
              "eventId": "20260902121500",
              "serial": "1"
            },
            "earthquake": {
              "originTime": "2026/09/02 12:14:50",
              "hypocenter": {
                "name": "宮城県沖",
                "latitude": 38.2,
                "longitude": 141.5,
                "depth": 50,
                "magnitude": 5.0
              }
            },
            "areas": [
              {
                "pref": "宮城県",
                "name": "宮城県南部",
                "scaleFrom": 40,
                "scaleTo": 40,
                "kindCode": "10"
              }
            ]
          }
        }
        """.trimIndent()

        val alert = disasterJson.decodeFromString<DisasterAlertMessage>(jsonString)

        assertTrue(alert.isEEW)
        assertFalse(alert.isQuake)
        assertEquals("4", alert.displayScale)
        assertEquals(40, alert.scaleValue)
        assertEquals("宮城県沖", alert.hypocenterName)
        assertEquals("M5.0", alert.magnitudeText)
        assertEquals("約50km", alert.depthText)
        assertEquals("第1報", alert.serialText)
        assertEquals(1, alert.data?.areas?.size)
        assertEquals("宮城県南部", alert.data?.areas?.first()?.name)
        assertEquals("4", alert.data?.areas?.first()?.displayScale)
    }

    @Test
    fun testParseQuakeMessage() {
        val jsonString = """
        {
          "type": "quake",
          "matchedScale": 30,
          "scaleHuman": "3",
          "summary": "【地震情報】最大震度3 / 震源: 滋賀県南部 M4.5 深さ10km",
          "timestamp": "2026/09/02 13:00:00",
          "data": {
            "id": "test_quake_002",
            "code": 551,
            "time": "2026/09/02 13:00:00",
            "earthquake": {
              "time": "2026/09/02 12:59:00",
              "maxScale": 30,
              "hypocenter": {
                "name": "滋賀県南部",
                "depth": 10,
                "magnitude": 4.5,
                "latitude": 35.1,
                "longitude": 135.9
              }
            }
          }
        }
        """.trimIndent()

        val alert = disasterJson.decodeFromString<DisasterAlertMessage>(jsonString)

        assertTrue(alert.isQuake)
        assertFalse(alert.isEEW)
        assertEquals("3", alert.displayScale)
        assertEquals("滋賀県南部", alert.hypocenterName)
        assertEquals("M4.5", alert.magnitudeText)
        assertEquals("約10km", alert.depthText)
    }
}
