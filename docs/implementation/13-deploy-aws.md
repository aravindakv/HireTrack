# Phase 13 — Deploy to AWS (when you're ready for the cloud)

**Goal:** staging and production environments on AWS that heal and scale themselves, built entirely from code. Phase 12's local stack stays your everyday environment; this phase is for going public.

| Layer | Tooling |
| --- | --- |
| Infrastructure | Terraform: VPC, EKS, RDS PostgreSQL, MSK (Kafka), ElastiCache (Redis), S3, CloudFront, ECR, Secrets Manager |
| Workloads | One Helm chart, a values file per service |
| Delivery | GitHub Actions builds and pushes images; Argo CD deploys them (GitOps) |
| Scaling | HPA on CPU for services; KEDA on Kafka lag for feed workers; Karpenter for nodes |

**Time:** 1–2 weeks the first time. **Cost:** see spec 10.4 (about $765/month of infrastructure at launch, plus AI usage). Create AWS Budgets alerts **before** you apply anything.

> **Real money and real data.** Everything here creates billable AWS resources and will hold real users' personal data. Review each `terraform plan` before applying, and run `terraform destroy` on environments you no longer need.

---

## Step 13.1 — Production configuration changes in the services

These differ from local and are switched on by the `prod` profile and environment variables. Make the code changes once:

1. **Kafka on MSK with IAM auth.** Add `implementation("software.amazon.msk:aws-msk-iam-auth:2.2.0")` to every service that uses Kafka, then add to the template's `prod` profile:
   ```yaml
   spring:
     kafka:
       bootstrap-servers: ${KAFKA_BOOTSTRAP}
       security.protocol: SASL_SSL
       properties:
         sasl.mechanism: AWS_MSK_IAM
         sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
         sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
   ```
2. **Redis over TLS** (ElastiCache in-transit encryption): `spring.data.redis.ssl.enabled: true` in `prod`.
3. **Kafka topics** are created with `KAFKA_PARTITIONS=12` and `KAFKA_REPLICAS=3`. `application-events` keeps events forever, so analytics can rebuild (Phase 7). In application-service's `KafkaTopicsConfig` add:
   ```java
   .config(org.apache.kafka.common.config.TopicConfig.RETENTION_MS_CONFIG, "-1")
   .config(org.apache.kafka.common.config.TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, "2")
   ```
4. **Secrets** come from AWS Secrets Manager through the External Secrets Operator, as environment variables and files:

   | Secret | Used by |
   | --- | --- |
   | DB password per service | each service with a database |
   | `OPENAI_API_KEY` (and `ANTHROPIC_API_KEY` if you switch provider) | ai-service |
   | `ADZUNA_APP_ID` / `ADZUNA_APP_KEY` | job-feed-service |
   | `HIRETRACK_INTERNAL_TOKEN` | all services |
   | JWT private key file | identity-service |

5. **Other prod values:**
   - `COOKIE_SECURE=true`
   - `S3_SSE=true`, `S3_CREATE_BUCKET=false`, `S3_ENDPOINT=` (empty)
   - `ALLOWED_ORIGINS=https://app.<your-domain>`
   - `JWKS_URI=http://identity-service.hiretrack.svc/.well-known/jwks.json`
   - `TRACING_SAMPLE_RATE=0.1`
6. **Flyway runs on start-up** in each service. Flyway takes a database lock, so several pods starting at once are safe. Keep every migration expand/contract (Phase 11, Step 11.7).

## Step 13.2 — Terraform

Layout:

```text
deploy/terraform/
├── modules/hiretrack/          # everything for one environment
│   ├── main.tf  network.tf  eks.tf  data.tf  storage.tf  secrets.tf  ecr.tf  variables.tf  outputs.tf
└── envs/
    ├── staging/main.tf         # module "hiretrack" { env = "staging", ... }
    └── prod/main.tf
```

`modules/hiretrack/main.tf`:

```hcl
terraform {
  required_version = ">= 1.6"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 5.70" }
  }
}

locals {
  name = "hiretrack-${var.env}"
  tags = { Project = "hiretrack", Environment = var.env }
}
```

`modules/hiretrack/variables.tf`:

```hcl
variable "env" { type = string }
variable "region" { type = string }
variable "azs" { type = list(string) }
variable "db_instance_class" { type = string }        # staging: db.t4g.medium, prod: db.m7g.large
variable "msk_instance_type" { type = string }        # staging: kafka.t3.small, prod: kafka.m7g.large
variable "redis_node_type" { type = string }          # cache.t4g.small
variable "domain" { type = string }                   # e.g. app.example.com
```

`modules/hiretrack/network.tf`:

