# Phase 14 — Production Readiness Review and Launch

**Goal:** a deliberate go/no-go before real users arrive, plus the operating basics: runbooks, incident handling, data deletion and cost controls.

If you only run HireTrack for yourself on `localhost` (Phase 12), you can skip the launch plan. Do still keep the AI budget alert and the backup script.

**Time:** 2–3 days, plus a one-week soft launch.

---

## Step 14.1 — Readiness review

Go through the list with every box ticked and evidence linked (dashboard screenshot, test report, pull request). Any unticked box is a "no-go" until it's fixed or explicitly accepted, in writing, as a known risk.

### Reliability
- [ ] SLOs from spec 10.2 are published; burn-rate alerts are live and were tested (Phase 10, Step 10.6)
- [ ] Every paging alert links to a runbook in `docs/runbooks/`
- [ ] Every service runs 2+ replicas across zones, with PodDisruptionBudgets
- [ ] Timeouts on every outbound call; retries only on idempotent operations; bulkhead and circuit breaker on AI calls
- [ ] k6 passed at 4× expected peak on staging (Phase 11 thresholds)
- [ ] A failed deploy rolls back automatically, tested (Phase 13, Step 13.7)

### Data
- [ ] RDS point-in-time recovery is on; a restore drill met the recovery time target
- [ ] Every creating endpoint takes an `Idempotency-Key`; every consumer deduplicates (Phase 1 tests, Phase 11 retry suite)
- [ ] Retention jobs run: feed items (30 days), outbox (7 days), idempotency keys (24 h), feed runs (90 days)
- [ ] `application-events` keeps events forever; a rebuild of analytics was rehearsed (Phase 7, Step 7.9)

### Security and privacy
- [ ] No secrets in git, images or Terraform state; Secrets Manager + IRSA only
- [ ] HTTPS everywhere at the edge; the refresh cookie is `Secure; HttpOnly; SameSite=Strict`
- [ ] NetworkPolicies limit `/internal/**` callers; the internal token is set and rotated
- [ ] Authorization tests (user B → `404`) pass for every endpoint
- [ ] ZAP baseline has no High findings; image scans have no High/Critical
- [ ] The signup page states that resumes and job descriptions go to the AI provider (OpenAI by default) and searches go to Adzuna
- [ ] The account deletion procedure (Step 14.3) was rehearsed on staging
- [ ] Adzuna attribution is shown; usage is within your Adzuna plan's terms and quota
- [ ] The AI provider account's data-usage settings match what your privacy notice says

### Cost
- [ ] Model IDs and **prices are filled in** (`hiretrack.ai.prices`) and match the provider's current price list
- [ ] The chosen models passed the AI eval gate (Phase 11, Step 11.5); the decision is recorded with eval results and cost per call
- [ ] The `AiSpendOverBudget` threshold is set to your real daily budget
- [ ] The daily match quota and feed cap fit the budget (spec 10.4 formulas with your prices)
- [ ] AWS Budgets alerts at 80% and 100% of the monthly infrastructure estimate
- [ ] The provider rate-limit tier covers peak requests and tokens per minute (spec 10.3)

## Step 14.2 — Operating basics

**On-call.** For a small project, a single owner with phone alerts for `severity: page` is fine. Route `ticket` alerts to email or an issue tracker.

**Incident process:**

1. **Acknowledge** the page.
2. **Open the runbook** linked in the alert.
3. **Mitigate first:** roll back, switch the provider, pause the feed (`FEED_SCHEDULERS_ENABLED=false`), or lower quotas.
4. **Write it up** within 48 hours: timeline, impact, cause, follow-up actions.

**Deploys:**

- Deploy during working hours.
- Don't deploy on Fridays before going offline.
- Use a canary for ai-service and job-feed-service.
- Watch the Service overview dashboard for 15 minutes after each production sync.

## Step 14.3 — Account deletion (support procedure for v1)

Spec 2 leaves account deletion out of the UI in v1. Handle requests with this script. Run it from a pod or bastion that can reach RDS and S3.

`tools/delete-user.sh`:

```bash
#!/usr/bin/env bash
# Deletes every record of one user across all services. Irreversible. Usage: tools/delete-user.sh <userId>
set -euo pipefail
U="$1"
read -r -p "Permanently delete ALL data for user $U? Type the user ID again to confirm: " again
[ "$again" = "$U" ] || { echo "Cancelled."; exit 1; }

psql "$ADMIN_DATABASE_URL" -v ON_ERROR_STOP=1 <<SQL
BEGIN;
DELETE FROM feed.feed_items         WHERE user_id = $U;
DELETE FROM feed.feed_runs          WHERE user_id = $U;
DELETE FROM feed.search_profiles    WHERE user_id = $U;
DELETE FROM feed.user_activity      WHERE user_id = $U;
DELETE FROM analytics.interview_projection WHERE user_id = $U;
DELETE FROM analytics.app_projection       WHERE user_id = $U;
DELETE FROM application.applications WHERE user_id = $U;      -- interviews cascade
DELETE FROM match.matches           WHERE user_id = $U;
DELETE FROM ai.ai_batch_items       WHERE user_id = $U;
DELETE FROM ai.ai_requests          WHERE user_id = $U;
UPDATE ai.ai_usage SET user_id = 0  WHERE user_id = $U;       -- keep cost totals, drop the link to the person
DELETE FROM resume.resumes          WHERE user_id = $U;
DELETE FROM identity.users          WHERE id = $U;            -- refresh tokens cascade
DELETE FROM resume.idempotency_keys      WHERE user_id = $U;
DELETE FROM application.idempotency_keys WHERE user_id = $U;
DELETE FROM match.idempotency_keys       WHERE user_id = $U;
DELETE FROM feed.idempotency_keys        WHERE user_id = $U;
COMMIT;
SQL

aws s3 rm "s3://$RESUME_BUCKET/resumes/$U/" --recursive
echo "User $U deleted. Kafka events keep only IDs (no personal text) and age out with topic retention,"
echo "except application-events (kept forever, IDs and stages only)."
```

Rehearse it on staging and confirm the user's resumes, matches, applications and feed are gone in every service. Event payloads never contain resume text or job descriptions (Phase 1 rule), which is what makes keeping `application-events` forever acceptable.

## Step 14.4 — Launch plan

1. **Feature flags off:** deploy with `FEED_SCHEDULERS_ENABLED=false`, so the daily feed doesn't run yet.
2. **Invite-only soft launch** (10–50 people) for one week. Each day, watch:
   - SLO dashboards: availability, p95, match latency
   - the AI & cost dashboard: spend per active user against your budget formula
   - error logs and dead-letter topics
3. **Turn on the feed** for the soft-launch group and watch one full morning cycle: runs planned, Adzuna calls shared by query key, batches scored, feed delivered by 08:30 local.
4. **Go/no-go meeting:** every readiness box ticked, a week of SLOs met, spend within budget.
5. **Open signups.** Raise rate limits and replica counts if the soft launch showed a need.
6. **Review after 30 days:** compare real traffic and spend against spec 10.3/10.4, update the capacity and cost tables with measured numbers, and re-run the AI eval to see if a cheaper model now passes.

## Done when

- [ ] Every readiness box is ticked or recorded as an accepted risk
- [ ] Runbooks, on-call routing and the incident template exist
- [ ] Account deletion was rehearsed on staging
- [ ] The soft launch ran for a week within SLOs and budget
- [ ] Signups are open, and the 30-day review is scheduled
