package com.lifeops.app.util

/**
 * Maps an NWS short-forecast phrase ("Sunny", "Chance Showers", "Patchy Fog") to a single emoji
 * glyph for the compact weekly-forecast strip. Pure and order-sensitive: the most weather-defining
 * words are checked first (storm before rain before clouds) so "Sunny then Thunderstorms" reads as
 * a storm, not sun.
 */
object WeatherGlyph {

    fun forShortForecast(text: String?): String {
        val t = text?.lowercase() ?: return "🌡️"
        return when {
            "thunder" in t || "storm" in t -> "⛈️"
            "snow" in t || "flurries" in t || "sleet" in t || "wintry" in t -> "🌨️"
            "rain" in t || "shower" in t || "drizzle" in t -> "🌧️"
            "fog" in t || "haze" in t || "mist" in t -> "🌫️"
            "mostly cloudy" in t || "overcast" in t -> "☁️"
            "partly cloudy" in t || "partly sunny" in t || "mostly sunny" in t -> "⛅"
            "cloud" in t -> "☁️"
            "clear" in t || "sunny" in t || "fair" in t -> "☀️"
            else -> "🌡️"
        }
    }
}