```hcl
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.13"

  name            = local.name
  cidr            = "10.20.0.0/16"
  azs             = var.azs
  private_subnets = ["10.20.0.0/19", "10.20.32.0/19", "10.20.64.0/19"]
  public_subnets  = ["10.20.96.0/22", "10.20.100.0/22", "10.20.104.0/22"]
  database_subnets = ["10.20.112.0/22", "10.20.116.0/22", "10.20.120.0/22"]

  enable_nat_gateway     = true
  one_nat_gateway_per_az = var.env == "prod"           # HA in prod, one NAT in staging to save cost
  private_subnet_tags    = { "karpenter.sh/discovery" = local.name }
  tags                   = local.tags
}
```

`modules/hiretrack/eks.tf`:

```hcl
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.24"

  cluster_name                   = local.name
  cluster_version                = "1.31"
  vpc_id                         = module.vpc.vpc_id
  subnet_ids                     = module.vpc.private_subnets
  cluster_endpoint_public_access = true
  enable_irsa                    = true

  eks_managed_node_groups = {
    general = {
      instance_types = ["m7g.large"]                   # Graviton (arm64): build multi-arch images
      ami_type       = "AL2023_ARM_64_STANDARD"
      min_size       = 3
      desired_size   = 4
      max_size       = 10
    }
  }
  tags = local.tags
}
```

`modules/hiretrack/data.tf`:

```hcl
resource "aws_security_group" "data" {
  name   = "${local.name}-data"
  vpc_id = module.vpc.vpc_id
  ingress {
    from_port       = 0
    to_port         = 65535
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]   # only the cluster nodes reach the data stores
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = module.vpc.database_subnets
}

resource "aws_db_instance" "postgres" {
  identifier                   = local.name
  engine                       = "postgres"
  engine_version               = "16.4"
  instance_class               = var.db_instance_class
  allocated_storage            = 100
  max_allocated_storage        = 500
  storage_type                 = "gp3"
  storage_encrypted            = true
  multi_az                     = true
  db_name                      = "hiretrack"
  username                     = "hiretrack_admin"
  manage_master_user_password  = true                  # password lives in Secrets Manager
  db_subnet_group_name         = aws_db_subnet_group.this.name
  vpc_security_group_ids       = [aws_security_group.data.id]
  backup_retention_period      = var.env == "prod" ? 30 : 7
  deletion_protection          = var.env == "prod"
  performance_insights_enabled = true
  skip_final_snapshot          = var.env != "prod"
  final_snapshot_identifier    = "${local.name}-final"
  tags                         = local.tags
}

resource "aws_msk_cluster" "kafka" {
  cluster_name           = local.name
  kafka_version          = "3.6.0"
  number_of_broker_nodes = 3
  broker_node_group_info {
    instance_type   = var.msk_instance_type
    client_subnets  = module.vpc.private_subnets
    security_groups = [aws_security_group.data.id]
    storage_info {
      ebs_storage_info { volume_size = 100 }
    }
  }
  client_authentication {
    sasl { iam = true }
  }
  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }
  tags = local.tags
}

resource "aws_elasticache_subnet_group" "this" {
  name       = local.name
  subnet_ids = module.vpc.private_subnets
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = local.name
  description                = "HireTrack rate limits, quotas and caches"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = var.redis_node_type
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  transit_encryption_enabled = true
  at_rest_encryption_enabled = true
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.data.id]
  tags                       = local.tags
}
```

`modules/hiretrack/storage.tf`:

```hcl
resource "aws_s3_bucket" "resumes" {
  bucket = "${local.name}-resumes"
  tags   = local.tags
}
resource "aws_s3_bucket_public_access_block" "resumes" {
  bucket                  = aws_s3_bucket.resumes.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_versioning" "resumes" {
  bucket = aws_s3_bucket.resumes.id
  versioning_configuration { status = "Enabled" }
}
resource "aws_s3_bucket_server_side_encryption_configuration" "resumes" {
  bucket = aws_s3_bucket.resumes.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

# Frontend: private bucket served through CloudFront
resource "aws_s3_bucket" "web" {
  bucket = "${local.name}-web"
  tags   = local.tags
}
resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${local.name}-web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}
```

Add the CloudFront distribution (S3 origin for the app, ALB origin for `/api/*` so the browser stays same-origin), an ACM certificate and Route 53 records in the same file. Copy the CSP and security headers from Phase 12's `nginx.conf` into a CloudFront response headers policy.

`modules/hiretrack/ecr.tf`:

