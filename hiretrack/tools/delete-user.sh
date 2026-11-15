#!/usr/bin/env bash
# Deletes every record of one user across all services. Irreversible. Usage: tools/delete-user.sh <userId>
set -euo pipefail
U="${1:-}"
[[ "$U" =~ ^[0-9]+$ ]] || { echo "Usage: tools/delete-user.sh <userId>  (userId must be numeric)"; exit 1; }
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