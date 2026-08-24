package com.rencon.biwaswim.map

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import com.rencon.biwaswim.R
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import com.rencon.biwaswim.nmea.calculateDistance

/**
 * MapLibre 地図の初期化、スタイル設定、マーカーレイヤーの管理、ライフサイクル委譲を行うクラス。
 */
class MapManager(
    private val context: Context,
    private val mapView: MapView
) {

    companion object {
        private const val SOURCE_ID = "marker-source"
        private const val LAYER_ID = "marker-layer"
        private const val ICON_ID = "marker-icon"

        private const val DEFAULT_LAT = 35.25
        private const val DEFAULT_LNG = 136.1
        private const val DEFAULT_ZOOM = 10.0
        private const val DEFAULT_TILT = 60.0

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
    }

    private var markerSource: GeoJsonSource? = null
    private var markerLayer: SymbolLayer? = null

    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0

    /**
     * MapLibre インスタンスと MapView の初期化を行います。
     */
    fun initialize(savedInstanceState: Bundle?, onMapReady: (() -> Unit)? = null) {
        mapView.onCreate(savedInstanceState)

        mapView.getMapAsync { map ->
            val style = Style.Builder().fromJson(GSI_SATELLITE_STYLE_JSON)
            map.setStyle(style) { loadedStyle ->
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
                        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                        PropertyFactory.visibility(Property.NONE)
                    )
                }

                loadedStyle.addLayer(layer)
                markerSource = loadedStyle.getSourceAs(SOURCE_ID)
                markerLayer = loadedStyle.getLayerAs(LAYER_ID)

                // すでに位置が更新されていた場合、マーカーを表示
                if (currentLatitude != 0.0 || currentLongitude != 0.0) {
                    applyMarkerLocation(currentLatitude, currentLongitude)
                }

                onMapReady?.invoke()
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(DEFAULT_LAT, DEFAULT_LNG))
                .zoom(DEFAULT_ZOOM)
                .tilt(DEFAULT_TILT)
                .build()
        }
    }

    /**
     * 現在位置を更新し、マーカーを移動・表示します。
     */
    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
        applyMarkerLocation(latitude, longitude)
    }
    private fun applyMarkerLocation(latitude: Double, longitude: Double) {
        markerSource?.setGeoJson(Point.fromLngLat(longitude, latitude))
        markerLayer?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
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
