# Phase 0 — Tools, Repository and Local Infrastructure

**Goal:** a Gradle monorepo that builds, plus everything the services need running on your laptop: PostgreSQL with one schema per service, Kafka, Redis, S3-compatible storage (MinIO) and WireMock. You also create the dev signing keys and a script that mints test JWTs, so you can call any service without running identity-service.

**Time:** about 2 hours.

---

## Step 0.1 — Install the tools

| Tool | Version | Check |
| --- | --- | --- |
| JDK (Eclipse Temurin) | 25 | `java -version` → `openjdk version "25...` |
| Gradle (only to create the wrapper once) | 9.1.0 | `gradle --version` |
| Docker Engine + Compose plugin | 24+ / v2 | `docker compose version` |
| Node.js | 20+ | `node --version` |
| OpenSSL | 3.x | `openssl version` |
| jq | any | `jq --version` |
| curl | any | `curl --version` |
| kubectl, helm, terraform, k6 | latest | needed from Phase 11 on |

On Ubuntu, for example:

```bash
sudo apt-get install -y temurin-25-jdk jq curl openssl   # after adding the Adoptium apt repo
curl -s "https://get.sdkman.io" | bash && sdk install java 25-tem && sdk install gradle 9.1.0
```

**Verify**

```bash
java -version 2>&1 | head -1
docker compose version
node --version
```

Expected: Java 25, Compose v2.x, Node v20 or later.

---

## Step 0.2 — Create the repository

```bash
mkdir hiretrack && cd hiretrack
git init
gradle wrapper --gradle-version 9.1.0 --distribution-type bin
mkdir -p build-logic/src/main/kotlin gradle platform services contracts/events \
         deploy/postgres deploy/wiremock/mappings deploy/wiremock/__files deploy/dev-keys \
         tools frontend perf e2e ai-evals
```

Create `.gitignore`:

```gitignore
.gradle/
build/
out/
.idea/
*.iml
.vscode/
node_modules/
dist/
.env
deploy/dev-keys/*.pem
logs/
*.log
```

Create `.env.example` (copy it to `.env` and fill in real values; `.env` is never committed):

```dotenv
# AI provider (used from Phase 5): openai (default) or anthropic
AI_PROVIDER=openai

# OpenAI: model IDs are placeholders until you choose them (see spec 10.4)
OPENAI_API_KEY=sk-...
OPENAI_MODEL=<your-openai-match-model>
OPENAI_FEED_MODEL=<your-openai-feed-model>

# Anthropic: only needed when AI_PROVIDER=anthropic
ANTHROPIC_API_KEY=
ANTHROPIC_MODEL=<your-claude-match-model>
ANTHROPIC_FEED_MODEL=<your-claude-feed-model>

# Adzuna (used from Phase 8)
ADZUNA_APP_ID=
ADZUNA_APP_KEY=

# Shared secret for service-to-service calls (local only; prod uses Secrets Manager)
HIRETRACK_INTERNAL_TOKEN=dev-internal-token
```

**Verify**

```bash
./gradlew --version | grep Gradle
```

Expected: `Gradle 9.1.0`. Gradle 9.1 is the first release that runs and compiles Java 25.

---

## Step 0.3 — Version catalog

Create `gradle/libs.versions.toml`. Every module reads versions from here, so an upgrade is a one-line change.

