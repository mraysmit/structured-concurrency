package dev.mars.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.mars.weather.WeatherRequest;
import dev.mars.weather.WeatherResponse;
import dev.mars.weather.WeatherService;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for WeatherClient.
 */
class WeatherClientTest {

    @Test
    void fetchSuccessfulReturnsOnlySuccessfulResponses() throws InterruptedException {
        AtomicInteger attempts = new AtomicInteger();
        WeatherService service = request -> {
            attempts.incrementAndGet();
            if ("Paris".equals(request.city())) {
                throw new IllegalStateException("simulated failure");
            }
            return new WeatherResponse(request.city(), 20, "sunny");
        };
        WeatherClient client = new WeatherClient(service);
        LocalDate today = LocalDate.now();
        List<WeatherRequest> requests = List.of(
                new WeatherRequest("default", "GB", "London", today),
                new WeatherRequest("default", "FR", "Paris", today),
                new WeatherRequest("default", "DE", "Berlin", today));

        List<TimedResponse> responses = client.fetchSuccessful(requests);

        Set<String> cities = responses.stream()
                .map(response -> response.response().city())
                .collect(Collectors.toSet());
        assertEquals(Set.of("London", "Berlin"), cities);
        assertEquals(requests.size(), attempts.get());
    }
}
