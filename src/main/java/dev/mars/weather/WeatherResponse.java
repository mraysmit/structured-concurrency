package dev.mars.weather;

/**
 * Represents a weather forecast response for a city.
 */
public record WeatherResponse(String city, int temperature, String condition) {
}