```toml
[versions]
spring-boot = "4.0.9"
spring-dependency-management = "1.1.7"
spring-cloud = "2025.1.4"
openai = "4.105.0"
anthropic = "3.8.0"
resilience4j = "2.3.0"
shedlock = "6.3.0"
pdfbox = "3.0.5"
aws-sdk = "2.31.6"
wiremock = "3.9.1"
pact = "4.6.17"
versions-plugin = "0.52.0"

[libraries]
# Gradle plugins used by build-logic
spring-boot-gradle-plugin = { module = "org.springframework.boot:spring-boot-gradle-plugin", version.ref = "spring-boot" }
spring-dependency-management-plugin = { module = "io.spring.gradle:dependency-management-plugin", version.ref = "spring-dependency-management" }

# Third-party libraries (Spring-managed ones need no version)
openai-java = { module = "com.openai:openai-java", version.ref = "openai" }
anthropic-java = { module = "com.anthropic:anthropic-java", version.ref = "anthropic" }
resilience4j-bulkhead = { module = "io.github.resilience4j:resilience4j-bulkhead", version.ref = "resilience4j" }
resilience4j-circuitbreaker = { module = "io.github.resilience4j:resilience4j-circuitbreaker", version.ref = "resilience4j" }
shedlock-spring = { module = "net.javacrumbs.shedlock:shedlock-spring", version.ref = "shedlock" }
shedlock-jdbc = { module = "net.javacrumbs.shedlock:shedlock-provider-jdbc-template", version.ref = "shedlock" }
pdfbox = { module = "org.apache.pdfbox:pdfbox", version.ref = "pdfbox" }
aws-sdk-bom = { module = "software.amazon.awssdk:bom", version.ref = "aws-sdk" }
aws-s3 = { module = "software.amazon.awssdk:s3" }
wiremock = { module = "org.wiremock:wiremock-standalone", version.ref = "wiremock" }
pact-consumer = { module = "au.com.dius.pact.consumer:junit5", version.ref = "pact" }
pact-provider = { module = "au.com.dius.pact.provider:junit5spring", version.ref = "pact" }

[plugins]
versions = { id = "com.github.ben-manes.versions", version.ref = "versions-plugin" }
```

---

## Step 0.4 — Convention plugins (`build-logic`)

Convention plugins hold the build settings shared by every module, so each module's own build file stays a few lines long.

`build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "build-logic"
```

`build-logic/build.gradle.kts`:

```kotlin
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spring.dependency.management.plugin)
}
```

`build-logic/src/main/kotlin/hiretrack.java-conventions.gradle.kts`, applied by every Java module:

```kotlin
plugins {
    java
    jacoco
}

group = "com.aravindakv.hiretrack"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked"))
    options.encoding = "UTF-8"
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    reports { xml.required = true; html.required = true }
}
```

If JaCoCo reports `Unsupported class file major version 69`, its bundled version predates Java 25. Pin a newer JaCoCo release in this file with `jacoco { toolVersion = "<a version with Java 25 support>" }`.

`build-logic/src/main/kotlin/hiretrack.library-conventions.gradle.kts`, for `platform/*` libraries:

```kotlin
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("hiretrack.java-conventions")
    `java-library`
    id("io.spring.dependency-management")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.findVersion("spring-cloud").get()}")
    }
}
```

`build-logic/src/main/kotlin/hiretrack.service-conventions.gradle.kts`, for `services/*`:

```kotlin
plugins {
    id("hiretrack.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${libs.findVersion("spring-cloud").get()}")
    }
}

// Coverage gate from spec 10.7: >= 80% lines on domain and service packages.
tasks.register<JacocoCoverageVerification>("coverageGate") {
    dependsOn(tasks.named("test"))
    executionData.setFrom(fileTree(layout.buildDirectory).include("jacoco/test.exec"))
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("classes/java/main")) {
            include("**/domain/**", "**/service/**")
        }
    )
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") { dependsOn("coverageGate") }

springBoot {
    buildInfo()   // exposes version in /actuator/info and on metrics
}

// Only the executable boot jar is built, so Docker images (Phase 12) can copy one unambiguous file.
tasks.named<Jar>("jar") { enabled = false }
```

---

## Step 0.5 — Root build files

`settings.gradle.kts`. Modules are added phase by phase; each phase tells you which `include` line to add.

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories { gradlePluginPortal(); mavenCentral() }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "hiretrack"

// Phase 1 adds: include("platform:common-web", ...)
```

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.versions)
}

tasks.register("printModules") {
    doLast { subprojects.forEach { println(it.path) } }
}
```

**Verify**

```bash
./gradlew help -q && echo BUILD-OK
./gradlew dependencyUpdates -q | head -20
```

Expected: `BUILD-OK`, then a report listing any dependency with a newer version. Update `libs.versions.toml` if you want the newer patch versions.

---

## Step 0.6 — Local infrastructure with Docker Compose

`deploy/postgres/init.sql` creates one schema and one user per service. A service's user owns only its own schema, so it **cannot read another service's tables**, which enforces the database-per-service rule even though everything shares one PostgreSQL server locally.

