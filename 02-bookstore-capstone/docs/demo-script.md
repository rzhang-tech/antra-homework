# Demo script

Submission deliverable #2. Everything below is copy-pasteable **into one SSH session on the EC2 box**,
because `kubectl`, `curl` and `jq` are all there and the platform answers on `localhost:30080`. Two
windows in total: this terminal, and a browser for Grafana and the GitHub Actions page.

**Do not run the `curl` lines in PowerShell.** `curl` there is an alias for `Invoke-WebRequest` and
takes different arguments; the commands will fail in ways that look like the platform is broken.

## Before recording

On the laptop, open the monitoring ports to your current address:

```bash
./scripts/aws/allow-my-ip.sh i-0cf3ea62b8d46ee6d --monitor
```

Then connect, and set the two variables everything below uses:

```bash
ssh -i D:\project\java_Antra\file\personal-dev-00.pem ubuntu@<public-ip>
```

```bash
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
export API=http://localhost:30080
```

Sanity check before you press record — if this is not `200`, fix it off-camera:

```bash
curl -s -o /dev/null -w '%{http_code}\n' $API/api/books
```

---

## 1 · It is running on AWS  (~1.5 min)

```bash
kubectl get pods -n bookstore
```

> Fifteen pods on one t3.large: eight services, four separate PostgreSQL instances, a Kafka broker,
> Prometheus and Grafana. Four databases rather than one, because each service owns its storage
> outright — book-service cannot read the users table even by accident.

```bash
kubectl get svc -n bookstore | grep -E 'NAME|NodePort'
```

> One NodePort. Everything else is ClusterIP and unreachable from outside — not because a rule forbids
> it, but because nothing exposes it.

---

## 2 · The business flow  (~2 min)

```bash
U=demo$RANDOM
curl -s -X POST $API/api/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"email\":\"$U@example.com\",\"password\":\"Passw0rd!\"}" | jq
```

```bash
TOKEN=$(curl -s -X POST $API/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"Passw0rd!\"}" | jq -r .token)
echo "${TOKEN:0:60}..."
```

> A JWT. Every other service verifies this signature itself — nobody calls user-service to ask whether
> a token is genuine.

```bash
curl -s $API/api/books | jq '.content[] | {id, title, price, stock}'
```

Place an order — this is the request that cannot be served by one service:

```bash
ORDER=$(curl -s -X POST $API/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"items":[{"bookId":1,"quantity":1}]}')
echo $ORDER | jq
OID=$(echo $ORDER | jq -r .id)
```

> order-service just called book-service over HTTP for the price and stock, with a 3-second timeout and
> a circuit breaker in front of it. The order was written **before** the stock was reserved, so a crash
> in between leaves a row a recovery job can find.

```bash
curl -s -X POST $API/api/payments -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"orderId\":$OID}" | jq
curl -s $API/api/orders/$OID -H "Authorization: Bearer $TOKEN" | jq '{id, status, totalPrice}'
```

Now the part order-service knows nothing about:

```bash
kubectl logs -n bookstore -l app=notification-service --tail 20 | grep -E 'CONFIRMATION|RECEIPT'
kubectl logs -n bookstore -l app=analytics-service --tail 20 | grep TALLY
```

> A confirmation, a receipt and a sales tally — from two services order-service has never heard of. It
> published one event to Kafka and returned. Adding a third consumer tomorrow needs no change here.

---

## 3 · The security boundary  (~1 min)

```bash
curl -s -o /dev/null -w 'no token:        %{http_code}\n' $API/api/orders
curl -s -o /dev/null -w 'forged header:   %{http_code}\n' \
  -H 'X-Auth-Role: ADMIN' -H 'X-Auth-User-Id: 1' $API/api/orders
```

> The gateway **strips** inbound `X-Auth-*` headers unconditionally. The moment any service trusted one
> of those, a curl command would be a complete authentication bypass.

```bash
curl -s -m 3 -o /dev/null -w 'book-service directly: %{http_code}\n' http://localhost:8082/api/books \
  || echo 'book-service directly: connection refused'
kubectl exec -n bookstore deploy/order-service -- wget -qO- http://book-service:8082/api/books \
  | head -c 80; echo
```

> Refused from outside, fine from inside. And the services still verify every token themselves — a
> network boundary is not a security boundary until something makes it one.

---

## 4 · The AWS half  (~1.5 min)

```bash
for b in 1 3 2; do curl -s -o /dev/null $API/api/books/$b -H "Authorization: Bearer $TOKEN"; done
sleep 3
curl -s $API/api/books/me/history -H "Authorization: Bearer $TOKEN" | jq
```

> Newest first, out of DynamoDB. The sort key is an ISO-8601 timestamp because DynamoDB orders sort
> keys as **strings**, and ISO-8601 is the format whose lexicographic order is its chronological order.
> The writes are asynchronous, so a slow DynamoDB never slows down a catalogue read.

