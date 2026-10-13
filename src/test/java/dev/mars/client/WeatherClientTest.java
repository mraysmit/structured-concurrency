package dev.mars.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mars.weather.WeatherRequest;
import dev.mars.weather.WeatherResponse;
import dev.mars.weather.WeatherService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for WeatherClient.
 */
@Timeout(5)
class WeatherClientTest {

    @Test
    void fetchAllConcurrentPreservesRequestOrder() throws InterruptedException {
        CountDownLatch secondStarted = new CountDownLatch(1);
        WeatherClient client = new WeatherClient(request -> {
            if ("London".equals(request.city())) {
                secondStarted.await();
            } else {
                secondStarted.countDown();
            }
            return new WeatherResponse(request.city(), 20, "sunny");
        });
        List<WeatherRequest> requests = List.of(request("London"), request("Paris"));

        List<TimedResponse> responses = client.fetchAllConcurrent(requests);

        assertEquals(List.of("London", "Paris"), responses.stream()
                .map(response -> response.response().city()).toList());
    }

    @Test
    void fetchAllConcurrentCancelsUnfinishedLookupsOnFailure() {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("provider failed");
        WeatherClient client = new WeatherClient(request -> {
            if ("Paris".equals(request.city())) {
                slowStarted.await();
                throw failure;
            }
            slowStarted.countDown();
            try {
                blocked.await();
            } catch (InterruptedException ex) {
                interrupted.set(true);
                throw ex;
            }
            return new WeatherResponse(request.city(), 20, "sunny");
        });

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> client.fetchAllConcurrent(List.of(request("London"), request("Paris"))));

        assertEquals(failure, thrown.getCause());
        assertTrue(interrupted.get());
    }

    @Test
    void fetchFirstCancelsLosingProvider() throws InterruptedException {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        WeatherClient client = new WeatherClient(request -> {
            if ("winner".equals(request.provider())) {
                slowStarted.await();
                return new WeatherResponse(request.city(), 20, "sunny");
            }
            slowStarted.countDown();
            try {
                blocked.await();
            } catch (InterruptedException ex) {
                interrupted.set(true);
                throw ex;
            }
            return new WeatherResponse(request.city(), 10, "rain");
        });
        LocalDate today = LocalDate.now();

        TimedResponse response = client.fetchFirst(List.of(
                new WeatherRequest("slow", "GB", "London", today),
                new WeatherRequest("winner", "GB", "London", today)));

        assertEquals("winner", response.provider());
        assertTrue(interrupted.get());
    }

    @Test
    void fetchAllWithTimeoutKeepsSuccessesAndCancelsSlowLookups() throws InterruptedException {
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        WeatherClient client = new WeatherClient(request -> {
            if ("Paris".equals(request.city())) {
                try {
                    blocked.await();
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    throw ex;
                }
            }
            return new WeatherResponse(request.city(), 20, "sunny");
        });

        var responses = client.fetchAllWithTimeout(
                List.of(request("London"), request("Paris")), Duration.ofMillis(500));

        assertEquals(2, responses.size());
        assertEquals("London", responses.get(0).orElseThrow().response().city());
        assertFalse(responses.get(1).isPresent());
        assertTrue(interrupted.get());
    }

    @Test
    void fetchFastestForEachCityReturnsOneResponsePerCity() throws InterruptedException {
        WeatherClient client = new WeatherClient(request -> new WeatherResponse(request.city(), 20, "sunny"));

        List<TimedResponse> responses = client.fetchFastestForEachCity(
                List.of(request("London"), request("Paris")), List.of("provider1", "provider2"));

        assertEquals(List.of("London", "Paris"), responses.stream()
                .map(response -> response.response().city()).toList());
        assertTrue(responses.stream().allMatch(response ->
                Set.of("provider1", "provider2").contains(response.provider())));
    }

    private static WeatherRequest request(String city) {
        return new WeatherRequest("default", "GB", city, LocalDate.now());
    }

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