```sql
-- Runs once, when the postgres volume is first created.
DO $$
DECLARE
    svc TEXT;
BEGIN
    FOREACH svc IN ARRAY ARRAY['identity','resume','ai','match','application','feed','analytics']
    LOOP
        EXECUTE format('CREATE USER %I WITH PASSWORD %L', svc || '_svc', svc || '_pw');
        EXECUTE format('CREATE SCHEMA %I AUTHORIZATION %I', svc, svc || '_svc');
        EXECUTE format('ALTER ROLE %I SET search_path = %I', svc || '_svc', svc);
    END LOOP;
END $$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
```

`deploy/docker-compose.yml`:

```yaml
name: hiretrack

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_USER: hiretrack
      POSTGRES_PASSWORD: hiretrack
      POSTGRES_DB: hiretrack
    ports: ["5432:5432"]
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./postgres/init.sql:/docker-entrypoint-initdb.d/01-init.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U hiretrack -d hiretrack"]
      interval: 5s
      retries: 20

  kafka:
    image: apache/kafka:3.8.0          # single-node KRaft (no ZooKeeper)
    ports: ["9092:9092"]
    environment:
      # Two listeners: kafka:19092 for other containers (Phase 12), localhost:9092 for services run on your machine
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:19092,PLAINTEXT_HOST://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:19092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_LOG_RETENTION_HOURS: -1   # keep events forever locally, so read models can be rebuilt (Phase 7)
    healthcheck:
      test: ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list"]
      interval: 10s
      retries: 20

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 20

  minio:
    image: minio/minio:latest          # pin to the RELEASE.* tag you pulled before committing
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: hiretrack
      MINIO_ROOT_PASSWORD: hiretrack-secret
    ports: ["9000:9000", "9001:9001"]
    volumes: [miniodata:/data]
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      retries: 20

  minio-setup:                         # one-shot helper; run on demand, not started by "up"
    image: minio/mc:latest             # pin as above
    profiles: ["tools"]
    depends_on:
      minio: { condition: service_healthy }
    entrypoint: >
      /bin/sh -c "
      mc alias set local http://minio:9000 hiretrack hiretrack-secret &&
      mc mb --ignore-existing local/hiretrack-resumes &&
      echo bucket-ready"

  wiremock:
    image: wiremock/wiremock:3.9.1
    ports: ["8089:8080"]
    command: ["--global-response-templating", "--verbose"]
    volumes:
      - ./wiremock:/home/wiremock

volumes:
  pgdata:
  miniodata:
```

Start it:

```bash
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml ps
```

**Verify each component**

```bash
# 1. PostgreSQL: seven service users and schemas exist
docker compose -f deploy/docker-compose.yml exec -T postgres \
  psql -U hiretrack -d hiretrack -c "\dn"
```

Expected: rows for `ai`, `analytics`, `application`, `feed`, `identity`, `match`, `resume` (plus `public`).

```bash
# 2. Schema isolation: resume_svc cannot read identity's tables
docker compose -f deploy/docker-compose.yml exec -T postgres \
  psql "postgresql://identity_svc:identity_pw@localhost/hiretrack" -c "CREATE TABLE probe(id int);"
docker compose -f deploy/docker-compose.yml exec -T postgres \
  psql "postgresql://resume_svc:resume_pw@localhost/hiretrack" -c "SELECT * FROM identity.probe;"
```

Expected: the first prints `CREATE TABLE`; the second fails with `ERROR:  permission denied for schema identity`. Clean up:

```bash
docker compose -f deploy/docker-compose.yml exec -T postgres \
  psql "postgresql://identity_svc:identity_pw@localhost/hiretrack" -c "DROP TABLE probe;"
```

```bash
# 3. Kafka: create, list and delete a topic
docker compose -f deploy/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic smoke --partitions 1
docker compose -f deploy/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
docker compose -f deploy/docker-compose.yml exec -T kafka \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic smoke
```

Expected: `Created topic smoke.`, then `smoke` in the list.

```bash
# 4. Redis
docker compose -f deploy/docker-compose.yml exec -T redis redis-cli ping
# 5. MinIO bucket (resume-service also creates it on start-up locally)
docker compose -f deploy/docker-compose.yml run --rm minio-setup | grep bucket-ready
# 6. WireMock
curl -s localhost:8089/__admin/mappings | jq '.meta.total'
```

