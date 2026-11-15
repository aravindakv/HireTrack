# Phase 12 — Run Everything Locally with Docker Compose

**Goal:** one command starts the whole of HireTrack on your machine, and you use it at **`http://localhost`**:

```text
browser ──► http://localhost (nginx: React app + /api proxy)
              └─► api-gateway ──► identity · resume · match · application · job-feed · analytics
                                   └─► ai-service ──► OpenAI API (real, with your key)
            PostgreSQL · Kafka · Redis · S3Mock · WireMock      Prometheus · Grafana · Tempo · Loki
```

Everything runs in containers except the OpenAI API, which is called over the internet with the key in your `.env`, and Adzuna, if you set its keys.

**Time:** half a day.

**Machine:** 16 GB RAM recommended (8 JVMs at about 500 MB each, plus infrastructure and monitoring), 4+ CPU cores, about 10 GB disk. Docker Desktop users: give Docker at least 10 GB of memory.

---

## Step 12.1 — One image recipe for every Java service

The Phase 0 convention plugin already disables the plain jar, so each service builds exactly one executable jar.

`deploy/docker/Dockerfile.service`:

```dockerfile
# One recipe for all eight services: docker build --build-arg SERVICE=match-service ...
FROM eclipse-temurin:25-jre

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home /app app

ARG SERVICE
WORKDIR /app
COPY services/${SERVICE}/build/libs/${SERVICE}-*.jar app.jar

USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`.dockerignore` in `hiretrack/` (the build context, next to `settings.gradle.kts`; Docker ignores it anywhere else), which keeps the build context small:

```text
.git
**/node_modules
**/.gradle
frontend/dist
logs
perf
e2e
```

**Verify**

```bash
./gradlew :services:identity-service:bootJar -q
docker build -f deploy/docker/Dockerfile.service --build-arg SERVICE=identity-service -t hiretrack/identity-service:local .
docker run --rm hiretrack/identity-service:local java -version 2>&1 | head -1
```

Expected: the image builds, and `openjdk version "21..."` is printed.

## Step 12.2 — The frontend image (nginx serves the app and proxies the API)

nginx serves the built React app and forwards `/api/*` to the gateway. The browser sees a single origin, `http://localhost`, so the refresh cookie and CORS just work.

`deploy/docker/nginx.conf`:

```nginx
server {
    listen 80;
    server_name localhost;
    root /usr/share/nginx/html;

    # Security headers (spec 4 / Phase 9: no user or AI text is ever executed)
    add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy strict-origin-when-cross-origin always;
    add_header X-Frame-Options DENY always;

    client_max_body_size 3m;                  # resume PDFs are capped at 2 MB

    location /api/ {
        proxy_pass http://api-gateway:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 25s;               # longer than the gateway's 17 s match timeout
    }

    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    location / {
        try_files $uri /index.html;           # client-side routes (/board, /jobs, ...)
        add_header Cache-Control "no-store";
    }
}
```

`deploy/docker/Dockerfile.frontend`:

```dockerfile
FROM node:20-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
```

`package-lock.json` was created when you ran `npm install` in Phase 9. Commit it.

## Step 12.3 — The application Compose file

All services listen on port 8080 inside their containers and reach each other by service name. Host ports 8081–8087 stay mapped, so the curl checks from earlier phases still work.

`deploy/docker-compose.app.yml`:

```yaml
name: hiretrack

x-java-env: &java-env
  SPRING_PROFILES_ACTIVE: local
  SERVER_PORT: "8080"
  DB_HOST: postgres
  KAFKA_BOOTSTRAP: kafka:19092
  REDIS_HOST: redis
  HIRETRACK_KEYS_DIR: /keys
  HIRETRACK_LOG_DIR: /logs
  HIRETRACK_INTERNAL_TOKEN: ${HIRETRACK_INTERNAL_TOKEN:-dev-internal-token}
  OTLP_TRACING_ENDPOINT: http://tempo:4318/v1/traces
  TRACING_SAMPLE_RATE: "1.0"

x-java: &java
  restart: unless-stopped
  mem_limit: 768m
  volumes:
    - ./dev-keys:/keys:ro
    - ../logs:/logs
  healthcheck:
    test: ["CMD", "curl", "-fsS", "http://localhost:8080/actuator/health/readiness"]
    interval: 10s
    timeout: 3s
    retries: 30
    start_period: 60s
  depends_on:
    postgres: { condition: service_healthy }
    kafka: { condition: service_healthy }
    redis: { condition: service_healthy }

services:
  identity-service:
    <<: *java
    build: { context: .., dockerfile: deploy/docker/Dockerfile.service, args: { SERVICE: identity-service } }
    image: hiretrack/identity-service:local
    environment:
      <<: *java-env
      COOKIE_SECURE: "false"            # plain http://localhost; true behind HTTPS
    ports: ["8081:8080"]

  resume-service:
    <<: *java
    build: { context: .., dockerfile: deploy/docker/Dockerfile.service, args: { SERVICE: resume-service } }
    image: hiretrack/resume-service:local
    environment:
      <<: *java-env
      S3_ENDPOINT: http://s3mock:9090
    depends_on:
      postgres: { condition: service_healthy }
      kafka: { condition: service_healthy }
      s3mock: { condition: service_healthy }
    ports: ["8082:8080"]

  ai-service:
    <<: *java
    build: { context: .., dockerfile: deploy/docker/Dockerfile.service, args: { SERVICE: ai-service } }
    image: hiretrack/ai-service:local
    env_file:
      - path: ../.env                   # OPENAI_API_KEY, OPENAI_MODEL, OPENAI_FEED_MODEL, AI_PROVIDER ...
        required: true
    environment:
      <<: *java-env
    ports: ["8083:8080"]

  match-service:
    <<: *java
    build: { context: .., dockerfile: deploy/docker/Dockerfile.service, args: { SERVICE: match-service } }
    image: hiretrack/match-service:local
    environment:
      <<: *java-env
      RESUME_SERVICE_URL: http://resume-service:8080
      AI_SERVICE_URL: http://ai-service:8080
    ports: ["8084:8080"]

  application-service:
    <<: *java
    build: { context: .., dockerfile: deploy/docker/Dockerfile.service, args: { SERVICE: application-service } }
    image: hiretrack/application-service:local
    environment:
      <<: *java-env
      MATCH_SERVICE_URL: http://match-service:8080
    ports: ["8085:8080"]

  job-feed-service:
    <<: *java
    build: { context: .., dockerfile: deploy/docker/Dockerfile.service, args: { SERVICE: job-feed-service } }
    image: hiretrack/job-feed-service:local
    env_file:
      - path: ../.env                   # ADZUNA_APP_ID, ADZUNA_APP_KEY
        required: true
    environment:
      <<: *java-env
      RESUME_SERVICE_URL: http://resume-service:8080
      AI_SERVICE_URL: http://ai-service:8080
      APPLICATION_SERVICE_URL: http://application-service:8080
    ports: ["8086:8080"]

  analytics-service:
    <<: *java
    build: { context: .., dockerfile: deploy/docker/Dockerfile.service, args: { SERVICE: analytics-service } }
    image: hiretrack/analytics-service:local
    environment:
      <<: *java-env
    ports: ["8087:8080"]

  api-gateway:
    <<: *java
    build: { context: .., dockerfile: deploy/docker/Dockerfile.service, args: { SERVICE: api-gateway } }
    image: hiretrack/api-gateway:local
    environment:
      <<: *java-env
      IDENTITY_URL: http://identity-service:8080
      RESUME_URL: http://resume-service:8080
      MATCH_URL: http://match-service:8080
      APPLICATION_URL: http://application-service:8080
      FEED_URL: http://job-feed-service:8080
      ANALYTICS_URL: http://analytics-service:8080
      ALLOWED_ORIGINS: http://localhost
    depends_on:
      redis: { condition: service_healthy }
      identity-service: { condition: service_healthy }
    ports: ["8080:8080"]

  frontend:
    build: { context: .., dockerfile: deploy/docker/Dockerfile.frontend }
    image: hiretrack/frontend:local
    restart: unless-stopped
    depends_on:
      api-gateway: { condition: service_healthy }
    ports: ["80:80"]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1/"]   # not localhost: that resolves to ::1 and nginx listens on IPv4
      interval: 10s
      retries: 10
```

The `x-java` anchor sets `depends_on`, and two services override it with their own `depends_on`. YAML merge keys replace that whole key rather than combining the two, which is why resume-service lists its full set.

## Step 12.4 — Monitoring for the containerised stack

In Phase 10 Prometheus scraped services on your host. Now it scrapes them inside the Compose network.

`deploy/observability/prometheus-compose.yml`:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s
rule_files: [/etc/prometheus/rules/*.yml]
alerting:
  alertmanagers:
    - static_configs: [{ targets: ["alertmanager:9093"] }]
scrape_configs:
  - job_name: hiretrack
    metrics_path: /actuator/prometheus
    static_configs:
      - targets:
          - api-gateway:8080
          - identity-service:8080
          - resume-service:8080
          - ai-service:8080
          - match-service:8080
          - application-service:8080
          - job-feed-service:8080
          - analytics-service:8080
```

`deploy/observability/docker-compose.observability-stack.yml`:

```yaml
name: hiretrack
services:
  prometheus:
    volumes:
      - ./prometheus-compose.yml:/etc/prometheus/prometheus.yml:ro
      - ./rules:/etc/prometheus/rules:ro
```

Both Compose invocations use the project name `hiretrack`, so the monitoring containers join the same network as the application.

## Step 12.5 — Start, stop and reset scripts

`tools/stack-up.sh`:

```bash
#!/usr/bin/env bash
# Builds and starts the whole system. Usage: tools/stack-up.sh [--no-observability]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

[ -f .env ] || { echo "Missing .env: copy .env.example to .env and set OPENAI_API_KEY and OPENAI_MODEL."; exit 1; }
grep -q '^OPENAI_API_KEY=sk-' .env || echo "Warning: OPENAI_API_KEY looks unset; matches will fail."
[ -f deploy/dev-keys/private.pem ] || tools/gen-dev-keys.sh
mkdir -p logs

echo "Building service jars..."
./gradlew bootJar -q

echo "Starting infrastructure and services (first run takes a few minutes)..."
docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml up -d --build --wait

if [ "${1:-}" != "--no-observability" ]; then
  docker compose -f deploy/observability/docker-compose.observability.yml \
                 -f deploy/observability/docker-compose.observability-stack.yml up -d
fi

cat <<EOF

HireTrack is running.
  App:          http://localhost
  API gateway:  http://localhost:8080
  Grafana:      http://localhost:3000
  Prometheus:   http://localhost:9090
  Alerts:       http://localhost:9093
  S3 (S3Mock):  http://localhost:9000  (path-style, no login, no console)
EOF
```

`tools/stack-down.sh`:

```bash
#!/usr/bin/env bash
# Stops everything but keeps data (database, Kafka topics, files).
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -f deploy/observability/docker-compose.observability.yml -f deploy/observability/docker-compose.observability-stack.yml down
docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml down
```

`tools/stack-reset.sh`:

```bash
#!/usr/bin/env bash
# Stops everything AND deletes all local data. Asks first.
set -euo pipefail
cd "$(dirname "$0")/.."
read -r -p "Delete all local HireTrack data (users, resumes, applications)? Type 'yes': " ok
[ "$ok" = "yes" ] || { echo "Cancelled."; exit 0; }
docker compose -f deploy/observability/docker-compose.observability.yml -f deploy/observability/docker-compose.observability-stack.yml down -v
docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml down -v
rm -rf logs/*
```

`tools/stack-update.sh` rebuilds and restarts one service after a code change:

```bash
#!/usr/bin/env bash
# Usage: tools/stack-update.sh match-service
set -euo pipefail
cd "$(dirname "$0")/.."
SVC="$1"
if [ "$SVC" = "frontend" ]; then
  docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml up -d --build --wait frontend
else
  ./gradlew ":services:$SVC:bootJar" -q
  docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml up -d --build --wait "$SVC"
fi
```

```bash
chmod +x tools/stack-*.sh
```

## Step 12.6 — Configure `.env` for local use

```dotenv
AI_PROVIDER=openai
OPENAI_API_KEY=sk-...                     # your real key
OPENAI_MODEL=<your-openai-match-model>    # the model you chose
OPENAI_FEED_MODEL=<your-openai-feed-model>
ADZUNA_APP_ID=...                         # free key from developer.adzuna.com (optional: without it, the Job Feed shows an error)
ADZUNA_APP_KEY=...
HIRETRACK_INTERNAL_TOKEN=dev-internal-token
```

Put your models' prices in `services/ai-service/src/main/resources/application.yml` under `hiretrack.ai.prices` (Phase 5), so local spend shows up in Grafana.

## Step 12.7 — Start it

```bash
tools/stack-up.sh
```

**Verify**

```bash
# 1. Every container is healthy
docker compose --env-file .env -f deploy/docker-compose.yml -f deploy/docker-compose.app.yml ps \
  --format 'table {{.Service}}\t{{.Status}}'
```

Expected: all eight services, `api-gateway`, `frontend`, `postgres`, `kafka`, `redis`, `s3mock` and `wiremock` show `Up ... (healthy)`, or `Up` for those without a health check.

```bash
# 2. The app and the API answer through nginx
curl -s -o /dev/null -w 'app %{http_code}\n' http://localhost/
curl -s http://localhost/api/v1/applications | jq -c .error
```

Expected: `app 200`, then `{"code":"UNAUTHENTICATED",...}`. That's the gateway answering through nginx.

```bash
# 3. Memory use
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}' | sort
```

Expected: each Java service under its 768 MB limit.

## Step 12.8 — Use it

Open **http://localhost** in your browser:

1. **Sign up.**
2. **Resume:** upload your PDF or paste your resume.
3. **Match:** paste a real job description. The score comes from **OpenAI**, in about 5–15 seconds. Save it to the pipeline.
4. **Pipeline:** drag cards through the stages and add an interview date.
5. **Dashboard:** counts, interviews this week and response rate.
6. **Job Feed:** enter keywords, country code (e.g. `in`, `gb`, `us`) and city, then **Refresh now**. Real postings from Adzuna arrive after the batch is scored. OpenAI's Batch API can take minutes or longer, so check back.

Your data survives restarts (`tools/stack-down.sh`, then `tools/stack-up.sh`). Only `tools/stack-reset.sh` deletes it.

## Step 12.9 — Back up and restore your local data

`tools/backup-db.sh`:

```bash
#!/usr/bin/env bash
# Writes backups/hiretrack-<timestamp>.sql.gz (all service schemas).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p backups
FILE="backups/hiretrack-$(date +%Y%m%d-%H%M%S).sql.gz"
docker compose -f deploy/docker-compose.yml exec -T postgres pg_dump -U hiretrack -d hiretrack | gzip > "$FILE"
echo "Backup written to $FILE"
```

To restore into a fresh stack, run `tools/stack-reset.sh`. Then start **only** Postgres so the services don't create empty tables first:

```bash
docker compose -f deploy/docker-compose.yml up -d --wait postgres
gunzip -c backups/<file>.sql.gz | docker compose -f deploy/docker-compose.yml exec -T postgres psql -U hiretrack -d hiretrack
tools/stack-up.sh
```

Add `backups/` to `.gitignore`. The backups contain personal data.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `bind: address already in use` on port 80 | Another web server is running | Stop it, or change `"80:80"` to `"8088:80"` and use `http://localhost:8088` (then also set `ALLOWED_ORIGINS`) |
| ai-service keeps restarting; log says "Set the API key, match model and feed model" | `.env` is missing `OPENAI_API_KEY`, `OPENAI_MODEL` or `OPENAI_FEED_MODEL` | Fill them in, then `tools/stack-update.sh ai-service` |
| Match returns `AI_UNAVAILABLE` | Wrong key or model ID, no network, or provider outage | `docker compose ... logs ai-service`; check the key and model on OpenAI's dashboard |
| Services `unhealthy`, exit code 137 | Out of memory | Give Docker more memory, or run without monitoring: `tools/stack-up.sh --no-observability` |
| Job Feed shows "Job search is unavailable" | Adzuna keys missing or invalid | Set `ADZUNA_APP_ID` and `ADZUNA_APP_KEY`, then `tools/stack-update.sh job-feed-service` |
| Logged out on every reload | The browser blocked the cookie | Use `http://localhost`, not `127.0.0.1`. The cookie is issued for the host you browse |
| Feed postings never appear | The OpenAI batch is still running | `docker compose ... logs -f ai-service`; batches can take a long time. `feed_runs.status` shows `SCORING` until it finishes |

---

## Phase demo

`tools/demo-local.sh` drives the whole system through `http://localhost` with the real OpenAI API:

```bash
#!/usr/bin/env bash
set -euo pipefail
BASE=http://localhost/api/v1
JAR=$(mktemp); EMAIL="demo$(date +%s)@example.com"
J=(-H 'Content-Type: application/json')

TOKEN=$(curl -sf -X POST $BASE/auth/signup "${J[@]}" -c "$JAR" \
  -d "{\"name\":\"Demo\",\"email\":\"$EMAIL\",\"password\":\"correct-horse\"}" | jq -r .accessToken)
A=(-H "Authorization: Bearer $TOKEN")

curl -sf -X POST $BASE/resumes "${A[@]}" "${J[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"text":"Software engineer. Java, Spring Boot, Kafka, PostgreSQL, Docker. 5 years building payment APIs."}' >/dev/null
echo "resume saved"

MATCH=$(curl -sf -X POST $BASE/matches "${A[@]}" "${J[@]}" -H "Idempotency-Key: $(uuidgen)" --max-time 20 \
  -d '{"jobDescription":"Fintech company Contoso is hiring a Backend Engineer: Java, Kafka, Kubernetes, payments experience."}')
echo "$MATCH" | jq -c '{fitScore, company, role, missingSkills}'
MATCH_ID=$(echo "$MATCH" | jq .id)

APP=$(curl -sf -X POST $BASE/applications "${A[@]}" "${J[@]}" -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"company\":\"Contoso\",\"role\":\"Backend Engineer\",\"source\":{\"type\":\"MATCH\",\"id\":\"$MATCH_ID\"}}")
APP_ID=$(echo "$APP" | jq .id)
curl -sf -X PATCH $BASE/applications/$APP_ID "${A[@]}" "${J[@]}" -H "If-Match: \"$(echo "$APP" | jq .version)\"" \
  -d '{"stage":"APPLIED"}' | jq -c '{stage, appliedDate}'

sleep 3
curl -sf "$BASE/dashboard?tz=$(cat /etc/timezone 2>/dev/null || echo UTC)" "${A[@]}" | jq -c '{byStage, sent, responseRate}'
echo "open http://localhost and log in as $EMAIL / correct-horse"
```

Expected:

```text
resume saved
{"fitScore":<real score>,"company":"Contoso","role":"Backend Engineer","missingSkills":[...]}
{"stage":"APPLIED","appliedDate":"<today>"}
{"byStage":{"WISHLIST":0,"APPLIED":1,...},"sent":1,"responseRate":0.0}
open http://localhost and log in as demo...@example.com / correct-horse
```

## Done when

- [ ] `tools/stack-up.sh` starts everything, and every container is healthy
- [ ] `http://localhost` works end to end: signup, resume, a **real OpenAI** match, pipeline, dashboard, Job Feed
- [ ] Data survives `stack-down` / `stack-up`; backup and restore work
- [ ] Grafana at `http://localhost:3000` shows metrics, traces and logs from the containers
- [ ] The Phase 11 end-to-end suite passes against this stack with the e2e override
- [ ] Commit: `git commit -am "Phase 12: local deployment with Docker Compose"`
