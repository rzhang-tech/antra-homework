#!/usr/bin/env bash
#
# Everything the cover-processing Lambda needs except the Lambda. Steps 9c and 9d.
#
#   ./cover-pipeline-infra.sh
#
# Creates, idempotently:
#   DynamoDB  CoverMetadata                    PK bookId
#   SNS       bookstore-cover-events           the admin notification topic
#   SQS       bookstore-cover-processor-dlq    where failed invocations go (9d)
#   IAM       bookstore-cover-processor-role   the Lambda's execution role
#
# Subscribing an email address is deliberately NOT done here - see the note at the end. Sending
# somebody a confirmation email is not something a provisioning script should do behind your back.

set -euo pipefail

REGION="$(aws configure get region)"
ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="bookstore-covers-${ACCOUNT: -6}"

TABLE="CoverMetadata"
TOPIC="bookstore-cover-events"
DLQ="bookstore-cover-processor-dlq"
ROLE="bookstore-cover-processor-role"

echo "Region ${REGION}, account ${ACCOUNT}"

# ---------------------------------------------------------------- DynamoDB
if aws dynamodb describe-table --table-name "${TABLE}" >/dev/null 2>&1; then
  echo "${TABLE} exists."
else
  echo "Creating ${TABLE}..."
  # PK bookId and NO sort key, which is the whole idempotency story in one line: there is exactly one
  # row per book, so a redelivered event or a re-upload updates it rather than adding a second. The
  # alternative - a synthetic id per processing run - would need something to notice duplicates
  # afterwards, which is the design D21 argues against.
  aws dynamodb create-table \
    --table-name "${TABLE}" \
    --attribute-definitions AttributeName=bookId,AttributeType=S \
    --key-schema AttributeName=bookId,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --tags Key=project,Value=bookstore-capstone Key=step,Value=9c \
    --no-cli-pager
  aws dynamodb wait table-exists --table-name "${TABLE}"
fi

# ---------------------------------------------------------------- SNS
TOPIC_ARN="$(aws sns create-topic --name "${TOPIC}" \
  --tags Key=project,Value=bookstore-capstone \
  --query TopicArn --output text)"
echo "Topic:  ${TOPIC_ARN}"

# SNS rather than SES, and it is a real choice. SES sends better email - templates, bounces,
# reputation - and a brand-new account is in the SES SANDBOX, where it can only send to addresses
# already verified in the console. SNS's email subscription needs one confirmation click and then
# works, and the thing being demonstrated is the event pipeline rather than the mail transport. A
# real product uses SES, and would need the sandbox lifted before it could email a customer at all.

# ---------------------------------------------------------------- SQS dead letter queue (9d)
DLQ_URL="$(aws sqs create-queue --queue-name "${DLQ}" \
  --attributes '{"MessageRetentionPeriod":"1209600"}' \
  --tags project=bookstore-capstone,step=9d \
  --query QueueUrl --output text)"
DLQ_ARN="$(aws sqs get-queue-attributes --queue-url "${DLQ_URL}" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)"
echo "DLQ:    ${DLQ_ARN}"

# 14 days of retention, the maximum. A dead letter queue whose messages expire before anyone looks
# is a dead letter queue that has quietly deleted the evidence - and the failures worth keeping are
# exactly the ones nobody noticed quickly.

# ---------------------------------------------------------------- IAM
if aws iam get-role --role-name "${ROLE}" >/dev/null 2>&1; then
  echo "Role ${ROLE} exists."
else
  echo "Creating ${ROLE}..."
  aws iam create-role --role-name "${ROLE}" \
    --assume-role-policy-document '{
      "Version": "2012-10-17",
      "Statement": [{
        "Effect": "Allow",
        "Principal": {"Service": "lambda.amazonaws.com"},
        "Action": "sts:AssumeRole"
      }]
    }' \
    --tags Key=project,Value=bookstore-capstone \
    --no-cli-pager

  # Logs. Without this the function runs and you cannot see anything it did, which during a first
  # Lambda deployment is indistinguishable from the function not running at all.
  aws iam attach-role-policy --role-name "${ROLE}" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