Expected: `PONG`, `bucket-ready`, `0`.

---

## Step 0.7 — Dev signing keys and the token minting script

identity-service signs access tokens with an RSA private key, and every other service verifies them with the public key. Locally, all services read the same key files from `deploy/dev-keys/`. In production the private key lives in AWS Secrets Manager, and services fetch the public key from identity's JWKS endpoint.

`tools/gen-dev-keys.sh`:

```bash
#!/usr/bin/env bash
# Creates a 2048-bit RSA key pair for local development only.
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)/deploy/dev-keys"
mkdir -p "$DIR"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$DIR/private.pem"
openssl rsa -in "$DIR/private.pem" -pubout -out "$DIR/public.pem"
chmod 600 "$DIR/private.pem"
echo "Keys written to $DIR"
```

`tools/mint-jwt.sh` signs a token exactly like identity-service does. Use it to call any service on its own:

```bash
#!/usr/bin/env bash
# Usage: tools/mint-jwt.sh [userId] [email] [ttlSeconds]
# Prints an RS256 JWT signed with deploy/dev-keys/private.pem. Local development only.
set -euo pipefail
USER_ID="${1:-1}"
EMAIL="${2:-dev@example.com}"
TTL="${3:-3600}"
KEY="$(cd "$(dirname "$0")/.." && pwd)/deploy/dev-keys/private.pem"

b64url() { openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }

NOW=$(date +%s)
HEADER=$(printf '{"alg":"RS256","typ":"JWT","kid":"dev-key-1"}' | b64url)
PAYLOAD=$(printf '{"sub":"%s","email":"%s","iss":"hiretrack-identity","iat":%d,"exp":%d}' \
          "$USER_ID" "$EMAIL" "$NOW" "$((NOW + TTL))" | b64url)
SIGNATURE=$(printf '%s.%s' "$HEADER" "$PAYLOAD" | openssl dgst -sha256 -sign "$KEY" -binary | b64url)
printf '%s.%s.%s\n' "$HEADER" "$PAYLOAD" "$SIGNATURE"
```

```bash
chmod +x tools/*.sh
tools/gen-dev-keys.sh
```

**Verify**

```bash
TOKEN=$(tools/mint-jwt.sh 42 me@example.com)
echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null; echo
```

Expected: `{"sub":"42","email":"me@example.com","iss":"hiretrack-identity","iat":...,"exp":...}` (a padding warning from `base64` is harmless).

---

## Step 0.8 — A helper to run any service with the shared settings

`tools/run-service.sh` loads `.env` and starts one service with the `local` profile:

```bash
#!/usr/bin/env bash
# Usage: tools/run-service.sh <service-name>   e.g. tools/run-service.sh resume-service
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$ROOT/.env" ] && set -a && . "$ROOT/.env" && set +a
export HIRETRACK_KEYS_DIR="$ROOT/deploy/dev-keys"
mkdir -p "$ROOT/logs"
cd "$ROOT"
exec ./gradlew ":services:$1:bootRun" --args="--spring.profiles.active=local"
```

```bash
chmod +x tools/run-service.sh
```

You'll use it from Phase 2 on.

---

## Phase demo

```bash
cd hiretrack
docker compose -f deploy/docker-compose.yml down -v      # start from nothing
docker compose -f deploy/docker-compose.yml up -d --wait
./gradlew help -q && echo "gradle ok"
tools/mint-jwt.sh 1 > /dev/null && echo "token ok"
docker compose -f deploy/docker-compose.yml exec -T postgres psql -U hiretrack -d hiretrack -tAc \
  "select count(*) from pg_namespace where nspname in ('identity','resume','ai','match','application','feed','analytics')"
```

Expected output, last three lines:

```text
gradle ok
token ok
7
```

## Done when

- [ ] `./gradlew help` succeeds with the convention plugins
- [ ] `docker compose up -d --wait` brings every container to healthy
- [ ] Each service schema exists, and a service user can't read another schema
- [ ] `tools/mint-jwt.sh` prints a token whose payload decodes correctly
- [ ] The first commit is made: `git add . && git commit -m "Phase 0: repo and local infrastructure"`
