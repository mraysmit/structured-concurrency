package dev.mars.client;

import dev.mars.weather.WeatherRequest;
import dev.mars.weather.WeatherResponse;
import dev.mars.weather.WeatherService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.StructuredTaskScope;

/**
 * Client that fetches weather forecasts for a list of cities using a WeatherService.
 */
public final class WeatherClient {

    private final WeatherService service;

    /**
     * Constructs a WeatherClient backed by the given service.
     *
     * @param service the weather service to use for fetching forecasts
     */
    public WeatherClient(WeatherService service) {
        this.service = service;
    }

    /**
     * Example 1: sequential baseline.
     *
     * <p>Fetches weather forecasts one request at a time and records how long each call takes.
     * This provides the comparison point for the structured concurrency examples.
     *
     * @param requests the list of weather requests
     * @return a list of timed responses in the same order as the requests
     * @throws InterruptedException if the current thread is interrupted
     */
    public List<TimedResponse> fetchAll(List<WeatherRequest> requests) throws InterruptedException {
        List<TimedResponse> results = new ArrayList<>(requests.size());

        for (WeatherRequest request : requests) {
            long start = System.currentTimeMillis();
            WeatherResponse response = service.fetch(request);
            results.add(new TimedResponse(request.provider(), response, System.currentTimeMillis() - start));
        }
        return results;
    }


    /**
     * Example 2: concurrent fan-out with all-or-fail semantics.
     *
     * <p>Forks one structured subtask per request and waits for every subtask to complete
     * successfully. If any lookup fails, {@code Joiner.allSuccessfulOrThrow()} fails the scope
     * and the remaining work is cancelled.
     *
     * @param requests the list of weather requests
     * @return a list of timed responses in the same order as the requests
     * @throws InterruptedException if the current thread is interrupted
     */
    public List<TimedResponse> fetchAllConcurrent(List<WeatherRequest> requests) throws InterruptedException {

        try (var scope = StructuredTaskScope.<TimedResponse, List<TimedResponse>>open(StructuredTaskScope.Joiner.allSuccessfulOrThrow())) {
            for (WeatherRequest request : requests) {
                scope.fork(() -> {
                    long start = System.currentTimeMillis();
                    WeatherResponse response = service.fetch(request);
                    return new TimedResponse(request.provider(), response, System.currentTimeMillis() - start);
                });
            }
            return scope.join();
        }
    }

    /**
     * Example 7: concurrent fan-out that cancels unfinished work after a failure.
     *
     * <p>This uses the same all-or-fail joiner as the concurrent fan-out example. Once one
     * subtask fails, the structured scope cancels unfinished sibling subtasks and reports the
     * failure to the caller.
     *
     * @param requests the list of weather requests
     * @return a list of timed responses in the same order as the requests
     * @throws InterruptedException if the current thread is interrupted
     */
    public List<TimedResponse> fetchAllCancellingOnFailure(List<WeatherRequest> requests)
            throws InterruptedException {
        return fetchAllConcurrent(requests);
    }

    /**
     * Example 3: first-success provider race.
     *
     * <p>Forks one structured subtask per provider request and returns the first successful
     * response. {@code Joiner.anySuccessfulOrThrow()} cancels the remaining provider calls once
     * a winner is available.
     *
     * @param providerRequests one {@link WeatherRequest} per provider (same city, different provider name)
     * @return the timed response from whichever provider replied first
     * @throws InterruptedException if the current thread is interrupted
     */
    public TimedResponse fetchFirst(List<WeatherRequest> providerRequests) throws InterruptedException {
        try (var scope = StructuredTaskScope.<TimedResponse, TimedResponse>open(
                StructuredTaskScope.Joiner.anySuccessfulOrThrow())) {
            for (WeatherRequest request : providerRequests) {
                scope.fork(() -> {
                    long start = System.currentTimeMillis();
                    WeatherResponse response = service.fetch(request);
                    return new TimedResponse(request.provider(), response, System.currentTimeMillis() - start);
                });
            }
            return scope.join();
        }
    }

