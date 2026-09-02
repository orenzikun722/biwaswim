package com.rencon.biwaswim.nmea

import android.content.Context
import android.content.SharedPreferences

/**
 * 緯度・経度のオフセット（ズレ）を管理するマネージャー。
 * テストや位置シミュレーション、微調整のために取得した生座標に対して加算するオフセットを保持・永続化します。
 */
object LocationOffsetManager {
    private const val PREFS_NAME = "location_offset_prefs"
    private const val KEY_LAT_OFFSET = "lat_offset"
    private const val KEY_LON_OFFSET = "lon_offset"

    @Volatile
    var latitudeOffset: Double = 0.0
        private set

    @Volatile
    var longitudeOffset: Double = 0.0
        private set

    val isOffsetActive: Boolean
        get() = latitudeOffset != 0.0 || longitudeOffset != 0.0

    /**
     * SharedPreferences から保存済みのオフセット値を読み込みます。
     */
    fun init(context: Context) {
        val prefs = getPrefs(context)
        latitudeOffset = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_LAT_OFFSET, java.lang.Double.doubleToRawLongBits(0.0))
        )
        longitudeOffset = java.lang.Double.longBitsToDouble(
            prefs.getLong(KEY_LON_OFFSET, java.lang.Double.doubleToRawLongBits(0.0))
        )
    }

    /**
     * オフセット値を設定し、SharedPreferences に保存します。
     */
    fun setOffset(context: Context, latOffset: Double, lonOffset: Double) {
        latitudeOffset = latOffset
        longitudeOffset = lonOffset
        getPrefs(context).edit()
            .putLong(KEY_LAT_OFFSET, java.lang.Double.doubleToRawLongBits(latOffset))
            .putLong(KEY_LON_OFFSET, java.lang.Double.doubleToRawLongBits(lonOffset))
            .apply()
    }

    /**
     * メモリ上のみのオフセットを設定します（テスト用）。
     */
    fun setOffsetDirect(latOffset: Double, lonOffset: Double) {
        latitudeOffset = latOffset
        longitudeOffset = lonOffset
    }

    /**
     * オフセットを 0.0 にリセットします。
     */
    fun resetOffset(context: Context) {
        setOffset(context, 0.0, 0.0)
    }

    /**
     * 生の緯度経度にオフセットを加算した座標 (lat, lon) を返します。
     */
    fun applyOffset(rawLatitude: Double, rawLongitude: Double): Pair<Double, Double> {
        return Pair(rawLatitude + latitudeOffset, rawLongitude + longitudeOffset)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
