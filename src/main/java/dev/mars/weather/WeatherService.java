package dev.mars.weather;

/**
 * Service interface for fetching weather forecasts.
 */
public interface WeatherService {

    /**
     * Fetches the weather forecast for the given request.
     *
     * @param request the weather request
     * @return the weather response
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    WeatherResponse fetch(WeatherRequest request) throws InterruptedException;
}
