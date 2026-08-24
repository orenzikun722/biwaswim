package com.rencon.biwaswim.nmea

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.geojson.GeoJsonReader
import org.locationtech.jts.operation.distance.DistanceOp
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

fun calculateDistance(context: Context, latitude: Double, longitude: Double) : Double {
    val geoJson = context.assets
        .open("biwako.geojson")
        .bufferedReader()
        .use { it.readText() }
    val geometry = GeoJsonReader().read(
        JSONObject(geoJson)
            .getJSONArray("features")
            .getJSONObject(0)
            .getJSONObject("geometry")
            .toString()
    )
    val factory = GeometryFactory()
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