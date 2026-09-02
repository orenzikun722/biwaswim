package com.rencon.biwaswim.log

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 1回の遊泳セッションの記録を保持するデータクラス
 */
@Serializable
data class SwimLog(
    val id: String = UUID.randomUUID().toString(),
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationSeconds: Long,
    val distanceMeters: Double,
    val trackPoints: List<Pair<Double, Double>> = emptyList(),
    var imageFileName: String? = null,
    val userName: String = "ユーザー"
) {
    fun getFormattedDate(): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        return sdf.format(Date(startTimeMs))
    }

    fun getFormattedDuration(): String {
        val hours = durationSeconds / 3600
        val minutes = (durationSeconds % 3600) / 60
        val seconds = durationSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    fun getFormattedDistance(): String {
        return if (distanceMeters >= 1000.0) {
            String.format(Locale.getDefault(), "%.2f km", distanceMeters / 1000.0)
        } else {
            String.format(Locale.getDefault(), "%d m", distanceMeters.toInt())
        }
    }

    fun getAverageSpeedKmh(): String {
        if (durationSeconds <= 0) return "0.0 km/h"
        val km = distanceMeters / 1000.0
        val hours = durationSeconds / 3600.0
        val speed = km / hours
        return String.format(Locale.getDefault(), "%.2f km/h", speed)
    }

    fun getPacePer100m(): String {
        if (distanceMeters <= 0 || durationSeconds <= 0) return "--:--"
        val secPer100m = (durationSeconds.toDouble() / distanceMeters) * 100.0
        val mins = (secPer100m / 60).toInt()
        val secs = (secPer100m % 60).toInt()
        return String.format(Locale.getDefault(), "%d:%02d /100m", mins, secs)
    }
}
