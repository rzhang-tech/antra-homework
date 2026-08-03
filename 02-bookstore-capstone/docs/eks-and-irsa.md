# Design note — this platform on EKS, and IRSA

The Step 10 challenge item. The manifests in [k8s/](../k8s) run on kind and on k3s; this is what would
change on EKS, what would not, and why the one that matters is IRSA.

**Not built.** EKS costs $0.10/hour for the control plane — about $73/month before a single node — and
this capstone deploys to one t3.large running k3s. What follows is a design, and it says so.

---

## What does not change

The manifests. Deployments, StatefulSets, Services, ConfigMap, Secret, probes, HPA — all of it is
portable Kubernetes API, and that portability is most of the argument for writing manifests rather than
`docker run` commands. Concretely, `kubectl apply -f k8s/` works unchanged on EKS.

## What changes, in four places

### 1. Images come from a registry

```
bookstore/user-service:latest  +  imagePullPolicy: Never
  ->  <account>.dkr.ecr.<region>.amazonaws.com/bookstore/user-service:<git-sha>
```

`Never` exists only because kind and k3s have images pushed into their own containerd. A real cluster
has many nodes, any of which may schedule the pod, so the image has to come from somewhere all of them
can reach. The node's instance role needs `AmazonEC2ContainerRegistryReadOnly`; no imagePullSecret is
required, which is one genuine convenience of ECR over Docker Hub.

The tag change matters more than the registry. `:latest` is the one tag that cannot be rolled back to,
because it means something different tomorrow. Step 11 tags by commit SHA, and that is what makes
`kubectl rollout undo` meaningful.

### 2. Storage: `standard` becomes `gp3`

The four PostgreSQL `volumeClaimTemplates` name no StorageClass, so they take the cluster default —
kind's `local-path` provisioner today, and on EKS whatever the EBS CSI driver installs. Worth being
explicit about two consequences:

- **An EBS volume lives in one Availability Zone.** A pod bound to a PVC can only ever be scheduled in
  that AZ. For a single-replica StatefulSet that is fine and is a fact worth knowing before an AZ
  outage teaches it.
- **`ReadWriteOnce` means one node, not one pod.** It is the accessMode already in use and it is the
  right one; it is also why the StatefulSet-not-Deployment argument in `10-postgres.yaml` still holds.

And the honest answer for a real deployment is **RDS**, which D26 already argues: managed backups,
point-in-time recovery, and a failover story none of this has.

### 3. Exposure: NodePort becomes an ALB

```yaml
type: NodePort            ->    type: LoadBalancer
nodePort: 30080                 # plus annotations for the AWS Load Balancer Controller
```

On EKS a `LoadBalancer` Service provisions a real NLB or ALB. That is the one place where the same
manifest field means something materially different per platform: on k3s it binds the node's own IP
through the bundled ServiceLB, and on kind it stays `<pending>` forever because there is no provider to
ask.

Still no Ingress, for the reason `50-api-gateway.yaml` gives: api-gateway already **is** this
platform's edge, and an ingress controller in front of it would be a second front door with its own
routing, its own CORS and its own way to get authentication wrong.

### 4. The HPA needs something underneath it

`60-autoscaling.yaml` works on any cluster with metrics-server — which k3s bundles and kind does not.
But on a fixed set of nodes an HPA can only scale until the nodes are full, after which it produces
`Pending` pods and no improvement. EKS is where that stops being true, with **Karpenter** (or the
Cluster Autoscaler) provisioning nodes in response to unschedulable pods. **Pod autoscaling without
node autoscaling is bounded by the machine**, which is exactly the situation on the t3.large.

---

## IRSA, which is the part worth the most

### The problem, stated precisely

book-service talks to S3 and DynamoDB (Step 9). Today its credentials arrive like this:

```yaml
- name: AWS_ACCESS_KEY_ID
  valueFrom:
    secretKeyRef: {name: bookstore-secrets, key: AWS_ACCESS_KEY_ID}
```

