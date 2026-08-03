#!/usr/bin/env bash
#
# Deletes everything Step 9 created. Run it when you are done with the demo.
#
#   ./teardown.sh          shows what would be deleted
#   ./teardown.sh --yes    actually deletes
#
# This exists because a capstone that leaves resources running is a capstone that sends somebody a
# bill. Nothing here costs much - DynamoDB on-demand and S3 at this volume are cents - but "cents"
# is a statement about today's usage, and an idle bucket is still a bucket you have forgotten about.
#
# It is also the honest test of the provisioning scripts: if teardown misses something, the scripts
# created something nobody wrote down.

set -euo pipefail

CONFIRM="${1:-}"
# THE REGION THE RESOURCES ACTUALLY LIVE IN, not whatever this machine happens to default to.
#
# This used to be `$(aws configure get region)`, and that is a quiet way to lose money. Run from a box
# configured for a different region - a new EC2 instance, a colleague's laptop, a CI runner with no
# config at all - the script looks in an empty region, finds nothing, reports every resource as already
# gone, and exits successfully. **A teardown that cleans the wrong region is worse than no teardown**,
# because it tells you that you are done.
#
# Overridable for anyone who genuinely deploys elsewhere; the default is where Step 9 put things.
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="bookstore-covers-${ACCOUNT: -6}"

cat <<EOF
This will delete, in account ${ACCOUNT} / ${REGION}:

  Lambda      bookstore-cover-processor
  CloudWatch  alarm bookstore-cover-processor-dlq-not-empty
              log group /aws/lambda/bookstore-cover-processor
  SQS         bookstore-cover-processor-dlq
  SNS         bookstore-cover-events            (and its subscriptions)
  IAM         role bookstore-cover-processor-role
  DynamoDB    CoverMetadata
  DynamoDB    UserBrowsingHistory               <-- browsing history, all of it
  S3          ${BUCKET}                         <-- every cover AND every old version

EOF

# DOES ANYTHING ACTUALLY EXIST HERE? Asked before deleting, because "nothing to delete" has two very
# different causes and the script cannot otherwise tell them apart:
#
#   already torn down          fine, exit quietly
#   pointed at the wrong region / account   NOT fine, and every delete below will succeed at nothing
#
# THE PROBES MUST BE REGIONAL. The first version of this guard used `s3api head-bucket`, and that is
# the bug that made the guard useless — **S3 bucket names are global**, so head-bucket succeeds from
# any region at all. Running with AWS_REGION=us-east-2 against resources in us-east-1 therefore found
# the bucket, decided the region was correct, and went on to delete it. Which it did.
#
# So: only DynamoDB, which is genuinely regional. If neither table is in this region, nothing that
# Step 9 built is, and the script must not touch anything global on its way to finding that out.
FOUND=0
for t in CoverMetadata UserBrowsingHistory; do
  aws dynamodb describe-table --table-name "$t" --region "${REGION}" >/dev/null 2>&1 \
    && FOUND=$((FOUND + 1))
done

# And before deleting the bucket, check where it actually lives. A global name plus a regional script
# is exactly the combination that deletes the right bucket from the wrong place.
BUCKET_REGION="$(aws s3api get-bucket-location --bucket "${BUCKET}" \
  --query 'LocationConstraint' --output text 2>/dev/null || echo absent)"
[ "${BUCKET_REGION}" = "None" ] && BUCKET_REGION=us-east-1   # the API's way of saying us-east-1

if [ "${BUCKET_REGION}" != "absent" ] && [ "${BUCKET_REGION}" != "${REGION}" ]; then
  echo "REFUSING: bucket ${BUCKET} is in ${BUCKET_REGION}, this run targets ${REGION}."
  echo "Re-run with AWS_REGION=${BUCKET_REGION} if that is what you meant. Nothing was deleted."
  exit 1
fi

if [ "${FOUND}" -eq 0 ]; then
  cat <<EOF
Nothing from Step 9 is present in ${ACCOUNT} / ${REGION}.

That means one of two things, and they are not the same:
  - it has already been torn down; or
  - this is the wrong region or the wrong account, and the resources are still running somewhere else.

