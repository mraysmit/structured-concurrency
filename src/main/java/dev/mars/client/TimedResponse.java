package dev.mars.client;

import dev.mars.weather.WeatherResponse;

/**
 * A weather response paired with the elapsed time taken to retrieve it.
 */
public record TimedResponse(String provider, WeatherResponse response, long elapsedMs) {
}
