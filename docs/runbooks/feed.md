# Runbook: Job feed (FeedRunsFailing)

| Alert | Severity | Fires when |
| --- | --- | --- |
| FeedRunsFailing | ticket | More than 1% of feed runs failed in the last hour, for 30 min |

## What it means
Users' daily job feeds aren't being refreshed. They see an older feed and the "last run" error on the Job feed
page. Nothing else in the app is affected. Failed scheduled runs retry the next day on their own.

## First three checks
1. Why are runs failing? The error is stored on each run:
   ```sql
   SELECT error, trigger, count(*) FROM feed.feed_runs
   WHERE status = 'FAILED' AND updated_at > now() - interval '1 hour' GROUP BY 1, 2 ORDER BY 3 DESC;
   ```
   | Error | Cause |
   | --- | --- |
   | Job search is unavailable right now. Will retry tomorrow. | Adzuna call failed (outage, auth, quota) or another unexpected error |
   | Couldn't load your resume. Will retry tomorrow. | resume-service unavailable |
   | Add a resume to get job matches. | User has no active resume (expected, not an incident) |
   | Scoring failed. Will retry tomorrow. | The ai-service batch failed 3 times (`max-score-attempts`) |
   | No search profile. | Profile deleted after the run was planned (expected) |
2. Adzuna: job-feed-service logs (`{job="hiretrack", service="job-feed-service", level=~"WARN|ERROR"}`) for the HTTP
   status. 401/403 means bad `ADZUNA_APP_ID`/`ADZUNA_APP_KEY`; 429 means the API quota is used up; 5xx means an outage.
3. AI scoring: runs stuck in `SCORING`, and the ai-service batches behind them:
   ```sql
   SELECT status, count(*) FROM feed.feed_runs WHERE created_at > now() - interval '1 day' GROUP BY 1;
   ```
   Then check ai-service logs and the provider's batch status (see [ai.md](ai.md)).

## Mitigate
- Adzuna outage: wait it out; runs retry tomorrow. Users can still use manual refresh once it recovers.
- Adzuna quota exhausted: pause scheduled runs with `FEED_SCHEDULERS_ENABLED=false` until the quota resets, or lower
  how many users are planned. The Redis query cache already shares one Adzuna call per search per hour.
- Bad Adzuna credentials: fix the secret and restart job-feed-service.
- AI batch failures: follow [ai.md](ai.md); pause the feed if it is driving AI cost.

## Afterwards
Write a short incident note if many users went a day or more without a feed: start/end time, impact, cause, follow-up.