```hcl
resource "aws_ecr_repository" "svc" {
  for_each             = toset(["api-gateway", "identity-service", "resume-service", "ai-service", "match-service",
                                "application-service", "job-feed-service", "analytics-service"])
  name                 = "hiretrack/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
  tags                 = local.tags
}
```

`modules/hiretrack/secrets.tf`:

```hcl
resource "aws_secretsmanager_secret" "app" {
  for_each = toset(["openai", "anthropic", "adzuna", "internal-token", "jwt-private-key",
                    "db-identity", "db-resume", "db-ai", "db-match", "db-application", "db-feed", "db-analytics"])
  name     = "${local.name}/${each.key}"
  tags     = local.tags
}
```

Secret **values** are set by hand (`aws secretsmanager put-secret-value`), never in Terraform state. Generate a new production JWT key pair with `tools/gen-dev-keys.sh` in a temporary directory, upload the private key, and delete the local copy.

**Verify**

```bash
cd deploy/terraform/envs/staging
terraform init && terraform plan -out plan.tfplan     # read it before applying
terraform apply plan.tfplan
aws eks update-kubeconfig --name hiretrack-staging --region <region>
kubectl get nodes
```

Expected: 4 `Ready` nodes. RDS, MSK and ElastiCache show `available` in the console. MSK takes 20–30 minutes.

## Step 13.3 — Cluster add-ons

Install them once per cluster, with Helm:

| Add-on | Why |
| --- | --- |
| AWS Load Balancer Controller | ALB for the gateway |
| External Secrets Operator | Secrets Manager → Kubernetes Secrets |
| KEDA | scale feed workers on Kafka lag |
| Karpenter (or cluster-autoscaler) | add and remove nodes |
| metrics-server | HPA CPU metrics |
| kube-prometheus-stack, Tempo, Loki | Phase 10 monitoring, in-cluster |
| Argo CD | GitOps delivery |

Give each controller its IRSA role, as in each project's install guide.

Bootstrap the database users and schemas once, with the same SQL as Phase 0's `init.sql`. Run it from a temporary pod using the master password from Secrets Manager. Use the generated per-service passwords from `db-*` secrets instead of `<schema>_pw`.

## Step 13.4 — The Helm chart

```text
deploy/helm/hiretrack-service/
├── Chart.yaml
├── values.yaml
└── templates/
    ├── deployment.yaml  service.yaml  hpa.yaml  pdb.yaml  networkpolicy.yaml
    ├── externalsecret.yaml  serviceaccount.yaml  ingress.yaml  scaledobject.yaml
deploy/helm/values/
    ├── common.yaml  api-gateway.yaml  identity-service.yaml  ...  job-feed-service.yaml
```

`Chart.yaml`:

```yaml
apiVersion: v2
name: hiretrack-service
version: 0.1.0
description: One HireTrack microservice
```

`values.yaml` (defaults):

```yaml
image: { repository: "", tag: "" }
replicas: 2
port: 8080
env: {}
secretEnv: {}               # ENV_NAME: { secret: <name>, key: <key> }
secretFiles: []             # [{ secret: <name>, key: <key>, mountPath: /keys/private.pem }]
resources:
  requests: { cpu: 500m, memory: 768Mi }
  limits: { memory: 768Mi }
autoscaling: { enabled: true, minReplicas: 2, maxReplicas: 10, targetCPU: 70 }
keda: { enabled: false }
ingress: { enabled: false }
allowFrom: [api-gateway]    # NetworkPolicy: who may call this service
serviceAccount: { roleArn: "" }
```

`templates/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels: { app: {{ .Release.Name }} }
spec:
  replicas: {{ .Values.replicas }}
  strategy:
    rollingUpdate: { maxUnavailable: 0, maxSurge: 1 }
  selector:
    matchLabels: { app: {{ .Release.Name }} }
  template:
    metadata:
      labels: { app: {{ .Release.Name }} }
    spec:
      serviceAccountName: {{ .Release.Name }}
      terminationGracePeriodSeconds: 30
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector: { matchLabels: { app: {{ .Release.Name }} } }
      securityContext: { runAsNonRoot: true, runAsUser: 10001, fsGroup: 10001 }
      containers:
        - name: app
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          ports: [{ containerPort: {{ .Values.port }} }]
          env:
            - { name: SPRING_PROFILES_ACTIVE, value: prod }
            - { name: SERVER_PORT, value: "{{ .Values.port }}" }
            {{- range $k, $v := .Values.env }}
            - { name: {{ $k }}, value: {{ $v | quote }} }
            {{- end }}
            {{- range $k, $v := .Values.secretEnv }}
            - name: {{ $k }}
              valueFrom: { secretKeyRef: { name: {{ $v.secret }}, key: {{ $v.key }} } }
            {{- end }}
          resources: {{- toYaml .Values.resources | nindent 12 }}
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: {{ .Values.port }} }
            periodSeconds: 5
            failureThreshold: 3
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: {{ .Values.port }} }
            initialDelaySeconds: 60
            periodSeconds: 10
          lifecycle:
            preStop: { exec: { command: ["sleep", "5"] } }   # let the load balancer drain first
          securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true }
          volumeMounts:
            - { name: tmp, mountPath: /tmp }
            {{- range .Values.secretFiles }}
            - { name: {{ .secret }}, mountPath: {{ .mountPath }}, subPath: {{ .key }}, readOnly: true }
            {{- end }}
      volumes:
        - { name: tmp, emptyDir: {} }
        {{- range .Values.secretFiles }}
        - { name: {{ .secret }}, secret: { secretName: {{ .secret }} } }
        {{- end }}
```

