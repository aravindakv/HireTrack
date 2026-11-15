# Runbook: AI alerts (AiMatchLatencyHigh, AiMatchSuccessLow, AiSpendOverBudget)

| Alert | Severity | Fires when |
| --- | --- | --- |
| AiMatchLatencyHigh | page | Match p95 above 15 s for 10 min |
| AiMatchSuccessLow | page | Match success below 99% over 1 h, for 15 min (`busy` is not counted as a failure) |
| AiSpendOverBudget | page | AI spend in the last 24 h above 120% of the daily budget |

## What it means
Matches are slow, failing, or costing more than budget. Users see 502/429 on the Match page, or nothing is wrong
for users but the bill is growing.

## First three checks
1. Grafana → "HireTrack — AI and cost": in "AI calls by outcome", which outcome is rising?
   `error` (provider 5xx, network errors and timeouts), `circuit_open` (breaker is failing fast after repeated errors),
   `rate_limited` (provider 429), `busy` (our bulkhead is full), `refusal` or `invalid` (the model's output was unusable).
   "Bulkhead free slots" at 0 means we are limiting ourselves.
2. The AI provider's status page (OpenAI by default; Anthropic if `hiretrack.ai.provider=anthropic`).
3. Tempo: open a slow `POST /api/v1/matches` trace. Is the time in ai-service → provider, or before it
   (match-service → resume-service, database)?

## Mitigate
- Provider outage or rate limiting: lower `hiretrack.ai.resilience.max-concurrent-matches`
  (env `HIRETRACK_AI_RESILIENCE_MAX_CONCURRENT_MATCHES`, default 32), or switch provider with `AI_PROVIDER=anthropic`
  (needs `ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL` and `ANTHROPIC_FEED_MODEL`) and restart ai-service.
- Spend spike: lower `hiretrack.ai.daily-match-quota` (env `HIRETRACK_AI_DAILY_MATCH_QUOTA`, default 20 per user per day);
  pause the feed with `FEED_SCHEDULERS_ENABLED=false` on job-feed-service; check the top users by cost:
  ```sql
  SELECT user_id, sum(cost_usd) FROM ai.ai_usage
  WHERE created_at > now() - interval '1 day' GROUP BY 1 ORDER BY 2 DESC LIMIT 10;
  ```
- Invalid output rising after a model or prompt change: roll back the change; run the eval set (Phase 11).

## Notes
- AiSpendOverBudget compares against the number in `deploy/observability/rules/hiretrack-slo.yml` (`1.2 * 50`):
  set it to your real daily budget.
- Spend is computed from `hiretrack.ai.prices` in ai-service. While the prices are 0, spend is 0 and this alert
  can never fire.

## Afterwards
Write a short incident note: start/end time, impact, cause, follow-up.
