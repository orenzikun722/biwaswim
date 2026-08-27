package com.rencon.biwaswim.nmea

import java.util.Locale

/**
 * NMEAセンテンスのパース、チェックサム検証、座標変換を行うクラス。
 * chooblarin/gnss-tracker-demo の方式に準拠し、GGA, RMC, GLL などの主要なGNSSセンテンスに対応。
 */
class NmeaParser {

    private val lineBuffer = NmeaLineBuffer()

    /**
     * 受信した生のバイト列から完全な NMEA センテンスを抽出し、
     * パースできた位置情報 (GpsLocation) のリストを返します。
     */
    fun parseRawData(data: ByteArray): List<GpsLocation> {
        val lines = lineBuffer.append(data)
        val locations = mutableListOf<GpsLocation>()
        for (line in lines) {
            val loc = parseSentence(line)
            if (loc != null) {
                locations.add(loc)
            }
        }
        return locations
    }

    /**
     * 受信した生のバイト列から完全な NMEA センテンスを抽出し、
     * 解析結果の詳細 (NmeaParseDetail) のリストを返します。
     */
    fun parseRawDataWithDetails(data: ByteArray): List<NmeaParseDetail> {
        val lines = lineBuffer.append(data)
        val details = mutableListOf<NmeaParseDetail>()
        for (line in lines) {
            details.add(parseSentenceDetail(line))
        }
        return details
    }

    /**
     * 単一の NMEA センテンス文字列を詳細に解析して NmeaParseDetail を返します。
     */
    fun parseSentenceDetail(sentence: String): NmeaParseDetail {
        return when (val result = parse(sentence)) {
            is NmeaParseResult.Parsed -> {
                val location = when (val event = result.event) {
                    is NmeaEvent.Gga -> {
                        val lat = event.latitude
                        val lng = event.longitude
                        if (lat != null && lng != null && (event.fixQuality == null || event.fixQuality > 0)) {
                            GpsLocation(
                                latitude = lat,
                                longitude = lng,
                                altitudeMeters = event.altitudeMeters,
                                satellites = event.satellitesUsed,
                                fixQuality = event.fixQuality,
                                utcTime = event.utcTime
                            )
                        } else null
                    }
                    is NmeaEvent.Rmc -> {
                        val lat = event.latitude
                        val lng = event.longitude
                        // status が "A" (有効) または未指定の場合
                        if (lat != null && lng != null && event.status != "V") {
                            GpsLocation(
                                latitude = lat,
                                longitude = lng,
                                speedKnots = event.speedKnots,
                                courseDegrees = event.courseDegrees,
                                utcTime = event.utcTime
                            )
                        } else null
                    }
                    is NmeaEvent.Gll -> {
                        val lat = event.latitude
                        val lng = event.longitude
                        if (lat != null && lng != null && event.status != "V") {
                            GpsLocation(
                                latitude = lat,
                                longitude = lng,
                                utcTime = event.utcTime
                            )
                        } else null
                    }
                }
                if (location != null) {
                    NmeaParseDetail.LocationUpdate(location, result.event.sentenceType)
                } else {
                    NmeaParseDetail.NoFix(result.event.sentenceType)
                }
            }
            is NmeaParseResult.InvalidChecksum -> NmeaParseDetail.InvalidChecksum(sentence)
            is NmeaParseResult.Malformed -> NmeaParseDetail.Malformed(sentence)
            is NmeaParseResult.Unsupported -> NmeaParseDetail.Unsupported(result.sentenceType)
        }
    }

    /**
     * 単一の NMEA センテンス文字列を解析して GpsLocation を返します。
     * パース失敗、無効なチェックサム、または対象外のセンテンスの場合は null を返します。
     */
    fun parseSentence(sentence: String): GpsLocation? {
        val detail = parseSentenceDetail(sentence)
        return (detail as? NmeaParseDetail.LocationUpdate)?.location
    }

    /**
     * バッファをクリアします。
     */
    fun clearBuffer() {
        lineBuffer.clear()
    }