`templates/hpa.yaml`:

```yaml
{{- if and .Values.autoscaling.enabled (not .Values.keda.enabled) }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: {{ .Release.Name }} }
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: {{ .Release.Name }} }
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: {{ .Values.autoscaling.targetCPU }} } }
{{- end }}
```

`templates/scaledobject.yaml`, which scales feed workers on Kafka lag:

```yaml
{{- if .Values.keda.enabled }}
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata: { name: {{ .Release.Name }} }
spec:
  scaleTargetRef: { name: {{ .Release.Name }} }
  minReplicaCount: {{ .Values.keda.min }}
  maxReplicaCount: {{ .Values.keda.max }}          # no more than the topic's partitions (12)
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: {{ .Values.keda.bootstrapServers }}
        consumerGroup: {{ .Values.keda.consumerGroup }}
        topic: {{ .Values.keda.topic }}
        lagThreshold: "100"
        sasl: aws_msk_iam
        tls: enable
{{- end }}
```

`templates/pdb.yaml`:

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: {{ .Release.Name }} }
spec:
  minAvailable: 1
  selector: { matchLabels: { app: {{ .Release.Name }} } }
```

`templates/networkpolicy.yaml`. Only the listed callers may connect, which is the second layer of protection for `/internal/**`:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: { name: {{ .Release.Name }} }
spec:
  podSelector: { matchLabels: { app: {{ .Release.Name }} } }
  policyTypes: [Ingress]
  ingress:
    - from:
        {{- range .Values.allowFrom }}
        - podSelector: { matchLabels: { app: {{ . }} } }
        {{- end }}
        - namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: monitoring } }   # Prometheus scrapes
      ports: [{ port: {{ .Values.port }} }]
```

Write `service.yaml` (ClusterIP on port 80 → 8080), `serviceaccount.yaml` (IRSA annotation from `serviceAccount.roleArn`), `externalsecret.yaml` (one `ExternalSecret` per secret referenced in `secretEnv`/`secretFiles`) and `ingress.yaml` (ALB, gateway only) in the same style.

`values/match-service.yaml`, an example per-service values file:

```yaml
image: { repository: <account>.dkr.ecr.<region>.amazonaws.com/hiretrack/match-service, tag: "<git-sha>" }
allowFrom: [api-gateway, application-service]
env:
  DB_HOST: <rds-endpoint>
  KAFKA_BOOTSTRAP: <msk-iam-bootstrap>
  REDIS_HOST: <redis-primary-endpoint>
  RESUME_SERVICE_URL: http://resume-service
  AI_SERVICE_URL: http://ai-service
  KAFKA_PARTITIONS: "12"
  KAFKA_REPLICAS: "3"
  OTLP_TRACING_ENDPOINT: http://tempo.monitoring:4318/v1/traces
  TRACING_SAMPLE_RATE: "0.1"
  DB_POOL_SIZE: "8"
secretEnv:
  DB_PASSWORD: { secret: db-match, key: password }
  HIRETRACK_INTERNAL_TOKEN: { secret: internal-token, key: token }
```

`values/job-feed-service.yaml` additions for KEDA:

```yaml
keda:
  enabled: true
  min: 1
  max: 12
  bootstrapServers: <msk-iam-bootstrap>
  consumerGroup: job-feed-workers
  topic: feed-commands
allowFrom: [api-gateway]
```

`values/identity-service.yaml` mounts the signing key:

```yaml
secretFiles:
  - { secret: jwt-private-key, key: private.pem, mountPath: /keys/private.pem }
env:
  HIRETRACK_KEYS_DIR: /keys
  COOKIE_SECURE: "true"
  JWT_KEY_ID: prod-key-1
allowFrom: [api-gateway, resume-service, match-service, application-service, job-feed-service, analytics-service, ai-service]  # JWKS readers
```

**Connection budget:** the sum of pods × `DB_POOL_SIZE` must stay below the RDS `max_connections`. At the launch scale, 8 services × 2–3 pods × 8 is about 190. Use RDS Proxy as traffic grows.

## Step 13.5 — Delivery: GitHub Actions → ECR → Argo CD

`.github/workflows/deploy.yml`:

```yaml
name: deploy
on:
  push:
    branches: [main]

permissions:
  id-token: write          # OIDC to AWS; no long-lived keys in GitHub
  contents: read

jobs:
  images:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service: [api-gateway, identity-service, resume-service, ai-service, match-service, application-service, job-feed-service, analytics-service]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '25' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew :services:${{ matrix.service }}:bootJar --no-daemon
      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_DEPLOY_ROLE_ARN }}
          aws-region: ${{ vars.AWS_REGION }}
      - id: ecr
        uses: aws-actions/amazon-ecr-login@v2
      - uses: docker/setup-qemu-action@v3
      - uses: docker/setup-buildx-action@v3
      - uses: docker/build-push-action@v6
        with:
          context: .
          file: deploy/docker/Dockerfile.service
          build-args: SERVICE=${{ matrix.service }}
          platforms: linux/arm64            # Graviton nodes
          push: true
          tags: ${{ steps.ecr.outputs.registry }}/hiretrack/${{ matrix.service }}:${{ github.sha }}

  promote-staging:
    needs: images
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { repository: <you>/hiretrack-gitops, token: "${{ secrets.GITOPS_TOKEN }}" }
      - name: Set the new image tag for staging
        run: |
          for f in staging/values/*.yaml; do
            sed -i "s/tag: \".*\"/tag: \"${{ github.sha }}\"/" "$f"
          done
          git config user.name ci && git config user.email ci@users.noreply.github.com
          git commit -am "staging: ${{ github.sha }}" && git push
```

Argo CD watches the GitOps repository. Staging syncs automatically. Production syncs after someone merges the staging tag into `prod/values/` and approves the sync in Argo CD.

For risky changes to ai-service or job-feed-service, use **Argo Rollouts** canaries: 10% of traffic for 15 minutes, analysed against the Phase 10 SLO queries, with automatic rollback when the error ratio or p95 breaks the SLO.

## Step 13.6 — Backups and disaster recovery

| Data | Protection | Recovery target |
| --- | --- | --- |
| RDS PostgreSQL | Automated snapshots + point-in-time recovery (30 days prod), Multi-AZ | Recovery point ≤ 5 min, recovery time ≤ 1 h |
| S3 resumes | Versioning; optional cross-region replication | Restore a previous version |
| Kafka | Not the source of truth. Outboxes re-create events; analytics rebuilds from `application-events` (kept forever) | Minutes |
| Redis | Caches, limits and quotas only | Rebuilt automatically |

Practise a restore into staging **every quarter**:

1. Restore RDS to a point in time as a new instance.
2. Point staging at it.
3. Run the Phase 11 end-to-end suite.
4. Record how long it took against the recovery time target.

## Step 13.7 — Verify the environment

```bash
kubectl -n hiretrack get pods -o wide              # 2+ pods per service, spread across zones
kubectl -n hiretrack get hpa,scaledobject
curl -s https://app.<domain>/api/v1/applications | jq -c .error     # UNAUTHENTICATED through CloudFront → ALB → gateway
```

Then:

1. **k6 on staging** (Phase 11, Step 11.4) with `BASE_URL=https://staging.<domain>/api/v1` and ai-service set to the fake provider in staging only (`AI_PROVIDER=fake`, never in prod: the service refuses). All thresholds pass, and the HPA adds pods during the run and removes them afterwards.
2. **Kill a node:** `kubectl drain <node> --ignore-daemonsets --delete-emptydir-data`. No user-visible errors beyond retried in-flight requests.
3. **A failed deploy:** push an image whose readiness probe fails. Argo CD shows it degraded, the old pods keep serving, and you roll back with one click.
4. **Restore drill** (Step 13.6) within the recovery time target.

## Done when

- [ ] `terraform apply` creates staging from nothing; prod uses the same module
- [ ] Images build for arm64, are scanned and pushed to ECR on every merge
- [ ] Argo CD deploys staging automatically and prod after approval
- [ ] HPA and KEDA scale under load; pods spread across zones; PDBs hold during node drains
- [ ] Secrets come only from Secrets Manager; nothing secret is in git or images
- [ ] The restore drill meets the recovery time target
- [ ] Commit: `git commit -am "Phase 13: AWS deployment"`
