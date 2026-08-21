package com.rencon.biwaswim.nmea

data class GpsLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val speedKnots: Double? = null,
    val courseDegrees: Double? = null,
    val satellites: Int? = null,
    val fixQuality: Int? = null,
    val utcTime: String? = null
)