    /**
     * Example 4: concurrent fan-out with a shared timeout.
     *
     * <p>Forks one structured subtask per request and waits until all subtasks complete or the
     * configured timeout expires. Each result is represented as an {@code Optional}: present when
     * the lookup completed before the deadline, empty when it was cancelled by the timeout.
     *
     * @param requests the list of weather requests
     * @param timeout  the maximum time to wait for all responses
     * @return a list of optionals in the same order as the requests
     * @throws InterruptedException if the current thread is interrupted
     */
    public List<Optional<TimedResponse>> fetchAllWithTimeout(List<WeatherRequest> requests, Duration timeout) throws InterruptedException {
        
        try (var scope = StructuredTaskScope.<TimedResponse, Void>open(StructuredTaskScope.Joiner.awaitAll(), config -> config.withTimeout(timeout))) {
            List<StructuredTaskScope.Subtask<TimedResponse>> subtasks =new ArrayList<>(requests.size());

            for (WeatherRequest request : requests) {
                subtasks.add(scope.fork(() -> {
                    long start = System.currentTimeMillis();
                    WeatherResponse response = service.fetch(request);
                    return new TimedResponse(request.provider(), response, System.currentTimeMillis() - start);
                }));
            }

            try {
                scope.join();
            } catch (StructuredTaskScope.TimeoutException ignored) {
                // deadline expired — check subtask states below to collect partial results
            }

            List<Optional<TimedResponse>> results = new ArrayList<>(subtasks.size());

            for (StructuredTaskScope.Subtask<TimedResponse> subtask : subtasks) {
                if (subtask.state() == StructuredTaskScope.Subtask.State.SUCCESS) {
                    results.add(Optional.of(subtask.get()));
                } else {
                    results.add(Optional.empty());
                }
            }
            return results;
        }
    }

    /**
     * Example 5: partial results with a custom joiner.
     *
     * <p>Forks one structured subtask per request and collects only successful responses.
     * Failed subtasks are ignored, so one failed lookup does not cancel or fail the whole
     * operation.
     *
     * @param requests the list of weather requests
     * @return the successful timed responses
     * @throws InterruptedException if the current thread is interrupted
     */
    public List<TimedResponse> fetchSuccessful(List<WeatherRequest> requests) throws InterruptedException {
        try (var scope = StructuredTaskScope.<TimedResponse, List<TimedResponse>>open(new SuccessfulResponsesJoiner())) {
            for (WeatherRequest request : requests) {
                scope.fork(() -> {
                    long start = System.currentTimeMillis();
                    WeatherResponse response = service.fetch(request);
                    return new TimedResponse(request.provider(), response, System.currentTimeMillis() - start);
                });
            }
            return scope.join();
        }
    }

    /**
     * Example 6: nested structured concurrency.
     *
     * <p>Forks one structured subtask per city. Each city subtask then starts its own structured
     * scope to race the configured providers and return the first successful provider response for
     * that city.
     *
     * @param cityRequests one request per city
     * @param providers    the provider names to race for each city
     * @return one winning timed response per city, in the same order as the city requests
     * @throws InterruptedException if the current thread is interrupted
     */
    public List<TimedResponse> fetchFastestForEachCity(List<WeatherRequest> cityRequests, List<String> providers) throws InterruptedException {

        try (var scope = StructuredTaskScope.<TimedResponse, List<TimedResponse>>open(StructuredTaskScope.Joiner.allSuccessfulOrThrow())) {
            for (WeatherRequest cityRequest : cityRequests) {
                scope.fork(() -> {
                    List<WeatherRequest> providerRequests = providers.stream()
                        .map(provider -> new WeatherRequest(
                                provider,
                                cityRequest.country(),
                                cityRequest.city(),
                                cityRequest.date()))
                        .toList();
                        // 
                    return fetchFirst(providerRequests);
                });
            }
            return scope.join();
        }
    }

    private static final class SuccessfulResponsesJoiner implements StructuredTaskScope.Joiner<TimedResponse, List<TimedResponse>> {

        private final Queue<TimedResponse> responses = new ConcurrentLinkedQueue<>();

        @Override
        public boolean onComplete(StructuredTaskScope.Subtask<TimedResponse> subtask) {
            if (subtask.state() == StructuredTaskScope.Subtask.State.SUCCESS) {
                responses.add(subtask.get());
            }
            return false;
        }

        @Override
        public List<TimedResponse> result() {
            return List.copyOf(responses);
        }
    }
}
