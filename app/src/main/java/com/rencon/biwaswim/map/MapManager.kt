package com.rencon.biwaswim.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import com.rencon.biwaswim.MainActivity
import com.rencon.biwaswim.R
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * MapLibre 地図の初期化、スタイル設定、マーカーレイヤー、泳いだ軌跡（線）レイヤーの管理、ライフサイクル委譲を行うクラス。
 */
class MapManager(
    private val context: Context,
    private val mapView: MapView
) {

    companion object {
        private const val SOURCE_ID = "marker-source"
        private const val LAYER_ID = "marker-layer"
        private const val ICON_ID = "marker-icon"

        private val PARTY_MEMBER_COLORS = intArrayOf(
            Color.parseColor("#1E88E5"), // 青 (Blue)
            Color.parseColor("#FB8C00"), // オレンジ (Orange)
            Color.parseColor("#43A047"), // 緑 (Green)
            Color.parseColor("#8E24AA"), // 紫 (Purple)
            Color.parseColor("#00ACC1"), // シアン (Cyan)
            Color.parseColor("#FFD600")  // イエロー (Yellow)
        )

        private const val TRACK_SOURCE_ID = "swim-track-source"
        private const val TRACK_LAYER_ID = "swim-track-layer"

        private const val DEFAULT_LAT = 35.25
        private const val DEFAULT_LNG = 136.1
        private const val DEFAULT_ZOOM = 9.0
        private const val DEFAULT_TILT = 0.0

        private val OSM_SATELLITE_STYLE_JSON = """
            {
              "version": 8,
              "sources": {
                "satellite": {
                  "type": "raster",
                  "tiles": [
                    "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
                  ],
                  "tileSize": 256
                }
              },
              "layers": [
                {
                  "id": "satellite",
                  "type": "raster",
                  "source": "satellite"
                }
              ]
            }
        """.trimIndent()
        private val GSI_SATELLITE_STYLE_JSON = """
            {
              "version": 8,
              "sources": {
                "satellite": {
                  "type": "raster",
                  "tiles": [
                    "https://cyberjapandata.gsi.go.jp/xyz/seamlessphoto/{z}/{x}/{y}.jpg"
                  ],
                  "tileSize": 256
                }
              },
              "layers": [
                {
                  "id": "satellite",
                  "type": "raster",
                  "source": "satellite"
                }
              ]
            }
        """.trimIndent()
        var attributionTextView: TextView? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
    var nowMapStyleType = "OSM"

    private var mapLibreMap: MapLibreMap? = null
    private var isInitialCameraMoved = false

    private var markerSource: GeoJsonSource? = null
    private var markerLayer: SymbolLayer? = null

    // パーティメンバーのマーカー管理
    private val memberLocations = mutableMapOf<String, LatLng>()
    private val memberNames = mutableMapOf<String, String>()
    private val partyMarkers = mutableMapOf<String, Marker>()
    private val partyMarkerIcons = mutableMapOf<String, Icon>()

    private var trackSource: GeoJsonSource? = null
    private var trackLayer: LineLayer? = null

    private val trackPoints = mutableListOf<Point>()

    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0

    /**
     * MapLibre インスタンスと MapView の初期化を行います。
     */
    fun initialize(savedInstanceState: Bundle?, onMapReady: (() -> Unit)? = null) {
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            mapLibreMap = map
            val style = Style.Builder().fromJson(OSM_SATELLITE_STYLE_JSON)
            map.setStyle(style) { loadedStyle ->
                // 1. 軌跡用 Source & LineLayer の初期化
                val initialTrackFeatureCollection = if (trackPoints.size >= 2) {
                    FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(trackPoints)))
                } else {
                    FeatureCollection.fromFeatures(emptyArray())
                }
                if (loadedStyle.getSource(TRACK_SOURCE_ID) == null) {
                    val tSource = GeoJsonSource(TRACK_SOURCE_ID, initialTrackFeatureCollection)
                    loadedStyle.addSource(tSource)
                }
                trackSource = loadedStyle.getSourceAs(TRACK_SOURCE_ID)

                if (loadedStyle.getLayer(TRACK_LAYER_ID) == null) {
                    val tLayer = LineLayer(TRACK_LAYER_ID, TRACK_SOURCE_ID).apply {
                        setProperties(
                            PropertyFactory.lineColor(Color.parseColor("#00E5FF")), // 鮮やかなシアン
                            PropertyFactory.lineWidth(6.0f),
                            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                            PropertyFactory.lineOpacity(0.9f)
                        )
                    }
                    loadedStyle.addLayer(tLayer)
                }
                trackLayer = loadedStyle.getLayerAs(TRACK_LAYER_ID)

                // 2. 自身用マーカーの初期化
                resetMarker(loadedStyle)

                // 3. パーティメンバーマーカーの再描画
                reapplyAllPartyMarkers()

                onMapReady?.invoke()
            }

            val initialTarget = if (currentLatitude != 0.0 || currentLongitude != 0.0) {
                isInitialCameraMoved = true
                LatLng(currentLatitude, currentLongitude)
            } else {
                LatLng(DEFAULT_LAT, DEFAULT_LNG)
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(initialTarget)
                .zoom(DEFAULT_ZOOM)
                .tilt(DEFAULT_TILT)
                .build()
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.compassGravity = Gravity.BOTTOM or Gravity.END
            map.uiSettings.setCompassMargins(0, 0, 30, 90)
        }
    }

    /**
     * 現在位置を更新し、マーカーを移動・表示します。
     */
    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
        runOnMainThread {
            applyMarkerLocation(latitude, longitude)
        }
    }

    private fun applyMarkerLocation(latitude: Double, longitude: Double) {
        Log.d("MapManager", "applyMarkerLocation: lat=$latitude, lon=$longitude, markerSource=$markerSource, markerLayer=$markerLayer")
        markerSource?.setGeoJson(Point.fromLngLat(longitude, latitude))
        markerLayer?.setProperties(PropertyFactory.visibility(Property.VISIBLE))

        if (!isInitialCameraMoved && (latitude != 0.0 || longitude != 0.0)) {
            isInitialCameraMoved = true
            mapLibreMap?.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(latitude, longitude))
                    .zoom(DEFAULT_ZOOM * 1.3)
                    .tilt(DEFAULT_TILT)
                    .build()
            ))
        }
    }

    /**
     * 泳いだ軌跡に新しい座標を追加し、地図上の線を更新します。
     */
    fun addTrackPoint(latitude: Double, longitude: Double) {
        runOnMainThread {
            val point = Point.fromLngLat(longitude, latitude)
            trackPoints.add(point)
            applyTrackUpdate()
        }
    }

    /**
     * 泳いだ軌跡をリセットします。
     */
    fun clearTrack() {
        runOnMainThread {
            trackPoints.clear()
            trackSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    /**
     * 軌跡の全座標を一度に設定します。
     */
    fun setTrackPoints(points: List<Pair<Double, Double>>) {
        runOnMainThread {
            trackPoints.clear()
            points.forEach { (lat, lng) ->
                trackPoints.add(Point.fromLngLat(lng, lat))
            }
            applyTrackUpdate()
        }
    }

    /**
     * パーティメンバーのマーカー用 Bitmap を生成します（カラーピン＋角丸名札バッジ）。
     */
    private fun createPartyMemberMarkerBitmap(displayName: String, color: Int): Bitmap {
        val baseBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.marker)
        val scaledWidth = 64
        val scaledHeight = 64
        val pinBitmap = if (baseBitmap != null) {
            val tinted = createTintedMarkerBitmap(baseBitmap, color)
            Bitmap.createScaledBitmap(tinted, scaledWidth, scaledHeight, true)
        } else {
            val bmp = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            canvas.drawCircle(scaledWidth / 2f, scaledHeight / 2f, scaledWidth / 2f - 2f, paint)
            bmp
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val textBounds = Rect()
        textPaint.getTextBounds(displayName, 0, displayName.length, textBounds)
        val textWidth = textBounds.width()
        val textHeight = textBounds.height()

        val paddingH = 14
        val paddingV = 6
        val badgeWidth = maxOf(scaledWidth, textWidth + paddingH * 2)
        val badgeHeight = textHeight + paddingV * 2
        val spacing = 2

        val totalWidth = badgeWidth
        val totalHeight = scaledHeight + spacing + badgeHeight

        val resultBitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        // 1. ピン画像を上部中央に配置
        val pinLeft = (totalWidth - scaledWidth) / 2f
        canvas.drawBitmap(pinBitmap, pinLeft, 0f, null)

        // 2. 名札バッジ背景
        val badgeRect = RectF(0f, (scaledHeight + spacing).toFloat(), totalWidth.toFloat(), totalHeight.toFloat())
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor("#D9000000") // 85% opacity black
        }
        canvas.drawRoundRect(badgeRect, 8f, 8f, bgPaint)

        // 名札バッジ枠線
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRoundRect(badgeRect, 8f, 8f, strokePaint)

        // 3. ラベルテキスト描画
        val textX = totalWidth / 2f
        val textY = scaledHeight + spacing + paddingV + textHeight - 1f
        canvas.drawText(displayName, textX, textY, textPaint)

        return resultBitmap
    }

    private fun getMemberIcon(clientId: String, displayName: String): Icon {
        val colorIndex = (clientId.hashCode() and 0x7FFFFFFF) % PARTY_MEMBER_COLORS.size
        val color = PARTY_MEMBER_COLORS[colorIndex]
        val bmp = createPartyMemberMarkerBitmap(displayName, color)
        val icon = IconFactory.getInstance(context).fromBitmap(bmp)
        partyMarkerIcons[clientId] = icon
        return icon
    }

    /**
     * パーティメンバーのユーザー名を更新し、マーカーバッジを再描画します。
     */
    fun updateMemberName(clientId: String, userName: String) {
        if (userName.isBlank()) return
        Log.d("MapManager", "updateMemberName: clientId=$clientId, userName=$userName")
        runOnMainThread {
            val oldName = memberNames[clientId]
            memberNames[clientId] = userName
            if (oldName != userName) {
                val marker = partyMarkers[clientId]
                if (marker != null) {
                    val icon = getMemberIcon(clientId, userName)
                    marker.icon = icon
                    marker.title = userName
                    Log.d("MapManager", "Updated existing marker badge for $clientId to '$userName'")
                }
            }
        }
    }

    /**
     * パーティメンバーの位置を更新または追加します。
     */
    fun updateMemberLocation(clientId: String, latitude: Double, longitude: Double, displayName: String? = null) {
        Log.d("MapManager", "updateMemberLocation: clientId=$clientId, lat=$latitude, lon=$longitude, displayName=$displayName")
        runOnMainThread {
            if (!displayName.isNullOrBlank()) {
                memberNames[clientId] = displayName
            }
            val effectiveName = memberNames[clientId] ?: (if (!displayName.isNullOrBlank()) displayName else context.getString(R.string.party_default_member_name))

            val latLng = LatLng(latitude, longitude)
            memberLocations[clientId] = latLng

            val map = mapLibreMap
            if (map == null) {
                Log.w("MapManager", "updateMemberLocation: mapLibreMap is not ready yet for $clientId")
                return@runOnMainThread
            }

            val existingMarker = partyMarkers[clientId]
            if (existingMarker != null) {
                existingMarker.position = latLng
                if (existingMarker.title != effectiveName) {
                    val icon = getMemberIcon(clientId, effectiveName)
                    existingMarker.icon = icon
                    existingMarker.title = effectiveName
                }
                Log.d("MapManager", "Moved existing marker for $clientId to $latLng")
            } else {
                val icon = partyMarkerIcons.getOrPut(clientId) {
                    getMemberIcon(clientId, effectiveName)
                }
                val markerOptions = MarkerOptions()
                    .position(latLng)
                    .title(effectiveName)
                    .icon(icon)
                val newMarker = map.addMarker(markerOptions)
                partyMarkers[clientId] = newMarker
                Log.d("MapManager", "Added new Annotation Marker for $clientId at $latLng with name '$effectiveName' (total active markers: ${partyMarkers.size})")
            }
        }
    }

    /**
     * 指定したパーティメンバーのマーカーを削除します。
     */
    fun removeMemberLocation(clientId: String) {
        Log.d("MapManager", "removeMemberLocation: clientId=$clientId")
        runOnMainThread {
            memberLocations.remove(clientId)
            memberNames.remove(clientId)
            partyMarkers.remove(clientId)?.let { marker ->
                mapLibreMap?.removeMarker(marker)
                Log.d("MapManager", "Removed marker for $clientId")
            }
            partyMarkerIcons.remove(clientId)
        }
    }

    /**
     * 全パーティメンバーのマーカーを削除します。
     */
    fun clearMemberLocations() {
        Log.d("MapManager", "clearMemberLocations: clearing ${partyMarkers.size} markers")
        runOnMainThread {
            memberLocations.clear()
            memberNames.clear()
            partyMarkers.values.forEach { marker ->
                mapLibreMap?.removeMarker(marker)
            }
            partyMarkers.clear()
            partyMarkerIcons.clear()
        }
    }

    /**
     * スタイル再読み込み時などに全パーティメンバーのマーカーを再配置します。
     */
    private fun reapplyAllPartyMarkers() {
        val map = mapLibreMap ?: return
        Log.d("MapManager", "reapplyAllPartyMarkers for ${memberLocations.size} members")
        partyMarkers.values.forEach { map.removeMarker(it) }
        partyMarkers.clear()

        memberLocations.forEach { (clientId, latLng) ->
            val name = memberNames[clientId] ?: context.getString(R.string.party_default_member_name)
            val icon = getMemberIcon(clientId, name)
            val marker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(name)
                    .icon(icon)
            )
            partyMarkers[clientId] = marker
        }
    }

    /**
     * ピン画像の中央の白穴を維持しつつ本体部分を指定色に着色した Bitmap を生成します。
     */
    private fun createTintedMarkerBitmap(original: Bitmap, tintColor: Int): Bitmap {
        val width = original.width
        val height = original.height
        val pixels = IntArray(width * height)
        original.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val alpha = Color.alpha(color)
            if (alpha > 0) {
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                // 中央の白い穴の部分（白系）は白のまま維持し、周囲のピン本体のみを着色
                if (!(r > 220 && g > 220 && b > 220)) {
                    pixels[i] = Color.argb(
                        alpha,
                        Color.red(tintColor),
                        Color.green(tintColor),
                        Color.blue(tintColor)
                    )
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun resetMarker(loadedStyle: Style) {
        val point = Point.fromLngLat(currentLongitude, currentLatitude)
        val feature = Feature.fromGeometry(point)
        val featureCollection = FeatureCollection.fromFeature(feature)

        if (loadedStyle.getSource(SOURCE_ID) == null) {
            val source = GeoJsonSource(SOURCE_ID, featureCollection)
            loadedStyle.addSource(source)
        } else {
            (loadedStyle.getSourceAs(SOURCE_ID) as? GeoJsonSource)?.setGeoJson(featureCollection)
        }
        markerSource = loadedStyle.getSourceAs(SOURCE_ID)

        if (loadedStyle.getImage(ICON_ID) == null) {
            val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.marker)
            if (bitmap != null) {
                loadedStyle.addImage(ICON_ID, bitmap)
            }
        }

        if (loadedStyle.getLayer(LAYER_ID) == null) {
            val layer = SymbolLayer(LAYER_ID, SOURCE_ID).apply {
                setProperties(
                    PropertyFactory.iconImage(ICON_ID),
                    PropertyFactory.iconSize(0.25f),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconOffset(arrayOf(0f, 100f)),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.visibility(Property.NONE)
                )
            }
            loadedStyle.addLayer(layer)
        }
        markerLayer = loadedStyle.getLayerAs(LAYER_ID)

        // すでに位置が更新されていた場合、マーカーを表示
        if (currentLatitude != 0.0 || currentLongitude != 0.0) {
            applyMarkerLocation(currentLatitude, currentLongitude)
        }
    }

    private fun applyTrackUpdate() {
        if (trackPoints.size >= 2) {
            val lineString = LineString.fromLngLats(trackPoints)
            trackSource?.setGeoJson(Feature.fromGeometry(lineString))
        } else if (trackPoints.size == 1) {
            trackSource?.setGeoJson(Feature.fromGeometry(trackPoints[0]))
        }
    }
    fun changeStyleToOSM(context: Context){
        mapView.getMapAsync { map ->
            mapLibreMap = map
            val style = Style.Builder().fromJson(OSM_SATELLITE_STYLE_JSON)
            map.setStyle(style) { loadedStyle ->
                resetMarker(loadedStyle)
                reapplyAllPartyMarkers()
            }
        }
        attributionTextView?.text = context.getString(R.string.attribution_osm)
    }
    fun changeStyleToGSI(context: Context){
        mapView.getMapAsync { map ->
            mapLibreMap = map
            val style = Style.Builder().fromJson(GSI_SATELLITE_STYLE_JSON)
            map.setStyle(style) { loadedStyle ->
                resetMarker(loadedStyle)
                reapplyAllPartyMarkers()
            }
        }
        attributionTextView?.text = context.getString(R.string.attribution_gsi)
    }
    fun jumpToMarker(){
        mapView.getMapAsync { map ->
            if (currentLatitude != 0.0 || currentLongitude != 0.0) {
                map.animateCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(currentLatitude, currentLongitude))
                        .zoom(DEFAULT_ZOOM * 1.3)
                        .tilt(DEFAULT_TILT)
                        .build()
                ))
            }else{
                showToast(context.getString(R.string.toast_no_location))
            }
        }
    }
    fun showToast(text: String) {
        Snackbar.make(mapView, text, Snackbar.LENGTH_LONG)
            .setBackgroundTint(context.getColor(R.color.snackbar_background))
            .show()
    }
    // --- ライフサイクルメソッド ---

    fun onStart() {
        mapView.onStart()
    }

    fun onResume() {
        mapView.onResume()
    }

    fun onPause() {
        mapView.onPause()
    }

    fun onStop() {
        mapView.onStop()
    }

    fun onDestroy() {
        mapView.onDestroy()
    }

    fun onLowMemory() {
        mapView.onLowMemory()
    }

    fun onSaveInstanceState(outState: Bundle) {
        mapView.onSaveInstanceState(outState)
    }
}