fi

# The inline policy is written out rather than using the AWS-managed FullAccess policies, and this is
# the one place in the project where least privilege is actually practised rather than discussed.
#
# The Lambda needs to: read the object that triggered it, write one DynamoDB table, publish to one
# topic, and send failures to one queue. Every resource below is named. AmazonS3FullAccess would
# have worked and would have given a function that processes images the ability to delete every
# bucket in the account.
#
# THE ListBucket STATEMENT IS NOT AN OVERSIGHT BEING CORRECTED - it buys a readable error message,
# and it was added after measuring what its absence costs.
#
# Without s3:ListBucket, S3 answers a GetObject for a key that does not exist with 403 AccessDenied
# rather than 404 NoSuchKey. That is deliberate on S3's part: a caller who cannot list the bucket
# must not be able to learn which keys exist by comparing 403 against 404. The cost is that a
# missing file and a broken policy produce the identical error, and whoever reads it goes and
# debugs IAM.
#
# Measured, by adding the permission two ways and invoking the function against a key that does
# not exist:
#
#   no ListBucket                      403 "not authorized to perform: s3:ListBucket"
#   ListBucket, prefix-conditioned     404 "The specified key does not exist"
#   ListBucket, unconditioned          404 "The specified key does not exist"
#
# The prefix condition is enough, so the honest error message costs nothing: the function still
# cannot enumerate anything outside covers/.
echo "Writing the least-privilege inline policy..."
aws iam put-role-policy --role-name "${ROLE}" --policy-name cover-processor-permissions \
  --policy-document "{
    \"Version\": \"2012-10-17\",
    \"Statement\": [
      {
        \"Sid\": \"ReadTheCoverThatTriggeredUs\",
        \"Effect\": \"Allow\",
        \"Action\": [\"s3:GetObject\", \"s3:GetObjectVersion\"],
        \"Resource\": \"arn:aws:s3:::${BUCKET}/covers/*\"
      },
      {
        \"Sid\": \"SoThatAMissingObjectIs404AndNot403\",
        \"Effect\": \"Allow\",
        \"Action\": \"s3:ListBucket\",
        \"Resource\": \"arn:aws:s3:::${BUCKET}\",
        \"Condition\": {\"StringLike\": {\"s3:prefix\": \"covers/*\"}}
      },
      {
        \"Sid\": \"WriteOneTable\",
        \"Effect\": \"Allow\",
        \"Action\": [\"dynamodb:PutItem\", \"dynamodb:UpdateItem\", \"dynamodb:GetItem\"],
        \"Resource\": \"arn:aws:dynamodb:${REGION}:${ACCOUNT}:table/${TABLE}\"
      },
      {
        \"Sid\": \"PublishToOneTopic\",
        \"Effect\": \"Allow\",
        \"Action\": \"sns:Publish\",
        \"Resource\": \"${TOPIC_ARN}\"
      },
      {
        \"Sid\": \"SendFailuresToTheDeadLetterQueue\",
        \"Effect\": \"Allow\",
        \"Action\": \"sqs:SendMessage\",
        \"Resource\": \"${DLQ_ARN}\"
      }
    ]
  }" --no-cli-pager

echo
echo "Done."
echo "  table     ${TABLE}"
echo "  topic     ${TOPIC_ARN}"
echo "  dlq       ${DLQ_ARN}"
echo "  role      arn:aws:iam::${ACCOUNT}:role/${ROLE}"
echo
echo "NOT done, on purpose - subscribing an email address sends somebody a confirmation mail,"
echo "which a provisioning script should not do without being asked. Run this yourself:"
echo
echo "  aws sns subscribe --topic-arn ${TOPIC_ARN} \\"
echo "      --protocol email --notification-endpoint YOUR@EMAIL"
echo
echo "then click the link in the message AWS sends. Until it is confirmed the subscription is"
echo "'PendingConfirmation' and publishing to the topic silently reaches nobody."
