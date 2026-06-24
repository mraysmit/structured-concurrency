package dev.mars.weather;

import java.time.LocalDate;

/**
 * Represents a weather forecast request for a specific city and date.
 */
public record WeatherRequest(String provider, String country, String city, LocalDate date) {
}
