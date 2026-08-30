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
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
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

        private const val PARTY_MARKER_SOURCE_ID = "party-marker-source"
        private const val PARTY_MARKER_LAYER_ID = "party-marker-layer"
        private const val PARTY_ICON_PREFIX = "party-marker-icon-"

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
    private var partyMarkerSource: GeoJsonSource? = null
    private var partyMarkerLayer: SymbolLayer? = null
    private val memberLocations = mutableMapOf<String, LatLng>()

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
                val tSource = GeoJsonSource(TRACK_SOURCE_ID, initialTrackFeatureCollection)
                loadedStyle.addSource(tSource)
                trackSource = loadedStyle.getSourceAs(TRACK_SOURCE_ID)

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
                trackLayer = loadedStyle.getLayerAs(TRACK_LAYER_ID)

                // 2. マーカー用 Source & SymbolLayer の初期化（LineLayerの上に描画）
                resetMarker(loadedStyle)

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
     * パーティメンバーのマーカー用画像・レイヤーの初期化・更新
     */
    private fun setupPartyMarkers(loadedStyle: Style) {
        val baseBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.marker)
        PARTY_MEMBER_COLORS.forEachIndexed { index, color ->
            val tinted = createTintedMarkerBitmap(baseBitmap, color)
            loadedStyle.addImage("$PARTY_ICON_PREFIX$index", tinted)
        }

        val features = memberLocations.map { (clientId, latLng) ->
            val feature = Feature.fromGeometry(Point.fromLngLat(latLng.longitude, latLng.latitude))
            val colorIndex = (Math.abs(clientId.hashCode()) % PARTY_MEMBER_COLORS.size)
            feature.addStringProperty("iconImage", "$PARTY_ICON_PREFIX$colorIndex")
            feature.addStringProperty("label", clientId)
            feature
        }
        val featureCollection = FeatureCollection.fromFeatures(features)
        val source = GeoJsonSource(PARTY_MARKER_SOURCE_ID, featureCollection)

        loadedStyle.addSource(source)
        partyMarkerSource = loadedStyle.getSourceAs(PARTY_MARKER_SOURCE_ID)

        val layer = SymbolLayer(PARTY_MARKER_LAYER_ID, PARTY_MARKER_SOURCE_ID).apply {
            setProperties(
                PropertyFactory.iconImage(Expression.get("iconImage")),
                PropertyFactory.iconSize(0.25f),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconOffset(arrayOf(0f, 100f)),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textSize(11f),
                PropertyFactory.textColor(Color.WHITE),
                PropertyFactory.textHaloColor(Color.BLACK),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.6f)),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.visibility(Property.VISIBLE)
            )
        }
        loadedStyle.addLayer(layer)
        partyMarkerLayer = loadedStyle.getLayerAs(PARTY_MARKER_LAYER_ID)
    }

    /**
     * パーティメンバーの位置を更新または追加します。
     */
    fun updateMemberLocation(clientId: String, latitude: Double, longitude: Double) {
        runOnMainThread {
            memberLocations[clientId] = LatLng(latitude, longitude)
            applyPartyMarkersUpdate()
        }
    }

    /**
     * 指定したパーティメンバーのマーカーを削除します。
     */
    fun removeMemberLocation(clientId: String) {
        runOnMainThread {
            memberLocations.remove(clientId)
            applyPartyMarkersUpdate()
        }
    }

    /**
     * 全パーティメンバーのマーカーを削除します。
     */
    fun clearMemberLocations() {
        runOnMainThread {
            memberLocations.clear()
            applyPartyMarkersUpdate()
        }
    }

    private fun applyPartyMarkersUpdate() {
        val features = memberLocations.map { (clientId, latLng) ->
            val feature = Feature.fromGeometry(Point.fromLngLat(latLng.longitude, latLng.latitude))
            val colorIndex = (Math.abs(clientId.hashCode()) % PARTY_MEMBER_COLORS.size)
            feature.addStringProperty("iconImage", "$PARTY_ICON_PREFIX$colorIndex")
            feature.addStringProperty("label", clientId)
            feature
        }
        partyMarkerSource?.setGeoJson(FeatureCollection.fromFeatures(features))
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
        val source = GeoJsonSource(SOURCE_ID, featureCollection)

        loadedStyle.addSource(source)
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.marker)
        loadedStyle.addImage(ICON_ID, bitmap)

        val layer = SymbolLayer(LAYER_ID, SOURCE_ID).apply {
            setProperties(
                PropertyFactory.iconImage(ICON_ID),
                PropertyFactory.iconSize(0.25f),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconOffset(arrayOf(0f, 100f)),
                PropertyFactory.visibility(Property.NONE)
            )
        }

        loadedStyle.addLayer(layer)
        markerSource = loadedStyle.getSourceAs(SOURCE_ID)
        markerLayer = loadedStyle.getLayerAs(LAYER_ID)

        // パーティメンバーのマーカーもスタイル再読み込み時にセットアップ
        setupPartyMarkers(loadedStyle)

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
            map.setStyle(style)
            map.getStyle { loadedStyle ->
                resetMarker(loadedStyle)
            }
        }
        attributionTextView?.text = context.getString(R.string.attribution_osm)
    }
    fun changeStyleToGSI(context: Context){
        mapView.getMapAsync { map ->
            mapLibreMap = map
            val style = Style.Builder().fromJson(GSI_SATELLITE_STYLE_JSON)
            map.setStyle(style)
            map.getStyle { loadedStyle ->
                resetMarker(loadedStyle)
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
