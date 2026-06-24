# Structured Concurrency Demo — Weather Service

## Overview

This project demonstrates Java structured concurrency using a simulated weather service.
The service mimics calling an external REST API to retrieve the weather outlook for a given city.
All latency and results are randomised to simulate real-world network behaviour.

---

## Weather Service Design

### API Contract

**Input parameters**

| Parameter | Type     | Description                        |
|-----------|----------|------------------------------------|
| country   | `String` | ISO country code, e.g. `"GB"`      |
| city      | `String` | City name, e.g. `"London"`         |
| date      | `LocalDate` | Forecast date                   |

**Output**

| Field       | Type     | Description                                          |
|-------------|----------|------------------------------------------------------|
| temperature | `int`    | Temperature in °C (randomised within a plausible range) |
| condition   | `String` | Weather condition (see list below)                   |

**Weather conditions (pool)**

- `"sunny"`
- `"partly cloudy"`
- `"cloudy"`
- `"overcast"`
- `"drizzle"`
- `"rain"`
- `"heavy rain"`
- `"thunderstorm"`
- `"snow"`
- `"fog"`

### Simulated behaviour

- **Response delay**: randomised between a configurable min and max (e.g. 200 ms – 2 000 ms)
  using `Thread.sleep()` inside the service implementation.
- **Result**: condition and temperature chosen at random from the pools above on every call.
- **No real network calls**: the implementation is entirely in-process.

### Class design

```
WeatherRequest          — record holding (country, city, date)
WeatherResponse         — record holding (city, temperature, condition)
WeatherService          — interface with one method:
                            WeatherResponse fetch(WeatherRequest request) throws InterruptedException
SimulatedWeatherService — implements WeatherService
                          injects random delay and picks random condition + temperature
```

---

## Structured Concurrency Demo Plan

### Goal

Show how `StructuredTaskScope` (JEP 505, Java 26) makes it easy to:

1. Fan out multiple concurrent weather lookups (one per city).
2. Wait for **all** results (shutdown-on-failure semantics).
3. Wait for the **first** successful result (shutdown-on-success semantics).
4. Enforce a single deadline across all subtasks with a timeout.

### Phases

#### Phase 1 — Sequential baseline
Call `SimulatedWeatherService.fetch()` for a list of cities one at a time.
Record total elapsed time. This establishes what unstructured sequential code looks like.

#### Phase 2 — Fan-out with `ShutdownOnFailure`
Use `StructuredTaskScope.ShutdownOnFailure` to fork one subtask per city.
Call `scope.join()` then `scope.throwIfFailed()`.
Collect all `WeatherResponse` results. Total time ≈ slowest single call.

#### Phase 3 — Race with `ShutdownOnSuccess`
Use `StructuredTaskScope.ShutdownOnSuccess` to fork the same city across multiple
"provider" subtasks (simulating redundant providers).
Return the first response that arrives and cancel the rest.

#### Phase 4 — Timeout
Wrap Phase 2 in `scope.joinUntil(Instant.now().plusSeconds(3))`.
Demonstrate clean cancellation when the deadline expires before all tasks complete.

#### Phase 5 — Custom scope (stretch goal)
Implement a custom `StructuredTaskScope` subclass that collects only successful
results and ignores failed subtasks (partial-results pattern).

### Acceptance criteria

- All demos run from `App.main()` with clear console output showing city, condition,
  temperature, and elapsed time per subtask.
- No raw `Thread` creation or `ExecutorService` usage — structured concurrency APIs only.
- Checkstyle passes with zero violations.
- JUnit tests cover `SimulatedWeatherService` (mock random seed) and each demo phase.

---

## Notes

- `StructuredTaskScope` requires `--enable-preview` on Java 21–25; it is finalised in Java 26.
- The `pom.xml` already targets Java 26 (`maven.compiler.release=26`).
- Random seed should be injectable for deterministic tests.
