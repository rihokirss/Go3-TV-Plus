package ee.local.go3tvplus.data.local

import ee.local.go3tvplus.domain.SeaForecastPosition
import ee.local.go3tvplus.domain.SeaLocationPreferences
import ee.local.go3tvplus.domain.SeaPoint
import kotlinx.serialization.json.*

internal object SeaLocationPreferencesCodec {
    fun encode(value: SeaLocationPreferences): String = buildJsonObject {
        put("first", point(value.first))
        put("second", point(value.second))
        put("forecastPosition", value.forecastPosition.name)
    }.toString()

    fun decode(raw: String?): SeaLocationPreferences = runCatching {
        val json = Json.parseToJsonElement(raw ?: return SeaLocationPreferences()).jsonObject
        SeaLocationPreferences(
            first = readPoint(json.getValue("first")),
            second = readPoint(json.getValue("second")),
            forecastPosition = SeaForecastPosition.valueOf(json.getValue("forecastPosition").jsonPrimitive.content),
        )
    }.getOrDefault(SeaLocationPreferences())

    private fun point(value: SeaPoint) = buildJsonObject {
        put("name", value.name)
        put("stationName", value.stationName)
        put("latitude", value.latitude)
        put("longitude", value.longitude)
    }

    private fun readPoint(element: JsonElement): SeaPoint = element.jsonObject.let {
        val latitude = it.getValue("latitude").jsonPrimitive.double
        val longitude = it.getValue("longitude").jsonPrimitive.double
        val name = it.getValue("name").jsonPrimitive.content
        val stationName = it.getValue("stationName").jsonPrimitive.content
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(name.isNotBlank() && stationName.isNotBlank())
        SeaPoint(name, stationName, latitude, longitude)
    }
}
