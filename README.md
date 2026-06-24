# Structured Concurrency

A Java project exploring structured concurrency features introduced in modern Java (Java 26+).

## Requirements

- Java 26+
- Maven 3.6.3+

## Build

```bash
mvn clean compile
```

## Test

```bash
mvn test
```

## Checkstyle

```bash
mvn checkstyle:check
```

To generate the HTML report:

```bash
mvn checkstyle:checkstyle
# report at target/site/checkstyle.html
```

## Coverage

JaCoCo coverage is configured. To generate the report:

```bash
mvn verify
# report at target/site/jacoco/index.html
```
