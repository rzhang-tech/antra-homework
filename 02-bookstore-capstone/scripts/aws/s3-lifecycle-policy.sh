#!/usr/bin/env bash
#
# The cost optimisation. Step 9d.
#
#   ./s3-lifecycle-policy.sh
#
# Versioning (enabled in 9b) makes a replaced cover recoverable, and it also means the bucket NEVER
# shrinks: every overwrite keeps the previous bytes forever, invisibly, and you pay for all of them.
# That is the trade this policy closes - keep old versions long enough to undo a mistake, then stop.

set -euo pipefail

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="bookstore-covers-${ACCOUNT: -6}"

echo "Bucket: ${BUCKET}"

aws s3api put-bucket-lifecycle-configuration --bucket "${BUCKET}" \
  --lifecycle-configuration '{
    "Rules": [
      {
        "ID": "expire-old-cover-versions",
        "Status": "Enabled",
        "Filter": {"Prefix": "covers/"},
        "NoncurrentVersionExpiration": {"NoncurrentDays": 30},
        "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 7}
      },
      {
        "ID": "current-covers-to-infrequent-access",
        "Status": "Enabled",
        "Filter": {"Prefix": "covers/"},
        "Transitions": [
          {"Days": 90, "StorageClass": "STANDARD_IA"}
        ]
      }
    ]
  }' --no-cli-pager

echo "Applied."
aws s3api get-bucket-lifecycle-configuration --bucket "${BUCKET}" --output json

cat <<'NOTES'

What each rule buys, and what it costs:

  NoncurrentVersionExpiration 30 days
      The one that actually saves money. A cover replaced today is recoverable for a month and then
      the old bytes are deleted for good. Without it, a book whose cover is updated monthly costs
      twelve covers a year and shows one. The 30 days is the real decision: it is how long somebody
      has to notice a wrong upload, and it should be longer than any plausible holiday.

  AbortIncompleteMultipartUpload 7 days
      The rule almost nobody sets and everybody eventually needs. A multipart upload that fails
      halfway leaves its parts in the bucket, BILLED, and INVISIBLE to `s3 ls` - which is why
      "why is this bucket 400 GB when it holds 2 GB of files" is such a common question. Covers are
      too small to be multipart today; the rule costs nothing and removes a whole class of surprise.

  Transition to STANDARD_IA after 90 days
      Roughly 45% cheaper storage for objects that are rarely read, which a cover from three months
      ago mostly is. The catch is honest: IA charges for retrieval and has a 128 KB minimum billable
      size, so a bucket of 20 KB thumbnails read constantly would cost MORE in IA than in Standard.
      It pays here because covers are read via presigned URLs a browser caches, and because the
      alternative - Intelligent-Tiering - adds a per-object monitoring fee that only wins at volumes
      far above this.

Not applied, deliberately:

  Expiring CURRENT versions. A cover with no book is a bug; a book with no cover after 400 days
  would be a feature nobody asked for. Lifecycle rules that delete live data need a much stronger
  reason than tidiness.
NOTES
