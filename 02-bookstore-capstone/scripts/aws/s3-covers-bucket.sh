#!/usr/bin/env bash
#
# Creates the cover-image bucket. Step 9b.
#
# Idempotent, like every script in here: re-running reports and changes nothing.
#
#   ./s3-covers-bucket.sh
#
# Prints the bucket name at the end. That name goes into config-repo/book-service-dev.yml, because
# S3 bucket names are GLOBALLY unique across every AWS account on earth - "bookstore-covers" was
# taken by somebody in 2011 - so it cannot be a constant in the config repo the way a table name can.

set -euo pipefail

ACCOUNT="$(aws sts get-caller-identity --query Account --output text)"
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
BUCKET="bookstore-covers-${ACCOUNT: -6}"

echo "Region:  ${REGION}"
echo "Bucket:  ${BUCKET}"

if aws s3api head-bucket --bucket "${BUCKET}" 2>/dev/null; then
  echo "Bucket already exists - leaving it alone."
else
  echo "Creating bucket..."

  # us-east-1 is the one region where --create-bucket-configuration must be OMITTED. Passing
  # LocationConstraint=us-east-1 is an error, and passing nothing anywhere else creates the bucket in
  # us-east-1 regardless of the CLI's configured region. A genuine API wart worth knowing about.
  if [ "${REGION}" = "us-east-1" ]; then
    aws s3api create-bucket --bucket "${BUCKET}" --no-cli-pager
  else
    aws s3api create-bucket --bucket "${BUCKET}" \
      --create-bucket-configuration "LocationConstraint=${REGION}" --no-cli-pager
  fi

  aws s3api wait bucket-exists --bucket "${BUCKET}"
fi

# PRIVATE, and belt-and-braces about it.
#
# Public buckets are the single most common cause of AWS data exposure, and the reason is that the
# old defaults made "public" one careless ACL away. These four settings are now on by default for new
# buckets - set explicitly anyway, because a default is something that can change or be turned off,
# and this line is the one a reviewer looks for.
#
# Covers are not secret; the point is that the bucket is not a place where anything BECOMES public by
# accident. Step 9b serves images through presigned URLs instead, which keeps "who may read this"
# an answer this platform gives rather than one S3 gives.
echo "Blocking all public access..."
aws s3api put-public-access-block --bucket "${BUCKET}" \
  --public-access-block-configuration \
    "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" \
  --no-cli-pager

# Versioning. Costs nothing until an object is overwritten, and covers ARE overwritten - the object
# key is deterministic (covers/{bookId}) precisely so that re-uploading replaces rather than
# accumulates. Without versioning a wrong upload destroys the previous cover irreversibly.
echo "Enabling versioning..."
aws s3api put-bucket-versioning --bucket "${BUCKET}" \
  --versioning-configuration Status=Enabled --no-cli-pager

aws s3api put-bucket-tagging --bucket "${BUCKET}" \
  --tagging 'TagSet=[{Key=project,Value=bookstore-capstone},{Key=step,Value=9b}]' --no-cli-pager

echo
echo "Done."
aws s3api get-public-access-block --bucket "${BUCKET}" --output table
echo
echo "Put this in config-repo/book-service-dev.yml:"
echo "    app.aws.s3.covers-bucket: ${BUCKET}"
