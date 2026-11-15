# Runbooks

Every alert in `hiretrack/deploy/observability/rules/hiretrack-slo.yml` links to one of these through its `runbook` annotation.
`severity: page` alerts wake someone up; `severity: ticket` alerts go to email or the issue tracker.

Shell commands in these runbooks (`docker compose -f deploy/...`) are run from the `hiretrack/` directory, not the repo root.

| Alert | Severity | Runbook |
| --- | --- | --- |
| ApiErrorBudgetFastBurn | page | [api-errors.md](api-errors.md) |
| ApiErrorBudgetSlowBurn | ticket | [api-errors.md](api-errors.md) |
| AiMatchLatencyHigh | page | [ai.md](ai.md) |
| AiMatchSuccessLow | page | [ai.md](ai.md) |
| AiSpendOverBudget | page | [ai.md](ai.md) |
| OutboxStuck | page | [events.md](events.md) |
| ConsumerLagHigh | ticket | [events.md](events.md) |
| ReadLatencyHigh | ticket | [latency.md](latency.md) |
| DbPoolSaturated | ticket | [latency.md](latency.md) |
| FeedRunsFailing | ticket | [feed.md](feed.md) |

## Adding a runbook
Use the same sections as [ai.md](ai.md):

1. **What it means:** what users see, in one or two sentences.
2. **First three checks:** where to look, in order, with the exact query or command.
3. **Mitigate:** how to stop the damage first. Root cause can wait.
4. **Afterwards:** the incident note (start/end time, impact, cause, follow-up).

Then point the alert's `runbook:` annotation at the file and add it to the table above.