```bash
curl -s $API/api/books/2/cover -o /dev/null -w 'cover: %{http_code} -> S3 presigned\n'
```

> A 302 to a presigned S3 URL. Generating it makes no network call at all — it is an HMAC computed
> locally — so the service does microseconds of work and S3 serves the megabytes.

If you want to show the Lambda pipeline live, upload a cover as an admin and then:

```bash
aws dynamodb get-item --table-name CoverMetadata --key '{"bookId":{"S":"2"}}' --region us-east-1
```

> Width and height were **read out of the image by the Lambda**, not taken from the request.
> book-service returned 204 before the function was even invoked.

---

## 5 · Failure, handled  (~1.5 min)

```bash
kubectl scale deployment book-service -n bookstore --replicas=0
sleep 20
time curl -s -o /dev/null -w '%{http_code}\n' -X POST $API/api/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"items":[{"bookId":1,"quantity":1}]}'
```

Run it four or five times, then:

> The first few take about 750 ms and then it drops to about 85 ms. The circuit breaker opened, and
> after that no request leaves the process at all. **Failing fast is the fallback** — it does not
> invent a price, because charging a made-up price is worse than an error.

```bash
kubectl scale deployment book-service -n bookstore --replicas=1
```

Also worth showing while it is down — the pod that keeps running:

```bash
kubectl get pods -n bookstore -l app=order-service
```

> Still Ready, zero restarts. The liveness probe asks the process about itself, not about its
> dependencies. Pointed at plain `/actuator/health` it would restart every replica of every service
> for a problem no restart can fix.

---

## 6 · The pipeline — the strongest section  (~2.5 min)

Browser: **github.com/rzhang-tech/antra-homework/actions**

> Every push runs the whole suite on GitHub's runners, then builds eight images in parallel, then
> deploys. The image job `needs` the test job, so a failing test means no image exists.

```bash
kubectl get deploy -n bookstore -o jsonpath='{range .items[*]}{.metadata.name}{"  "}{.spec.template.spec.containers[0].image}{"\n"}{end}' | head -3
cd ~/antra-homework && git log --oneline -1
```

> The tag on the running image is the commit SHA. Nobody touched this server.

Then break a deploy on purpose:

```bash
kubectl set image deployment/user-service \
  user-service=ghcr.io/rzhang-tech/bookstore/user-service:v99-does-not-exist -n bookstore
sleep 25
kubectl get pods -n bookstore -l app=user-service
```

> New pod cannot pull. Old pod still Running.

```bash
curl -s -o /dev/null -w 'register during the failed deploy: %{http_code}\n' \
  -X POST $API/api/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"live$RANDOM\",\"email\":\"live@example.com\",\"password\":\"Passw0rd!\"}"
```

> **Not one request dropped.** A Deployment removes the old pod only once the new one passes its
> readiness probe, so a rollout that cannot start changes nothing.

```bash
kubectl rollout undo deployment/user-service -n bookstore
kubectl rollout status deployment/user-service -n bookstore
```

> Back to the previous ReplicaSet, which still names the previous commit SHA. **Rolling back is finding
> a tag, not rebuilding a commit** — which is why `:latest` is the one tag you cannot roll back to.

---

## 7 · Monitoring  (~1 min)

Browser: **http://\<public-ip\>:30300** (Grafana) or **:30090** (Prometheus).

```
histogram_quantile(0.99, sum by (application,le) (rate(http_server_requests_seconds_bucket[5m])))
```

> p99 from histogram buckets rather than per-instance percentiles — because **per-instance percentiles
> cannot be aggregated**. The p99 of three replicas is not the mean, the max, or any function of their
> three p99s, so with an autoscaler they are a number about one pod that nobody wants.

Prometheus → Alerts. Seven rules.

> Error rate, p99, Kafka consumer lag, connection-pool exhaustion, circuit breaker open, dead-letter
> depth at threshold **zero**, and a pod restart loop. What is deliberately **not** alerted on is CPU,
> memory and request rate — those are causes, and every unnecessary alert makes the necessary ones less
> likely to be read.

---

## 8 · Close  (~30 s)

> The thing I would fix first is a dual-write hole: order-service commits the order and then publishes
> the event, and if the broker is unreachable in between the order exists and nobody was ever told —
> no error, no retry, no trace. The answer is a transactional outbox: write the event into the order's
> own database in the same transaction, and let a poller publish it. It is in `docs/reflection.md`
> along with everything else still open, including two services that produce wrong numbers if you scale
> them, and why.

---

## If anything goes wrong on camera

```bash
kubectl get pods -n bookstore | grep -v Running     # what is unhealthy
kubectl rollout restart deployment/<name> -n bookstore
```

And the whole platform can be rebuilt from scratch in about three minutes:

```bash
cd ~/antra-homework/02-bookstore-capstone
IMAGE_REPO=ghcr.io/rzhang-tech IMAGE_TAG=$(git rev-parse HEAD) bash k8s/deploy.sh
```
