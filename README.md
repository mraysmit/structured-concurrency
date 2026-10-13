# Structured Concurrency

A Java project exploring structured concurrency preview features in Java 27.

## Requirements

- JDK 27 (preview features enabled by the build and launch configurations)
- Maven 3.9.0+

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
