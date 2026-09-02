package com.rencon.biwaswim.disaster.model

import android.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

val disasterJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * 災害配信用WebSocketから配信されるトップレベルメッセージ
 */
@Serializable
data class DisasterAlertMessage(
    val type: String = "eew", // "eew" または "quake"
    val matchedScale: Int? = null,
    val scaleHuman: String? = null,
    val summary: String? = null,
    val timestamp: String? = null,
    val data: DisasterPayloadData? = null
) {
    val isEEW: Boolean
        get() = type.equals("eew", ignoreCase = true) || data?.code == 556

    val isQuake: Boolean
        get() = type.equals("quake", ignoreCase = true) || data?.code == 551

    /** 震度スケール数値（10, 20, 30, 40, 45, 50, 55, 60, 70） */
    val scaleValue: Int
        get() = matchedScale ?: data?.earthquake?.maxScale ?: -1

    /** 震度の表示用文字列（例: "4", "5弱", "7"） */
    val displayScale: String
        get() = scaleHuman ?: formatScale(scaleValue)

    /** 震源地名称 */
    val hypocenterName: String
        get() = data?.earthquake?.hypocenter?.name?.ifBlank { null }
            ?: data?.earthquake?.hypocenter?.reduceName
            ?: "不明"

    /** マグニチュード文字列 */
    val magnitudeText: String
        get() {
            val mag = data?.earthquake?.hypocenter?.magnitude ?: -1.0
            return if (mag > 0) "M%.1f".format(mag) else "M不明"
        }

    /** 震源の深さ文字列 */
    val depthText: String
        get() {
            val depth = data?.earthquake?.hypocenter?.depth ?: -1
            return when {
                depth == 0 -> "ごく浅い"
                depth > 0 -> "約${depth}km"
                else -> "深さ不明"
            }
        }

    /** 発報/発生時刻 */
    val eventTimeText: String
        get() = timestamp
            ?: data?.issue?.time
            ?: data?.earthquake?.originTime
            ?: data?.earthquake?.time
            ?: ""

    /** 第何報情報（EEW用） */
    val serialText: String
        get() {
            val serial = data?.issue?.serial
            return if (!serial.isNullOrBlank()) "第${serial}報" else ""
        }

    /**
     * 震度に応じたメインアクセントカラー（HEX）
     */
    val accentColorHex: String
        get() = when {
            scaleValue >= 60 -> "#B71C1C" // 震度6強, 7: クリムゾン・深紅
            scaleValue >= 50 -> "#D50000" // 震度5強, 6弱: 鮮烈な赤
            scaleValue >= 45 -> "#FF3D00" // 震度5弱: 濃いオレンジレッド
            scaleValue >= 40 -> "#FF6D00" // 震度4: 鮮やかなオレンジ
            scaleValue >= 30 -> "#FFAB00" // 震度3: アンバー/イエロー
            else -> "#2979FF"             // その他: ブルー
        }

    /**
     * 震度に応じた背景グラデーション（開始色、終了色）
     */
    val gradientColors: Pair<Int, Int>
        get() = when {
            scaleValue >= 60 -> Pair(Color.parseColor("#E64A19"), Color.parseColor("#880E4F"))
            scaleValue >= 50 -> Pair(Color.parseColor("#D32F2F"), Color.parseColor("#C2185B"))
            scaleValue >= 45 -> Pair(Color.parseColor("#F57C00"), Color.parseColor("#D32F2F"))
            scaleValue >= 40 -> Pair(Color.parseColor("#FF9800"), Color.parseColor("#E65100"))
            scaleValue >= 30 -> Pair(Color.parseColor("#FFA000"), Color.parseColor("#F57C00"))
            else -> Pair(Color.parseColor("#1976D2"), Color.parseColor("#0D47A1"))
        }

    companion object {
        fun formatScale(scale: Int): String = when (scale) {
            10 -> "1"
            20 -> "2"
            30 -> "3"
            40 -> "4"
            45 -> "5弱"
            46 -> "5弱以上"
            50 -> "5強"
            55 -> "6弱"
            60 -> "6強"
            70 -> "7"
            0 -> "0"
            -1 -> "不明"
            else -> if (scale > 0) "${scale / 10}" else "不明"
        }
    }
}

/**
 * 詳細データペイロード
 */
@Serializable
data class DisasterPayloadData(
    val id: String? = null,
    val code: Int? = null,
    val time: String? = null,
    val cancelled: Boolean? = false,
    val test: Boolean? = false,
    val issue: DisasterIssue? = null,
    val earthquake: DisasterEarthquake? = null,
    val areas: List<DisasterEEWArea>? = null,
    val points: List<DisasterObservationPoint>? = null
)

@Serializable
data class DisasterIssue(
    val source: String? = null,
    val time: String? = null,
    val type: String? = null,
    val eventId: String? = null,
    val serial: String? = null
)

@Serializable
data class DisasterEarthquake(
    val time: String? = null,
    val originTime: String? = null,
    val arrivalTime: String? = null,
    val condition: String? = null,
    val hypocenter: DisasterHypocenter? = null,
    val maxScale: Int? = null,
    val domesticTsunami: String? = null,
    val foreignTsunami: String? = null
)

@Serializable
data class DisasterHypocenter(
    val name: String? = null,
    val reduceName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val depth: Int? = null,
    val magnitude: Double? = null
)

@Serializable
data class DisasterEEWArea(
    val pref: String? = null,
    val name: String? = null,
    val scaleFrom: Int? = null,
    val scaleTo: Int? = null,
    val kindCode: String? = null
) {
    val displayScale: String
        get() = when {
            scaleFrom != null && scaleTo != null && scaleFrom == scaleTo ->
                DisasterAlertMessage.formatScale(scaleFrom)
            scaleFrom != null && scaleTo != null ->
                "${DisasterAlertMessage.formatScale(scaleFrom)}〜${DisasterAlertMessage.formatScale(scaleTo)}"
            scaleFrom != null ->
                DisasterAlertMessage.formatScale(scaleFrom)
            else -> "-"
        }
}

@Serializable
data class DisasterObservationPoint(
    val pref: String? = null,
    val addr: String? = null,
    val isArea: Boolean? = false,
    val scale: Int? = null
)
