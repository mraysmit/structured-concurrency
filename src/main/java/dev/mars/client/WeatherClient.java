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
     * Fetches weather forecasts for all requests sequentially and returns timed results.
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
     * Fetches weather forecasts for all requests concurrently using structured concurrency.
     * One subtask is forked per request using {@code Joiner.allSuccessfulOrThrow()}.
     * If any subtask fails the scope is cancelled and the failure is thrown unchecked.
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
     * Fetches the first successful weather forecast by racing one subtask per provider request.
     * Uses {@code Joiner.anySuccessfulOrThrow()} so the scope is cancelled as soon as one
     * subtask completes successfully, returning the result from whichever provider won.
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
     * Fetches weather forecasts for all requests concurrently with a deadline.
     * Uses {@code Joiner.awaitAll()} so all subtasks are awaited (or cancelled on timeout).
     * Returns an {@code Optional} per request: present if the subtask completed before the
     * deadline, empty if it was cancelled by the timeout.
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
     * Fetches weather forecasts concurrently and returns only successful responses.
     * Failed subtasks are ignored by a custom joiner so one failed lookup does not
     * cancel or fail the whole operation.
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