That is an IAM **user**'s long-lived access key, sitting in a Secret, which is base64 in etcd rather
than encryption. It has every weakness a long-lived credential has:

- it does not expire, so a leak is permanent until somebody notices and rotates it;
- rotating it is a manual act nobody schedules;
- anyone who can `kubectl get secret` in this namespace has it, and so does anyone who can exec into
  the pod, read a heap dump, or see it in a crash log;
- it is the *same* credential in every environment unless somebody remembers to make it different.

Encrypting etcd or moving to an external secret store improves the storage. **IRSA removes the thing
being stored.**

### How it works

IAM Roles for Service Accounts. The cluster gets an OIDC identity provider, and IAM is told to trust
tokens it issues:

1. EKS publishes an OIDC discovery document for the cluster; you register it as an IAM identity
   provider, once.
2. A Kubernetes ServiceAccount is annotated with a role ARN.
3. Pods using that ServiceAccount get a **projected service-account token** mounted at a path in the
   filesystem — short-lived, audience-scoped, and rotated by the kubelet.
4. The AWS SDK's default credential chain notices `AWS_WEB_IDENTITY_TOKEN_FILE` and `AWS_ROLE_ARN`,
   calls `sts:AssumeRoleWithWebIdentity` with that token, and receives **temporary** credentials.
5. The role's trust policy names the exact namespace and ServiceAccount, so no other pod can assume it.

Nothing in the application changes. The SDK clients in `S3CoverStore` and `BrowsingHistoryRepository`
are built with the default provider chain already, which is the reason this is a deployment change and
not a code change.

### What it would look like here

```yaml
# The role, created once with a trust policy naming this exact namespace + ServiceAccount:
#   "system:serviceaccount:bookstore:book-service"
apiVersion: v1
kind: ServiceAccount
metadata:
  name: book-service
  namespace: bookstore
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::013141018733:role/bookstore-book-service
---
# and in the Deployment's pod spec:
      serviceAccountName: book-service
```

The two `secretKeyRef` blocks and both keys in `bookstore-secrets` are then **deleted**. That deletion
is the whole point: the improvement is not a better hiding place, it is that there is no longer a
long-lived credential to hide.

The policy attached to that role is the one already written by hand in
[`scripts/aws/cover-pipeline-infra.sh`](../scripts/aws) for the Lambda's execution role — named
resources, `s3:GetObject` on `covers/*` and the DynamoDB tables, and nothing else. That script is
already the project's one worked example of least privilege, and IRSA is what lets a *pod* have the
same thing.

### The equivalent outside EKS

The mechanism is not AWS-specific. GKE has Workload Identity and AKS has Workload Identity too, both
the same shape: a Kubernetes ServiceAccount is federated to a cloud identity, and the pod exchanges a
projected token for short-lived credentials.

What has no equivalent is the k3s box this actually deploys to. **EC2 instance profiles are the closest
thing**, and they are strictly worse in one specific way worth being able to name: an instance profile
grants its permissions to *every process on the node*, so book-service's S3 access would also be
notification-service's, and anything that achieves execution anywhere on that host inherits it. IRSA's
contribution is per-pod granularity, and a single-node cluster is precisely where you do not get it.

---

## Summary

| | this project | on EKS |
|---|---|---|
| images | `bookstore/x:latest`, `imagePullPolicy: Never` | ECR, tagged by commit SHA |
| storage | kind `local-path` / k3s local | EBS via CSI — or RDS, which is the real answer |
| exposure | `NodePort` 30080 | `LoadBalancer` → NLB/ALB |
| node scaling | none; the HPA is bounded by one machine | Karpenter provisions nodes |
| AWS credentials | long-lived IAM user key in a Secret | **IRSA — no stored credential at all** |
| control plane | k3s, ~500 MB on the app's own node | managed across three AZs, $73/month |

The row that matters is the credential row. The others are configuration; that one is a class of
vulnerability removed.
