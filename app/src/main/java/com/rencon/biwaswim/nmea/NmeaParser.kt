package com.rencon.biwaswim.nmea

enum class CoordinateType {
    LATITUDE,
    LONGITUDE
}

/**
 * NMEAセンテンスのバッファリングとパース、および座標変換を行うクラス。
 */
class NmeaParser {

    private val buffer = StringBuilder()

    /**
     * 受信したバイト列をバッファに追加し、完全な行から位置情報リストを抽出して返します。
     */
    fun parseRawData(data: ByteArray): List<GpsLocation> {
        val text = String(data, Charsets.UTF_8)
        val lines = mutableListOf<String>()

        synchronized(buffer) {
            buffer.append(text)
            var newlineIndex: Int

            while (true) {
                newlineIndex = buffer.indexOf("\n")
                if (newlineIndex == -1) break

                var line = buffer.substring(0, newlineIndex)
                if (line.endsWith("\r")) {
                    line = line.dropLast(1)
                }

                lines.add(line)
                buffer.delete(0, newlineIndex + 1)
            }
        }

        val locations = mutableListOf<GpsLocation>()
        for (line in lines) {
            val location = parseSentence(line)
            if (location != null) {
                locations.add(location)
            }
        }
        return locations
    }

    /**
     * 単一の NMEA センテンスを解析して GpsLocation を返します。
     * 対象外のセンテンスやパース失敗時は null を返します。
     */
    fun parseSentence(sentence: String): GpsLocation? {
        if (!sentence.startsWith("\$GNGLL")) {
            return null
        }

        val parts = sentence.split(",")
        if (parts.size < 5) {
            return null
        }

        val rawLat = parts[1]
        val rawLng = parts[3]

        if (rawLat.isBlank() || rawLng.isBlank()) {
            return null
        }

        return try {
            val lat = dmsToDecimal(rawLat, CoordinateType.LATITUDE)
            val lng = dmsToDecimal(rawLng, CoordinateType.LONGITUDE)
            GpsLocation(latitude = lat, longitude = lng)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * DMS (度分秒) 形式の文字列を 10進数の度数 (Decimal Degrees) に変換します。
     */
    fun dmsToDecimal(dms: String, type: CoordinateType): Double {
        return when (type) {
            CoordinateType.LATITUDE -> {
                if (dms.length < 4) throw IllegalArgumentException("Latitude DMS string too short: $dms")
                val hour = dms.substring(0, 2).toDouble()
                val minute = dms.substring(2, 4).toDouble()
                val second = if (dms.length > 4) dms.substring(4).toDouble() else 0.0
                hour + (minute / 60.0) + (second / 3600.0)
            }
            CoordinateType.LONGITUDE -> {
                if (dms.length < 5) throw IllegalArgumentException("Longitude DMS string too short: $dms")
                val hour = dms.substring(0, 3).toDouble()
                val minute = dms.substring(3, 5).toDouble()
                val second = if (dms.length > 5) dms.substring(5).toDouble() else 0.0
                hour + (minute / 60.0) + (second / 3600.0)
            }
        }
    }

    /**
     * バッファをクリアします。
     */
    fun clearBuffer() {
        synchronized(buffer) {
            buffer.setLength(0)
        }
    }
}
