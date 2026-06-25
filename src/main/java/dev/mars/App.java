package dev.mars;

import dev.mars.client.TimedResponse;
import dev.mars.client.WeatherClient;
import dev.mars.weather.WeatherRequest;
import dev.mars.weather.WeatherService;
import dev.mars.weather.sim.SimulatedWeatherService;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Structured concurrency demo application.
 */
public final class App {

    private App() {
    }

    /**
     * Entry point for the structured concurrency demo.
     *
     * @param args command-line arguments (not used)
     * @throws InterruptedException if the current thread is interrupted
     */
    public static void main(String[] args) throws InterruptedException {
        runPhase1();
        runPhase2();
        runPhase3();
        runPhase4();
        runPhase5();
    }

    private static void runPhase1() throws InterruptedException {
        System.out.println("=== Phase 1: Sequential Baseline ===");
        WeatherClient client = new WeatherClient(new SimulatedWeatherService());
        LocalDate today = LocalDate.now();
        List<WeatherRequest> requests = List.of(
                new WeatherRequest("default", "GB", "London", today),
                new WeatherRequest("default", "FR", "Paris", today),
                new WeatherRequest("default", "DE", "Berlin", today),
                new WeatherRequest("default", "ES", "Madrid", today),
                new WeatherRequest("default", "IT", "Rome", today));

        long start = System.currentTimeMillis();
        List<TimedResponse> responses = client.fetchAll(requests);
        long total = System.currentTimeMillis() - start;

        for (TimedResponse tr : responses) {
            System.out.printf("  %s: %d\u00b0C, %s (%d ms)%n",
                    tr.response().city(), tr.response().temperature(),
                    tr.response().condition(), tr.elapsedMs());
        }
        System.out.printf("Total time: %d ms%n%n", total);
    }

    private static void runPhase2() throws InterruptedException {
        System.out.println("=== Phase 2: Concurrent Fan-out (ShutdownOnFailure) ===");
        WeatherClient client = new WeatherClient(new SimulatedWeatherService());
        LocalDate today = LocalDate.now();
        List<WeatherRequest> requests = List.of(
                new WeatherRequest("default", "GB", "London", today),
                new WeatherRequest("default", "FR", "Paris", today),
                new WeatherRequest("default", "DE", "Berlin", today),
                new WeatherRequest("default", "ES", "Madrid", today),
                new WeatherRequest("default", "IT", "Rome", today));
        long start = System.currentTimeMillis();
        List<TimedResponse> responses = client.fetchAllConcurrent(requests);
        long total = System.currentTimeMillis() - start;
        for (TimedResponse tr : responses) {
            System.out.printf("  %s: %d\u00b0C, %s (%d ms)%n",
                    tr.response().city(), tr.response().temperature(),
                    tr.response().condition(), tr.elapsedMs());
        }
        System.out.printf("Total time: %d ms%n%n", total);
    }

    private static void runPhase3() throws InterruptedException {
        System.out.println("=== Phase 3: Race per City (anySuccessfulOrThrow, 3 providers) ===");
        WeatherClient client = new WeatherClient(new SimulatedWeatherService());
        LocalDate today = LocalDate.now();
        String[][] cities = {
            {"GB", "London"}, {"FR", "Paris"}, {"DE", "Berlin"},
            {"ES", "Madrid"}, {"IT", "Rome"}
        };
        List<String> providers = List.of("OpenWeather", "AccuWeather", "WeatherAPI");
        long start = System.currentTimeMillis();
        for (String[] city : cities) {
            List<WeatherRequest> providerRequests = providers.stream()
                    .map(p -> new WeatherRequest(p, city[0], city[1], today))
                    .toList();
            TimedResponse tr = client.fetchFirst(providerRequests);
            System.out.printf("  %s: %d\u00b0C, %s (%d ms) [%s]%n",
                    tr.response().city(), tr.response().temperature(),
                    tr.response().condition(), tr.elapsedMs(), tr.provider());
        }
        long total = System.currentTimeMillis() - start;
        System.out.printf("Total time: %d ms%n%n", total);
    }

    private static void runPhase4() throws InterruptedException {
        Duration timeout = Duration.ofSeconds(1);
        System.out.println("=== Phase 4: Timeout (" + timeout.toSeconds() + "s deadline) ===");
        WeatherClient client = new WeatherClient(new SimulatedWeatherService());
        LocalDate today = LocalDate.now();
        List<WeatherRequest> requests = List.of(
                new WeatherRequest("default", "GB", "London", today),
                new WeatherRequest("default", "FR", "Paris", today),
                new WeatherRequest("default", "DE", "Berlin", today),
                new WeatherRequest("default", "ES", "Madrid", today),
                new WeatherRequest("default", "IT", "Rome", today));
        long start = System.currentTimeMillis();
        List<Optional<TimedResponse>> results = client.fetchAllWithTimeout(requests, timeout);
        long total = System.currentTimeMillis() - start;
        int completed = 0;
        for (int i = 0; i < requests.size(); i++) {
            Optional<TimedResponse> result = results.get(i);
            if (result.isPresent()) {
                TimedResponse tr = result.get();
                System.out.printf("  %s: %d\u00b0C, %s (%d ms) \u2713%n",
                        tr.response().city(), tr.response().temperature(),
                        tr.response().condition(), tr.elapsedMs());
                completed++;
            } else {
                System.out.printf("  %s: timed out%n", requests.get(i).city());
            }
        }
        System.out.printf("Completed %d/%d, Total time: %d ms%n%n",
                completed, requests.size(), total);
    }

    private static void runPhase5() throws InterruptedException {
        System.out.println("=== Phase 5: Partial Results (custom Joiner) ===");
        WeatherService delegate = new SimulatedWeatherService();
        WeatherClient client = new WeatherClient(request -> {
            if ("Paris".equals(request.city()) || "Madrid".equals(request.city())) {
                throw new IllegalStateException("simulated provider failure");
            }
            return delegate.fetch(request);
        });
        LocalDate today = LocalDate.now();
        List<WeatherRequest> requests = List.of(
                new WeatherRequest("default", "GB", "London", today),
                new WeatherRequest("default", "FR", "Paris", today),
                new WeatherRequest("default", "DE", "Berlin", today),
                new WeatherRequest("default", "ES", "Madrid", today),
                new WeatherRequest("default", "IT", "Rome", today));
        long start = System.currentTimeMillis();
        List<TimedResponse> responses = client.fetchSuccessful(requests);
        long total = System.currentTimeMillis() - start;
        for (TimedResponse tr : responses) {
            System.out.printf("  %s: %d\u00b0C, %s (%d ms)%n",
                    tr.response().city(), tr.response().temperature(),
                    tr.response().condition(), tr.elapsedMs());
        }
        System.out.printf("Collected %d/%d successful results, Total time: %d ms%n%n",
                responses.size(), requests.size(), total);
    }
}