    companion object {
        /**
         * NMEA行をパースして詳細な NmeaParseResult を返します。
         */
        fun parse(line: String): NmeaParseResult {
            val trimmed = line.trim()
            if (!trimmed.startsWith("$") && !trimmed.startsWith("!")) {
                return NmeaParseResult.Malformed
            }

            if (!hasValidChecksum(trimmed)) {
                return NmeaParseResult.InvalidChecksum
            }

            val body = trimmed
                .drop(1)
                .substringBefore('*')
            val fields = body.split(',')
            val header = fields.firstOrNull().orEmpty()
            if (header.length < 3) return NmeaParseResult.Malformed

            return when (header.takeLast(3).uppercase(Locale.US)) {
                "GGA" -> parseGga(header, fields)
                "RMC" -> parseRmc(header, fields)
                "GLL" -> parseGll(header, fields)
                else -> NmeaParseResult.Unsupported(header)
            }
        }

        private fun parseGga(header: String, fields: List<String>): NmeaParseResult {
            if (fields.size < 10) return NmeaParseResult.Malformed
            return NmeaParseResult.Parsed(
                NmeaEvent.Gga(
                    sentenceType = header,
                    utcTime = fields.getOrNull(1)?.ifBlank { null },
                    latitude = parseCoordinate(fields.getOrNull(2).orEmpty(), fields.getOrNull(3).orEmpty()),
                    longitude = parseCoordinate(fields.getOrNull(4).orEmpty(), fields.getOrNull(5).orEmpty()),
                    fixQuality = fields.getOrNull(6)?.toIntOrNull(),
                    satellitesUsed = fields.getOrNull(7)?.toIntOrNull(),
                    hdop = fields.getOrNull(8)?.toDoubleOrNull(),
                    altitudeMeters = fields.getOrNull(9)?.toDoubleOrNull()
                )
            )
        }

        private fun parseRmc(header: String, fields: List<String>): NmeaParseResult {
            if (fields.size < 10) return NmeaParseResult.Malformed
            return NmeaParseResult.Parsed(
                NmeaEvent.Rmc(
                    sentenceType = header,
                    utcTime = fields.getOrNull(1)?.ifBlank { null },
                    status = fields.getOrNull(2)?.ifBlank { null },
                    latitude = parseCoordinate(fields.getOrNull(3).orEmpty(), fields.getOrNull(4).orEmpty()),
                    longitude = parseCoordinate(fields.getOrNull(5).orEmpty(), fields.getOrNull(6).orEmpty()),
                    speedKnots = fields.getOrNull(7)?.toDoubleOrNull(),
                    courseDegrees = fields.getOrNull(8)?.toDoubleOrNull(),
                    utcDate = fields.getOrNull(9)?.ifBlank { null }
                )
            )
        }

        private fun parseGll(header: String, fields: List<String>): NmeaParseResult {
            if (fields.size < 5) return NmeaParseResult.Malformed
            return NmeaParseResult.Parsed(
                NmeaEvent.Gll(
                    sentenceType = header,
                    latitude = parseCoordinate(fields.getOrNull(1).orEmpty(), fields.getOrNull(2).orEmpty()),
                    longitude = parseCoordinate(fields.getOrNull(3).orEmpty(), fields.getOrNull(4).orEmpty()),
                    utcTime = fields.getOrNull(5)?.ifBlank { null },
                    status = fields.getOrNull(6)?.ifBlank { null }
                )
            )
        }

        /**
         * NMEA形式の座標文字列 (DDMM.MMMM / DDDMM.MMMM) を10進数の度 (Decimal Degrees) に変換します。
         * 北緯(N)・東経(E)は正の値、南緯(S)・西経(W)は負の値になります。
         */
        fun parseCoordinate(value: String, hemisphere: String): Double? {
            if (value.isBlank() || hemisphere.isBlank()) return null
            val raw = value.toDoubleOrNull() ?: return null
            val degrees = (raw / 100).toInt()
            val minutes = raw - degrees * 100
            val decimal = degrees + (minutes / 60.0)
            return when (hemisphere.uppercase(Locale.US)) {
                "N", "E" -> decimal
                "S", "W" -> -decimal
                else -> null
            }
        }

        /**
         * NMEAチェックサム (*XX) の正当性を検証します。
         */
        fun hasValidChecksum(line: String): Boolean {
            val checksumText = line.substringAfter('*', missingDelimiterValue = "")
            if (checksumText.isBlank()) return true
            if (checksumText.length < 2) return false

            val expected = checksumText.take(2).toIntOrNull(radix = 16) ?: return false
            val body = line.drop(1).substringBefore('*')
            val actual = body.fold(0) { checksum, char -> checksum xor char.code }
            return actual == expected
        }
    }
}

sealed interface NmeaParseResult {
    data class Parsed(val event: NmeaEvent) : NmeaParseResult
    data class Unsupported(val sentenceType: String) : NmeaParseResult
    data object InvalidChecksum : NmeaParseResult
    data object Malformed : NmeaParseResult
}

/**
 * NMEAパース結果の詳細。UI通知や受信品質判定に使用します。
 */
sealed interface NmeaParseDetail {
    data class LocationUpdate(val location: GpsLocation, val sentenceType: String) : NmeaParseDetail
    data class NoFix(val sentenceType: String) : NmeaParseDetail
    data class Unsupported(val sentenceType: String) : NmeaParseDetail
    data class InvalidChecksum(val rawSentence: String) : NmeaParseDetail
    data class Malformed(val rawSentence: String) : NmeaParseDetail
}

sealed interface NmeaEvent {
    val sentenceType: String

    data class Gga(
        override val sentenceType: String,
        val utcTime: String?,
        val latitude: Double?,
        val longitude: Double?,
        val fixQuality: Int?,
        val satellitesUsed: Int?,
        val hdop: Double?,
        val altitudeMeters: Double?
    ) : NmeaEvent

    data class Rmc(
        override val sentenceType: String,
        val utcTime: String?,
        val status: String?,
        val latitude: Double?,
        val longitude: Double?,
        val speedKnots: Double?,
        val courseDegrees: Double?,
        val utcDate: String?
    ) : NmeaEvent

    data class Gll(
        override val sentenceType: String,
        val latitude: Double?,
        val longitude: Double?,
        val utcTime: String?,
        val status: String?
    ) : NmeaEvent
}
