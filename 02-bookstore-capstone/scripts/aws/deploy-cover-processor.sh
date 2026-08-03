#!/usr/bin/env bash
#
# Builds and deploys the cover-processing Lambda, wires S3 to it, and adds the 9d monitoring.
#
#   ./deploy-cover-processor.sh
#
# Run cover-pipeline-infra.sh first. Re-runnable: an existing function has its code and configuration
# updated rather than being recreated, which is what a deploy should do.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PLATFORM="${HERE}/../../bookstore-platform"

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

FUNCTION="bookstore-cover-processor"
ROLE_ARN="arn:aws:iam::${ACCOUNT}:role/bookstore-cover-processor-role"
TOPIC_ARN="arn:aws:sns:${REGION}:${ACCOUNT}:bookstore-cover-events"
DLQ_ARN="arn:aws:sqs:${REGION}:${ACCOUNT}:bookstore-cover-processor-dlq"
JAR="${PLATFORM}/cover-processor/target/cover-processor-0.0.1-SNAPSHOT.jar"

# The AWS CLI on Windows is a native binary and does not understand Git Bash's /d/... paths - it
# reports "No such file or directory" for a file that is plainly there. cygpath translates; on Linux
# and macOS there is nothing to translate and the path is used as-is.
if command -v cygpath >/dev/null 2>&1; then
  JAR_FOR_CLI="$(cygpath -w "${JAR}")"
else
  JAR_FOR_CLI="${JAR}"
fi

echo "Building..."
(cd "${PLATFORM}" && ./mvnw -q -pl cover-processor package)
echo "Jar: $(du -h "${JAR}" | cut -f1)"

if aws lambda get-function --function-name "${FUNCTION}" >/dev/null 2>&1; then
  echo "Updating code..."
  aws lambda update-function-code --function-name "${FUNCTION}" \
    --zip-file "fileb://${JAR_FOR_CLI}" --no-cli-pager --query 'LastModified' --output text
  aws lambda wait function-updated --function-name "${FUNCTION}"

  echo "Updating configuration..."
  aws lambda update-function-configuration --function-name "${FUNCTION}" \
    --environment "Variables={METADATA_TABLE=CoverMetadata,TOPIC_ARN=${TOPIC_ARN}}" \
    --dead-letter-config "TargetArn=${DLQ_ARN}" \
    --timeout 30 --memory-size 512 \
    --no-cli-pager --query 'LastModified' --output text
  aws lambda wait function-updated --function-name "${FUNCTION}"
else
  echo "Creating function..."

  # 512 MB rather than the 128 MB default, and it is not really about memory.
  #
  # Lambda allocates CPU in PROPORTION to memory, so a 128 MB function gets roughly a tenth of a
  # core - and a JVM cold start on a tenth of a core is measured in many seconds. Raising memory on a
  # Java Lambda usually makes it CHEAPER, because billing is memory x duration and duration falls
  # faster than memory rises. The counter-intuitive part is worth remembering: the default is a bad
  # default for the JVM specifically.
  #
  # 30s timeout because the work is one S3 read, one DynamoDB write and one publish. The default of
  # 3 seconds is not enough for a cold JVM, and the failure looks like a hang rather than a timeout.
  aws lambda create-function --function-name "${FUNCTION}" \
    --runtime java21 \
    --handler com.example.cover.CoverProcessor::handleRequest \
    --role "${ROLE_ARN}" \
    --zip-file "fileb://${JAR_FOR_CLI}" \
    --environment "Variables={METADATA_TABLE=CoverMetadata,TOPIC_ARN=${TOPIC_ARN}}" \
    --dead-letter-config "TargetArn=${DLQ_ARN}" \
    --timeout 30 --memory-size 512 \
    --tags project=bookstore-capstone,step=9c \
    --no-cli-pager --query 'FunctionArn' --output text

  aws lambda wait function-active --function-name "${FUNCTION}"
fi

FUNCTION_ARN="$(aws lambda get-function --function-name "${FUNCTION}" \
  --query 'Configuration.FunctionArn' --output text)"

# ---------------------------------------------------------------- S3 -> Lambda
# S3 invokes the function directly, so the FUNCTION must grant S3 permission to do it. This is the
# opposite direction from the execution role: that says what the function may do, this says who may
# call it. Forgetting it produces a bucket notification that AWS accepts and never fires - which
# looks exactly like a Lambda that is not being triggered for some mysterious reason.
if ! aws lambda get-policy --function-name "${FUNCTION}" 2>/dev/null | grep -q "s3-covers-invoke"; then
  echo "Allowing S3 to invoke the function..."
  aws lambda add-permission --function-name "${FUNCTION}" \
    --statement-id s3-covers-invoke \
    --action lambda:InvokeFunction \
    --principal s3.amazonaws.com \
    --source-arn "arn:aws:s3:::${BUCKET}" \
    --source-account "${ACCOUNT}" \
    --no-cli-pager --output text >/dev/null
fi

echo "Wiring ObjectCreated on covers/ ..."
# Prefix-filtered, so only the covers/ folder triggers this. A notification on the whole bucket would
# also fire for anything else stored here later, and the function would spend its dead letter queue
# rejecting objects it was never meant to see.
aws s3api put-bucket-notification-configuration --bucket "${BUCKET}" \
  --notification-configuration "{
    \"LambdaFunctionConfigurations\": [{
      \"Id\": \"cover-uploaded\",
      \"LambdaFunctionArn\": \"${FUNCTION_ARN}\",
      \"Events\": [\"s3:ObjectCreated:*\"],
      \"Filter\": {\"Key\": {\"FilterRules\": [{\"Name\": \"prefix\", \"Value\": \"covers/\"}]}}
    }]
  }" --no-cli-pager

# ---------------------------------------------------------------- 9d: monitoring
# A dead letter queue nobody watches is worse than none - it removes the SYMPTOM along with the
# failure, exactly as Step 7d's dead letter topic did. Before the DLQ, a failing invocation retried
# and was visible in the error metric; after it, the function reports success and the message sits in
# a queue nobody has opened.
echo "Alarming on DLQ depth..."
aws cloudwatch put-metric-alarm \
  --alarm-name "bookstore-cover-processor-dlq-not-empty" \
  --alarm-description "A cover failed processing and its event is sitting in the dead letter queue" \
  --namespace AWS/SQS \
  --metric-name ApproximateNumberOfMessagesVisible \
  --dimensions "Name=QueueName,Value=bookstore-cover-processor-dlq" \
  --statistic Maximum \
  --period 300 \
  --evaluation-periods 1 \
  --threshold 0 \
  --comparison-operator GreaterThanThreshold \
  --treat-missing-data notBreaching \
  --alarm-actions "${TOPIC_ARN}" \
  --no-cli-pager

# Threshold zero: ONE message in there is worth waking up for. The same reasoning as 7d's monitor -
# nothing consumes this queue, a human does, so its healthy depth is not "low", it is empty.
#
# Alarming to the same SNS topic the notifications use. Adequate here and wrong at any real scale: an
# operational alert and a business notification want different audiences and different urgency, and
# sharing a topic means whoever muted the cover emails also muted the alarm.

echo
echo "Done."
aws lambda get-function-configuration --function-name "${FUNCTION}" \
  --query '{Runtime:Runtime,Memory:MemorySize,Timeout:Timeout,DLQ:DeadLetterConfig.TargetArn}' \
  --output table
