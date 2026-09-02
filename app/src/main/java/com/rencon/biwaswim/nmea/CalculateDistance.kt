package com.rencon.biwaswim.nmea

import android.content.Context
import android.location.Location
import org.json.JSONObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.geojson.GeoJsonReader
import org.locationtech.jts.operation.distance.DistanceOp
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

private object BiwakoGeometryHolder {
    @Volatile
    private var geometry: Geometry? = null
    private val factory = GeometryFactory()

    fun getGeometry(context: Context): Geometry {
        return geometry ?: synchronized(this) {
            geometry ?: run {
                val geoJson = context.applicationContext.assets
                    .open("biwako.geojson")
                    .bufferedReader()
                    .use { it.readText() }
                val parsed = GeoJsonReader().read(
                    JSONObject(geoJson)
                        .getJSONArray("features")
                        .getJSONObject(0)
                        .getJSONObject("geometry")
                        .toString()
                )
                geometry = parsed
                parsed
            }
        }
    }

    fun getFactory(): GeometryFactory = factory
}

fun calculateDistance(context: Context, latitude: Double, longitude: Double): Double {
    val geometry = BiwakoGeometryHolder.getGeometry(context)
    val factory = BiwakoGeometryHolder.getFactory()
    val point = factory.createPoint(
        Coordinate(longitude, latitude)
    )
    val nearest = DistanceOp.nearestPoints(
        point,
        geometry.boundary
    )[1]
    val nearestX = nearest.x
    val nearestY = nearest.y

    val lat = Math.toRadians((longitude + nearestX) / 2.0)

    val latMeters = (longitude - nearestX) * 111_000.0
    val lonMeters = (latitude - nearestY) * 111_000.0 * cos(lat)

    return sqrt(
        latMeters.pow(2) +
                lonMeters.pow(2)
    )
}

object SwimDebugConfig {
    var isForceSwimming: Boolean = false
}

fun isSwimming(context: Context, latitude: Double, longitude: Double): Boolean {
    if (SwimDebugConfig.isForceSwimming) {
        return true
    }
    val geometry = BiwakoGeometryHolder.getGeometry(context)
    val factory = BiwakoGeometryHolder.getFactory()
    val point = factory.createPoint(
        Coordinate(longitude, latitude)
    )
    return geometry.contains(point)
}

/**
 * 2点間の距離（メートル）を計算します。
 */
fun calculateDistanceBetween(
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double
): Float {
    val results = FloatArray(1)
    Location.distanceBetween(startLatitude, startLongitude, endLatitude, endLongitude, results)
    return results[0]
}