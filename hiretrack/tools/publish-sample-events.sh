#!/usr/bin/env bash
# Usage: tools/publish-sample-events.sh <userId>
set -euo pipefail
U="${1:-900}"
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
TODAY=$(date -u +%Y-%m-%d)
# IDs derived from the user, so they never collide with rows from other users or earlier runs
# (the projector ignores events whose aggregateVersion isn't newer than the stored row).
A1=$((U * 1000 + 1)); A2=$((U * 1000 + 2)); I1=$((U * 1000 + 1))
# "Now" is always inside the dashboard's current Monday-to-Monday week, in any timezone.
ev() {  # type aggregateId version payload
  printf '%s|{"eventId":"%s","type":"%s","schemaVersion":1,"userId":%s,"aggregateId":"%s","aggregateVersion":%s,"occurredAt":"%s","payload":%s}\n' \
    "$U" "$(uuidgen)" "$1" "$U" "$2" "$3" "$NOW" "$4"
}
{
  ev application.created       application-$A1 0 "{\"applicationId\":$A1,\"stage\":\"WISHLIST\",\"appliedDate\":null,\"respondedAt\":null}"
  ev application.stage-changed application-$A1 1 "{\"applicationId\":$A1,\"stage\":\"APPLIED\",\"appliedDate\":\"$TODAY\",\"respondedAt\":null}"
  ev application.stage-changed application-$A1 2 "{\"applicationId\":$A1,\"stage\":\"INTERVIEW\",\"appliedDate\":\"$TODAY\",\"respondedAt\":\"$NOW\"}"
  ev application.created       application-$A2 0 "{\"applicationId\":$A2,\"stage\":\"WISHLIST\",\"appliedDate\":null,\"respondedAt\":null}"
  ev application.stage-changed application-$A2 1 "{\"applicationId\":$A2,\"stage\":\"APPLIED\",\"appliedDate\":\"$TODAY\",\"respondedAt\":null}"
  ev interview.scheduled       interview-$I1 0 "{\"interviewId\":$I1,\"applicationId\":$A1,\"scheduledAt\":\"$NOW\",\"round\":\"HR\"}"
} | docker compose -f deploy/docker-compose.yml exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 --topic application-events --property parse.key=true --property key.separator='|'
echo "published 6 events for user $U (applications $A1, $A2; interview $I1)"