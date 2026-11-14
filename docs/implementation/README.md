# HireTrack AI — Build It Yourself (Java Microservices)

A complete, step-by-step guide to building the production version of HireTrack AI. Every file you need is in these pages: build scripts, migrations, Java classes, configuration, tests, infrastructure and deployment.

What the product does is defined in `../Final-Specification-Doc.md` (sections 1–9). The production targets (SLOs, throughput, cost, metrics, idempotency, test gates) are in section 10 of the same file. This guide implements both.

## How to use this guide

Work through the phases in order. Each phase builds on the code from the phases before it.

| Phase | File | You build | Runs on its own with |
| --- | --- | --- | --- |
| 0 | [00-setup.md](00-setup.md) | Tools, Gradle monorepo, local infrastructure, dev keys | Docker Compose |
| 1 | [01-platform.md](01-platform.md) | Shared libraries: errors, security, events, outbox, idempotency, test support | Testcontainers |
| 2 | [02-identity-gateway.md](02-identity-gateway.md) | `identity-service`, `api-gateway` | Compose infra |
| 3 | [03-resume.md](03-resume.md) | `resume-service` | Compose infra + a minted dev token |
| 4 | [04-application.md](04-application.md) | `application-service` | Compose infra + WireMock for match-service |
| 5 | [05-ai.md](05-ai.md) | `ai-service` (OpenAI default, Claude switchable) | Compose infra + WireMock for OpenAI and Claude |
| 6 | [06-match.md](06-match.md) | `match-service` | Compose infra + WireMock for resume and ai services |
| 7 | [07-analytics.md](07-analytics.md) | `analytics-service` | Compose infra + events you publish by hand |
| 8 | [08-job-feed.md](08-job-feed.md) | `job-feed-service` | Compose infra + WireMock for Adzuna, resume, ai and application services |
| 9 | [09-frontend.md](09-frontend.md) | React SPA | The full local stack |
| 10 | [10-observability.md](10-observability.md) | Prometheus, Grafana, Tempo, Loki, alerts, runbooks | The full local stack |
| 11 | [11-testing.md](11-testing.md) | Contract, end-to-end, resilience, performance, AI evaluation and security tests | The full local stack (fake AI provider for E2E and load tests) |
| 12 | [12-deploy-local.md](12-deploy-local.md) | Dockerfiles and one Docker Compose file that runs the whole system at `http://localhost`, using the real OpenAI API | Your machine |
| 13 | [13-deploy-aws.md](13-deploy-aws.md) | Terraform, Helm, CI/CD, Argo CD (when you're ready for the cloud) | An AWS account |
| 14 | [14-launch.md](14-launch.md) | Production readiness review, runbooks, launch | Production |

### Three levels of testing in every phase

1. **Each step is verified.** Every numbered step ends with a **Verify** block: a command to run and the output you should see. Don't continue until it matches.
2. **Each service is tested alone.** Every service has its own unit and integration tests, and can run by itself. Other services, the AI provider (OpenAI or Claude) and Adzuna are replaced by WireMock stubs, and the JWT comes from `tools/mint-jwt.sh`. You never need the whole system running to work on one service.
3. **Each phase has a demo.** Every phase ends with a **Phase demo** script that proves the phase works end to end, followed by a **Done when** checklist.

## Confirmed decisions

These were agreed before writing this guide. Change them here first if you change your mind, because every phase depends on them.

| Decision | Choice |
| --- | --- |
| Deployment | **Local first:** everything in Docker Compose at `http://localhost` (Phase 12). **AWS later** (EKS, RDS PostgreSQL, MSK, ElastiCache, S3, CloudFront) in Phase 13 |
| AI when running locally | The real OpenAI API with your key; WireMock fakes are used only in automated tests |
| Scale target | Launch 1,000 DAU, design for 10,000 DAU (100,000 registered users) |
| Messaging | Apache Kafka with the transactional outbox pattern |
| Services | 8: `api-gateway`, `identity-service`, `resume-service`, `ai-service`, `match-service`, `application-service`, `job-feed-service`, `analytics-service` |
| Language / framework | Java 25, Spring Boot 4.0.x, Spring Cloud 2025.1 (Oakwood), Jackson 3 |
| Build | Gradle 9.9.1 (Kotlin DSL), multi-module monorepo with convention plugins |
| Base package / group | `com.aravindakv.hiretrack` |
| Data access | Spring Data JPA for domain tables; `JdbcTemplate` for outbox, idempotency and upsert SQL |
| Database | PostgreSQL 16, one schema and one database user per service |
| Cache | Redis 7 |
| Auth | 15-minute RS256 access token in memory; rotating 7-day refresh token in an `HttpOnly` cookie |
| Monitoring | Prometheus, Grafana, Tempo, Loki, OpenTelemetry |
| SLOs | 99.9% availability, p95 read < 300 ms, AI match p95 < 15 s |
| AI provider | Switchable behind one interface: **OpenAI (default)** or Anthropic Claude, chosen with `hiretrack.ai.provider` |
| AI model | Configurable per operation (`OPENAI_MODEL`, `OPENAI_FEED_MODEL`; `ANTHROPIC_MODEL`, `ANTHROPIC_FEED_MODEL`). **Model IDs and prices are placeholders** until you fill them in; the evaluation set picks the cheapest model that meets the quality bar |

## Conventions used in every phase

| Item | Convention |
| --- | --- |
| Code root | `hiretrack/`, a folder inside the HireTrack repository (next to `docs/`). All paths in this guide are relative to it, and all commands run from it |
| Java packages | `com.aravindakv.hiretrack.<service>.<layer>`, where layer is `domain`, `service`, `web`, `persistence`, `client`, `config`, `messaging` |
| Ports (local) | gateway 8080, identity 8081, resume 8082, ai 8083, match 8084, application 8085, job-feed 8086, analytics 8087, WireMock 8089, frontend 5173 |
| Database schemas | `identity`, `resume`, `ai`, `match`, `application`, `feed`, `analytics` |
| Database users | `<schema>_svc`, password `<schema>_pw` locally |
| Kafka topics | `identity-events`, `resume-events`, `match-events`, `application-events`, `feed-commands`, `feed-events` |
| Public API | Browser → gateway `/api/v1/...`; the gateway strips `/api/v1` before forwarding |
| Internal API | Service → service `/internal/...` with header `X-Internal-Token`; never routed by the gateway |
| Errors | `{"error": {"code": "SNAKE_CASE", "message": "..."}}` from every service |
| Idempotency | `Idempotency-Key` header required on every creating `POST` |
| Time | Stored as UTC `TIMESTAMPTZ` / `Instant`; dates as `DATE` / `LocalDate` |
| Profiles | `local` (default on your machine), `test` (automated tests), `prod` (Kubernetes) |

## Versions

Pinned in `gradle/libs.versions.toml` (Phase 0). These were current when the guide was written. Before you start, check for newer patch releases with `./gradlew dependencyUpdates` (plugin added in Phase 0) and update the catalog in one place.

| Component | Version |
| --- | --- |
| Java | 25 |
| Gradle | 9.9.1 |
| Spring Boot | 4.0.9 |
| Spring Cloud | 2025.1.4 |
| OpenAI Java SDK | 4.105.0 |
| Anthropic Java SDK (optional provider) | 3.8.0 |
| Resilience4j | 2.3.0 |
| ShedLock | 6.3.0 |
| Apache PDFBox | 3.0.5 |
| AWS SDK for Java v2 | 2.31.6 |
| WireMock | 3.9.1 |
| Pact JVM | 4.6.17 |
| PostgreSQL / Kafka / Redis images | 16-alpine / apache/kafka 3.8.0 / 7-alpine |

Testcontainers (2.x), Flyway, Jackson (3.x), Micrometer and the PostgreSQL driver versions come from the Spring Boot BOM.

**Check before you start:** ShedLock, Pact JVM and the Gradle versions plugin ship their own releases. Make sure the versions in the catalog support **Spring Framework 7 / Spring Boot 4** and **Gradle 9**, and move to a newer release if `./gradlew build` reports an incompatibility.

## Spring Boot 4 notes

The guide is written for Spring Boot 4.0. The differences from Spring Boot 3 that show up in the code:

| Topic | What the guide uses |
| --- | --- |
| Starters | Technology starters: `spring-boot-starter-webmvc` (not `-web`), `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-flyway`, `spring-boot-starter-kafka`, `spring-boot-starter-restclient`, `spring-boot-starter-opentelemetry` |
| Test starters | `spring-boot-starter-webmvc-test` (MockMvc), `spring-boot-starter-webflux-test` (WebTestClient), `spring-boot-resttestclient` (TestRestTemplate, enabled with `@AutoConfigureTestRestTemplate`) |
| JSON | Jackson 3: packages `tools.jackson.*`; `JacksonException` is unchecked; `JsonNode.asString()` replaces `asText()`. Annotations stay in `com.fasterxml.jackson.annotation`. The OpenAI and Anthropic SDKs bring their own Jackson 2, which lives in different packages and doesn't conflict |
| Testcontainers 2 | Artifacts `org.testcontainers:testcontainers-<module>`; containers in `org.testcontainers.<module>` (e.g. `org.testcontainers.postgresql.PostgreSQLContainer`) |
| Tracing | `management.opentelemetry.tracing.export.otlp.endpoint`; `management.tracing.export.enabled` |
| Security set-up | `@EnableHiretrackSecurity` on each servlet service, instead of ordering an auto-configuration against Spring Boot's internal classes (Phase 1, Step 1.3) |
| Resilience4j | Core libraries (`resilience4j-bulkhead`, `resilience4j-circuitbreaker`) configured in code, so there's no Spring Boot starter version to match (Phase 5) |

## When something doesn't compile

The OpenAI and Anthropic SDKs and some other libraries change between versions. If a method name in this guide doesn't exist in the version you installed, the compiler error points at it. Check that library's README or examples for the new name, fix it, and note the change in your own copy of the guide.
