# config-repo

The configuration the [config-server](../config-server) serves. Plain YAML, no Java, no build.

## Which file answers a request

A service asks for `{application}/{profile}`, where `{application}` is its `spring.application.name` and
`{profile}` is its active profile. The server returns every matching file as a separate property source,
highest priority first:

```
GET http://localhost:8888/user-service/dev

  user-service-dev.yml     wins
  application-dev.yml      <- outranks the service's own file below
  user-service.yml
  application.yml          platform-wide default - loses to all of the above
```

**Profile beats specificity, and that is the trap.** The intuitive order — everything named
`user-service*` outranking everything named `application*` — is wrong. A key set in the shared
`application-dev.yml` overrides the same key in `user-service.yml`, and the service file simply appears
to be ignored, with no warning anywhere. `ConfigServerContractTest` pins the real order for exactly this
reason.

The rule that follows: to override something for one service, override it at the same profile level or
lower. `user-service-dev.yml` beats everything.

Nothing is textually merged. The client receives four property sources and applies Spring's ordinary
precedence rules, exactly as it would to four local files.

## What is here and what is not

| Lives here | Lives in the service's own jar |
|---|---|
| datasource URL, credentials | `spring.application.name` |
| ports | the address of this config server |
| the JWT signing key, issuer, expiry | Flyway migration **scripts** (`db/migration/*.sql`) |
| Feign timeouts, resilience thresholds | anything needed *before* config can be fetched |
| log levels, actuator exposure | |

The last row of the right-hand column is the rule that decides the rest. A service cannot fetch its
configuration until it knows its own name and where the config server is, so those two facts can never
come from the config server. Everything else can.

Migration *scripts* stay with the service that owns the schema. They are versioned artefacts that must
match the compiled entity classes; shipping `V3__add_column.sql` separately from the code that needs the
column is how a deployment half-applies itself.

## Before moving a value in here: check the tests

**Tests do not read the config server.** Every service's `application-test.yml` sets
`spring.cloud.config.enabled: false`, and the `configserver:` import is skipped under the `test` profile
— deliberately, so the suite needs no second process. The consequence is a rule with teeth:

> Moving a property into this directory **removes it from the test classpath**. If any test depends on
> it, that test breaks — and the failure never mentions configuration.

Do this before every move, from `bookstore-platform/`:

```bash
grep -rn "order-placed" --include="*.java" --include="*.yml" */src/test
```

**Search the leaf key, not the dotted path.** `app.kafka.topics.order-placed` finds nothing: YAML nests
it, so the string never appears anywhere. The leaf name matches both forms — the nesting in a
`application-test.yml` and the `${app.kafka.topics.order-placed}` inside a `@Value`. The first version
of this rule shipped with the dotted path and would have found none of the three failures below.

If it does, copy the value into that service's `src/test/resources/application-test.yml` in the same
change. Duplication is correct here: a test that asserts "the circuit opens on the sixth call" must own
the number it asserts on, or it silently changes meaning the moment operations tunes a threshold.

This is written down because it was learned three times in two steps, and the symptom was different
every time:

| what moved | how it failed | what it looked like |
|---|---|---|
| Resilience4j thresholds | `CatalogGatewayResilienceTest` could no longer open a circuit | a resilience bug |
| `auto-offset-reset` | one Kafka test passed, its neighbour timed out | a flaky test |
| `app.kafka.topics.*` | context failed to start | `PlaceholderResolutionException` |

None of the three says "the config server has this now". The grep above is two seconds and finds all of
them.

## Secrets

Nothing readable in here is a credential.

- **dev** — `app.jwt.secret` is a `{cipher}` value. The config server decrypts it before serving, using
  a key it reads from `ENCRYPT_KEY` and never writes down. Produce one with:

  ```bash
  curl -X POST localhost:8888/encrypt -H 'Content-Type: text/plain' --data-binary 'the secret'
  ```

- **prod** — `${JWT_SECRET}`, `${DB_PASSWORD}` and friends are passed through **unresolved**. The config
  server never sees the value; the client resolves it against its own environment. This is the mechanism
  that becomes a Kubernetes Secret in Step 10, with these files unchanged.

Encryption protects the repository — a Git history keeps every value forever, including after rotation.
Placeholders protect everything: the repository, the wire, and the config server's logs. Use
placeholders wherever something can populate an environment.

## Editing it

The `native` backend (the local default) reads these files from disk, so a change takes effect on the
next fetch — a service restart, or a `POST /actuator/refresh` (Step 6c).

The `git` backend (`--spring.profiles.active=git`, what production uses) serves **committed** content
only. An uncommitted edit is invisible to it. That is the feature: every production configuration change
is a commit with an author, a timestamp and a diff, and a bad value is reverted like any other line.
