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
REGION="$(aws configure get region)"
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
