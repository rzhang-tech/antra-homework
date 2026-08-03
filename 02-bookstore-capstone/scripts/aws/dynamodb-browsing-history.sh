#!/usr/bin/env bash
#
# Creates the UserBrowsingHistory table. Step 9a.
#
# Idempotent: run it twice and the second run reports what exists and changes nothing. That matters
# more for infrastructure than for code - a provisioning script you are afraid to re-run is a script
# you will edit by hand under pressure.
#
#   ./dynamodb-browsing-history.sh
#
# Uses whatever profile and region `aws configure` set up. Nothing here reads a credential.

set -euo pipefail

TABLE="UserBrowsingHistory"
REGION="$(aws configure get region)"

echo "Region: ${REGION}"

if aws dynamodb describe-table --table-name "${TABLE}" >/dev/null 2>&1; then
  echo "${TABLE} already exists - leaving it alone."
else
  echo "Creating ${TABLE}..."

  # THE KEY DESIGN, which the assignment asks to be justified.
  #
  # Partition key `userId`:
  #   DynamoDB spreads items across partitions by hashing this value, and every query must supply it.
  #   userId is the right choice on both counts. There are many users and each generates a modest,
  #   independent stream of views, so the hash spreads writes evenly - no single partition absorbs a
  #   disproportionate share. And it matches the only access pattern that exists: "what has THIS user
  #   viewed", never "who viewed this book".
  #
  #   What would have been wrong, and why it is tempting:
  #     bookId      popular books are popular. A best-seller becomes a hot partition while the rest of
  #                 the table idles, and DynamoDB throttles per partition, not per table.
  #     viewedAt    every write on a given day lands on one partition. The hottest possible key: all
  #                 of today's traffic on one shard and yesterday's shard permanently idle.
  #     a constant  the degenerate case of the above - one partition for the whole table.
  #
  # Sort key `viewedAt`, an ISO-8601 UTC string:
  #   Items within a partition are stored ordered by this, so "newest first" is a backwards read of an
  #   already-sorted index rather than a sort. ISO-8601 is chosen precisely because its lexicographic
  #   order IS its chronological order - 2026-08-02T18:05:47Z sorts after 2026-08-02T09:00:00Z as a
  #   plain string. A locale-formatted timestamp, or epoch millis as a string, would not.
  #
  # PAY_PER_REQUEST rather than provisioned capacity:
  #   Nobody knows this table's traffic, and provisioned capacity means guessing a number and then
  #   either paying for idle capacity or being throttled. On-demand costs nothing when nothing happens,
  #   which for a capstone means it costs nothing. The trade at scale is real - provisioned is
  #   substantially cheaper for steady, predictable load - and switching is a one-line change.
  aws dynamodb create-table \
    --table-name "${TABLE}" \
    --attribute-definitions \
        AttributeName=userId,AttributeType=S \
        AttributeName=viewedAt,AttributeType=S \
    --key-schema \
        AttributeName=userId,KeyType=HASH \
        AttributeName=viewedAt,KeyType=RANGE \
    --billing-mode PAY_PER_REQUEST \
    --tags Key=project,Value=bookstore-capstone Key=step,Value=9a \
    --no-cli-pager

  echo "Waiting for ${TABLE} to become ACTIVE..."
  aws dynamodb wait table-exists --table-name "${TABLE}"
fi

# TTL can only be set once the table is ACTIVE, which is why this is separate from create-table.
TTL_STATUS="$(aws dynamodb describe-time-to-live --table-name "${TABLE}" \
  --query "TimeToLiveDescription.TimeToLiveStatus" --output text)"

if [ "${TTL_STATUS}" = "ENABLED" ]; then
  echo "TTL already enabled on ${TABLE}."
else
  echo "Enabling TTL on attribute expiresAt..."

  # `expiresAt` is a NUMBER holding epoch SECONDS. Not milliseconds, not a string, not ISO-8601 -
  # DynamoDB silently ignores an attribute of the wrong type, so a TTL that is quietly doing nothing
  # is the normal way this is got wrong. Milliseconds are the specific trap: 1e12 seconds is the year
  # 33658, so the item simply never expires and nothing anywhere complains.
  aws dynamodb update-time-to-live \
    --table-name "${TABLE}" \
    --time-to-live-specification "Enabled=true,AttributeName=expiresAt" \
    --no-cli-pager
fi

echo
echo "Done. Current state:"
aws dynamodb describe-table --table-name "${TABLE}" \
  --query "Table.{Name:TableName,Status:TableStatus,Billing:BillingModeSummary.BillingMode,Keys:KeySchema}" \
  --output table
aws dynamodb describe-time-to-live --table-name "${TABLE}" --output table
