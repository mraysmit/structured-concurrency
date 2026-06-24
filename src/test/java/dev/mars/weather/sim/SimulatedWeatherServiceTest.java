package dev.mars.weather.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mars.weather.WeatherRequest;
import dev.mars.weather.WeatherResponse;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for SimulatedWeatherService.
 */
class SimulatedWeatherServiceTest {

    private static final long TEST_SEED = 42L;
    private static final int MIN_TEMP = -10;
    private static final int MAX_TEMP = 40;
    private static final Set<String> VALID_CONDITIONS = Set.of(
            "sunny", "partly cloudy", "cloudy", "overcast",
            "drizzle", "rain", "heavy rain", "thunderstorm", "snow", "fog");

    @Test
    void fetchReturnsCityMatchingRequest() throws InterruptedException {
        SimulatedWeatherService service = new SimulatedWeatherService(TEST_SEED);
        WeatherRequest request = new WeatherRequest("", "GB", "London", LocalDate.now());

        WeatherResponse response = service.fetch(request);

        assertEquals("London", response.city());
    }

    @Test
    void fetchReturnsTemperatureWithinRange() throws InterruptedException {
        SimulatedWeatherService service = new SimulatedWeatherService(TEST_SEED);
        WeatherRequest request = new WeatherRequest("", "GB", "London", LocalDate.now());

        WeatherResponse response = service.fetch(request);

        assertTrue(response.temperature() >= MIN_TEMP,
                "Temperature should be >= " + MIN_TEMP);
        assertTrue(response.temperature() <= MAX_TEMP,
                "Temperature should be <= " + MAX_TEMP);
    }

    @Test
    void fetchReturnsValidCondition() throws InterruptedException {
        SimulatedWeatherService service = new SimulatedWeatherService(TEST_SEED);
        WeatherRequest request = new WeatherRequest("", "GB", "London", LocalDate.now());

        WeatherResponse response = service.fetch(request);

        assertTrue(VALID_CONDITIONS.contains(response.condition()),
                "Condition '" + response.condition() + "' is not a valid weather condition");
    }

    @Test
    void sameSeedProducesSameResult() throws InterruptedException {
        WeatherRequest request = new WeatherRequest("", "FR", "Paris", LocalDate.now());

        WeatherResponse response1 = new SimulatedWeatherService(TEST_SEED).fetch(request);
        WeatherResponse response2 = new SimulatedWeatherService(TEST_SEED).fetch(request);

        assertEquals(response1.temperature(), response2.temperature());
        assertEquals(response1.condition(), response2.condition());
    }
}