Check before assuming the first:
  aws s3api list-buckets --query "Buckets[?starts_with(Name,'bookstore-covers')].Name"
  aws dynamodb list-tables --region us-east-1

Override with AWS_REGION=<region> if you deployed elsewhere. Nothing was deleted.
EOF
  exit 0
fi

echo "Found ${FOUND}/3 core resources in ${REGION}."
echo

if [ "${CONFIRM}" != "--yes" ]; then
  echo "Dry run. Re-run with --yes to delete."
  exit 0
fi

echo "Deleting..."

aws s3api put-bucket-notification-configuration --bucket "${BUCKET}" \
  --notification-configuration '{}' 2>/dev/null || true
aws lambda delete-function --function-name bookstore-cover-processor 2>/dev/null || true
aws logs delete-log-group --log-group-name /aws/lambda/bookstore-cover-processor 2>/dev/null || true
aws cloudwatch delete-alarms --alarm-names bookstore-cover-processor-dlq-not-empty 2>/dev/null || true

QURL="$(aws sqs get-queue-url --queue-name bookstore-cover-processor-dlq --query QueueUrl --output text 2>/dev/null || true)"
[ -n "${QURL}" ] && aws sqs delete-queue --queue-url "${QURL}" 2>/dev/null || true

TOPIC="arn:aws:sns:${REGION}:${ACCOUNT}:bookstore-cover-events"
aws sns delete-topic --topic-arn "${TOPIC}" 2>/dev/null || true

# A role cannot be deleted while policies are attached to it, and the error message names neither the
# role nor the policy clearly. Detach first, always.
aws iam delete-role-policy --role-name bookstore-cover-processor-role \
  --policy-name cover-processor-permissions 2>/dev/null || true
aws iam detach-role-policy --role-name bookstore-cover-processor-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole 2>/dev/null || true
aws iam delete-role --role-name bookstore-cover-processor-role 2>/dev/null || true

aws dynamodb delete-table --table-name CoverMetadata 2>/dev/null || true
aws dynamodb delete-table --table-name UserBrowsingHistory 2>/dev/null || true

# A VERSIONED bucket cannot be emptied with `s3 rm --recursive`: that deletes current versions and
# leaves every noncurrent version and every delete marker behind, and then `rb` fails with
# BucketNotEmpty on a bucket that looks empty. Every version has to go individually.
echo "Emptying ${BUCKET} (versions and delete markers)..."
aws s3api list-object-versions --bucket "${BUCKET}" \
  --query '{Objects: Versions[].{Key:Key,VersionId:VersionId}}' --output json 2>/dev/null \
  | python -c "
import json, subprocess, sys
data = json.load(sys.stdin) if sys.stdin.isatty() is False else {}
objs = (data or {}).get('Objects') or []
for i in range(0, len(objs), 900):
    batch = json.dumps({'Objects': objs[i:i+900], 'Quiet': True})
    subprocess.run(['aws','s3api','delete-objects','--bucket','${BUCKET}','--delete',batch], check=False)
print(f'  removed {len(objs)} versions')
" 2>/dev/null || true

aws s3api list-object-versions --bucket "${BUCKET}" \
  --query '{Objects: DeleteMarkers[].{Key:Key,VersionId:VersionId}}' --output json 2>/dev/null \
  | python -c "
import json, subprocess, sys
data = json.load(sys.stdin) if sys.stdin.isatty() is False else {}
objs = (data or {}).get('Objects') or []
for i in range(0, len(objs), 900):
    batch = json.dumps({'Objects': objs[i:i+900], 'Quiet': True})
    subprocess.run(['aws','s3api','delete-objects','--bucket','${BUCKET}','--delete',batch], check=False)
print(f'  removed {len(objs)} delete markers')
" 2>/dev/null || true

aws s3api delete-bucket --bucket "${BUCKET}" 2>/dev/null || true

echo
echo "Done. Verify nothing is left:"
echo "  aws dynamodb list-tables"
echo "  aws s3api list-buckets --query 'Buckets[].Name'"
echo "  aws lambda list-functions --query 'Functions[].FunctionName'"
