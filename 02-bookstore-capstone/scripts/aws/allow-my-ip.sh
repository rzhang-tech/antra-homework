#!/usr/bin/env bash
#
# Point this instance's security group at wherever you are right now.
#
#   ./allow-my-ip.sh i-0123456789abcdef0            SSH + the platform
#   ./allow-my-ip.sh i-0123456789abcdef0 --monitor  ...and Prometheus + Grafana
#
# Written for a laptop that moves between a home connection and a campus network. Re-run it after the
# address changes; it revokes whatever it previously allowed on these ports and grants the current
# address, so running it from three places over a week leaves one rule per port rather than three.
#
# WHY BOTHER, when :22 only accepts keys anyway.
#
# It is not really about SSH. The instance holds a Kubernetes Secret containing a long-lived IAM access
# key (docs/eks-and-irsa.md is the argument for why that is the platform's weakest secret), and
# port 30080 is an API gateway with NO RATE LIMITING - Step 8 lists that under "what got worse" and it
# is still true. An unauthenticated, unthrottled endpoint on a box holding a real credential is worth
# more care than a demo suggests.
#
# What this is NOT: security. It is one layer, and a thin one - anyone sharing your campus NAT gets the
# same address. The things actually protecting this platform are the JWT check at the edge, the rules
# inside each service, and key-only SSH. This just removes it from everybody's scanner.

set -euo pipefail

INSTANCE="${1:-}"
[ -z "${INSTANCE}" ] && { echo "usage: $0 <instance-id> [--monitor]"; exit 1; }
REGION="${AWS_REGION:-${AWS_DEFAULT_REGION:-us-east-1}}"

# 22 to get in, 30080 for the API, 30081 for the frontend. 30081 was missing from the first version of
# this script because the frontend did not exist yet — which is the ordinary way a helper script goes
# quietly out of date: it keeps working, and it stops covering the thing added after it.
PORTS=(22 30080 30081)
[ "${2:-}" = "--monitor" ] && PORTS+=(30090 30300)

# checkip is AWS's own, so this needs no third-party service and returns the address AWS will see.
MY_IP="$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')"
[ -z "${MY_IP}" ] && { echo "could not determine public IP"; exit 1; }
CIDR="${MY_IP}/32"

SG="$(aws ec2 describe-instances --instance-ids "${INSTANCE}" --region "${REGION}" \
        --query 'Reservations[0].Instances[0].SecurityGroups[0].GroupId' --output text)"

echo "instance ${INSTANCE}  sg ${SG}  region ${REGION}"
echo "your address: ${CIDR}"
echo

for PORT in "${PORTS[@]}"; do
  # Revoke every existing range on this port first, including 0.0.0.0/0. Adding without removing is how
  # a security group ends up with six stale home addresses and the "anywhere" rule nobody noticed was
  # still there - and the stale ones are invisible, because the group keeps working.
  EXISTING="$(aws ec2 describe-security-groups --group-ids "${SG}" --region "${REGION}" \
      --query "SecurityGroups[0].IpPermissions[?FromPort==\`${PORT}\`].IpRanges[].CidrIp" \
      --output text 2>/dev/null || true)"

  for OLD in ${EXISTING}; do
    [ "${OLD}" = "${CIDR}" ] && continue
    aws ec2 revoke-security-group-ingress --group-id "${SG}" --region "${REGION}" \
        --protocol tcp --port "${PORT}" --cidr "${OLD}" >/dev/null
    echo "  ${PORT}  revoked ${OLD}"
  done

  if echo "${EXISTING}" | tr '\t' '\n' | grep -qx "${CIDR}"; then
    echo "  ${PORT}  already allows ${CIDR}"
  else
    aws ec2 authorize-security-group-ingress --group-id "${SG}" --region "${REGION}" \
        --protocol tcp --port "${PORT}" --cidr "${CIDR}" >/dev/null
    echo "  ${PORT}  allowed ${CIDR}"
  fi
done

echo
aws ec2 describe-security-groups --group-ids "${SG}" --region "${REGION}" \
  --query 'SecurityGroups[0].IpPermissions[].[FromPort,IpRanges[].CidrIp]' --output text
