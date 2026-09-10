# Transactions Service

An in-memory RESTful service that stores transactions and answers questions about them.
Transactions carry an `amount` and a `type`, and may link to a parent, forming trees; the service
returns the identifiers of a given type and the total of everything transitively linked to a given
transaction.

Solution to the Mendel Java Code Challenge (`docs/Java_Code_Challenge.pdf`).

- **Java 21**, **Spring Boot 4.1.1**, Maven (wrapper committed)
- **No SQL, no database** — the store is a set of concurrent in-memory indexes
- **98 tests**: domain, store, service, controller slice, full-stack integration, HTTP end-to-end

---

## Quick start

```bash
./mvnw test                   # run the whole suite
./mvnw spring-boot:run        # serve on :8080
docker compose up --build     # same, in a container
```

Reproducing the example from the challenge, verbatim:

```bash
curl -X PUT localhost:8080/transactions/10 -H 'Content-Type: application/json' \
  -d '{"amount":5000,"type":"cars"}'                          # 201 {"status":"ok"}
curl -X PUT localhost:8080/transactions/11 -H 'Content-Type: application/json' \
  -d '{"amount":10000,"type":"shopping","parent_id":10}'      # 201 {"status":"ok"}
curl -X PUT localhost:8080/transactions/12 -H 'Content-Type: application/json' \
  -d '{"amount":5000,"type":"shopping","parent_id":11}'       # 201 {"status":"ok"}

curl localhost:8080/transactions/types/cars                   # [10]
curl localhost:8080/transactions/sum/10                       # {"sum":20000.0}
curl localhost:8080/transactions/sum/11                       # {"sum":15000.0}
```

---

## API

### `PUT /transactions/{transaction_id}`

Stores a transaction under a client-chosen identifier.

```json
{ "amount": 5000.0, "type": "cars", "parent_id": 10 }
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `amount` | double | yes | Must be greater than zero, and finite |
| `type` | string | yes | Must not be blank; matched exactly, case included |
| `parent_id` | long | no | Must reference an existing transaction |

| Status | When |
|---|---|
| `201 Created` | The identifier was free. Carries `Location: /transactions/{id}` |
| `200 OK` | The identifier was in use; the transaction was replaced |
| `400 Bad Request` | Malformed JSON, missing `amount`, an `amount` that is not positive, blank `type`, non-numeric identifier |
| `405 Method Not Allowed` | A verb the resource does not support, such as `POST` |
| `409 Conflict` | The parent link would make the transaction its own ancestor |
| `412 Precondition Failed` | `If-None-Match: *` was sent and the identifier is already in use |
| `415 Unsupported Media Type` | The body is not JSON |
| `422 Unprocessable Content` | `parent_id` references a transaction that does not exist |

Both success cases answer with the body the specification prescribes: `{"status":"ok"}`.

**Amounts must be positive.** Anything at or below zero is refused before it reaches the store:

```bash
curl -X PUT localhost:8080/transactions/1 -H 'Content-Type: application/json' \
  -d '{"amount":0.01,"type":"cars"}'      # 201  the smallest amount that is accepted

curl -X PUT localhost:8080/transactions/1 -H 'Content-Type: application/json' \
  -d '{"amount":0,"type":"cars"}'         # 400  amount must be greater than zero
```

| Sent | Answer | `type` |
|---|---|---|
| `5000`, `0.01` | `201` / `200` — stored | — |
| `0`, `-0.01`, `-5000.5` | `400` `amount must be greater than zero` | `validation-error` |
| `null`, or the field absent | `400` `amount is required` | `validation-error` |
| `1e400` | `400` `amount must be a finite number, but was Infinity` | `validation-error` |
| `NaN`, `"abc"` | `400` `the request body is not valid JSON` | `malformed-request` |

The last two rows are the ones worth noticing. `NaN` is not legal JSON, so it never reaches
validation at all — the parser refuses it and it surfaces as a malformed body. `1e400` *is* legal
JSON, overflows silently to `Infinity` when parsed as a `double`, and is caught by the finiteness
check in the `Transaction` constructor. That check is not defensive dead code: this is the request
that reaches it.

A rejected write stores nothing: the transaction it addressed stays absent, and `GET /sum` on that
identifier still answers `404`.

**Create without replacing.** A client that must not overwrite an existing transaction says so with
a conditional request rather than a different endpoint:

```bash
curl -X PUT localhost:8080/transactions/10 -H 'If-None-Match: *' \
  -H 'Content-Type: application/json' -d '{"amount":99,"type":"food"}'   # 412 if 10 exists
```

Any other `If-None-Match` value is an entity-tag list. This service issues no ETags, so nothing can
match one, the precondition passes, and the write proceeds as a normal PUT.

### `GET /transactions/types/{type}`

```json
[10, 11, 12]
```

Identifiers of every transaction of that exact type, ascending. An unknown type is **not** an
error: it answers `200` with `[]`.

### `GET /transactions/sum/{transaction_id}`

```json
{ "sum": 20000.0 }
```

The total of the transaction itself plus everything transitively linked to it **through
`parent_id`** — that is, its whole subtree of descendants. Ancestors are not included, which is why
`sum/10` is `20000` while `sum/11` is `15000` in the example above. `404` if the transaction does
not exist.

### Errors

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details, served as
`application/problem+json`:

```json
{
  "type": "urn:mendel:transactions:parent-not-found",
  "title": "Parent transaction not found",
  "status": 422,
  "detail": "parent_id 99 does not exist",
  "instance": "/transactions/13"
}
```

`type` is the stable identifier to branch on; `detail` carries what varies per request, and
`instance` names the request that failed.

---

## Architecture

Ports and adapters. The point is that storage is an implementation detail behind an interface, so
replacing the in-memory store touches no service code.

Three layers, one package each, named so the boundary is obvious at a glance:

```
com.mendel.transactions
├── api/                                     layer 1 — HTTP, and nothing else
│   ├── TransactionController                maps requests onto the service
│   ├── ApiExceptionHandler                  maps failures onto status codes
│   └── dto/                                 TransactionRequest, StatusResponse, SumResponse
├── service/                                 layer 2 — the rules
│   ├── TransactionCommandService            port: the write side
│   ├── TransactionQueryService              port: the read side
│   └── DefaultTransactionService            parent validation, cycles, traversal
├── infrastructure/                          layer 3 — storage
│   └── InMemoryTransactionRepository        the concurrent indexes
└── domain/                                  the core the three layers share, framework-free
    ├── Transaction                          record(id, amount, type, parentId)
    ├── TransactionRepository                port: what storage must provide
    ├── SaveResult                           CREATED | REPLACED
    └── exception/                           TransactionNotFound, ParentNotFound,
                                             CircularReference, TransactionAlreadyExists
```

Dependencies point inward only: `api` knows `service`, `service` knows the `domain` ports, and
`infrastructure` implements a port without anything importing it back. `domain` imports nothing of
ours at all.

How the SOLID principles actually show up here, rather than as a checklist:

- **Dependency inversion** — `DefaultTransactionService` depends on the `TransactionRepository`
  port, never on the map-backed class. The direction of the arrow is what keeps the domain
  framework-free: nothing in `domain/` or `service/` imports Spring except the `@Service`
  stereotype.
- **Interface segregation** — the service is split into a command port and a query port. A caller
  that only reads does not depend on, or get to invoke, the write rules. One class implements both,
  which is a deployment detail, not a contract.
- **Single responsibility** — the controller maps HTTP shapes and nothing else; the advice maps
  failures to status codes; the store keeps indexes consistent; the service owns the rules. The
  invariants that belong to a value alone (non-blank type, finite amount) live in the record's own
  constructor, so an invalid `Transaction` cannot be constructed at all.
- **Open/closed** — adding a rule means adding an exception type and one handler method; adding a
  storage backend means one new class implementing the port.

---

## Design decisions

Everything below is a decision the challenge left open. Each is enforced by a test.

### What "transitively linked" means

The challenge asks for *"el monto total involucrado para todas las transacciónes vinculadas a una
transacción en particular"*, and `sum` is *"la suma de todas las transacciones que estan
transitivamente conectadas por su parent_id"*. Read literally, "connected" could mean the whole
connected component: follow parent links in **both** directions and every transaction in the same
tree is connected to every other, which would make `sum/10`, `sum/11` and `sum/12` all return the
same 20000.

The worked example settles it. `sum/10` is 20000 but `sum/11` is 15000, so the traversal goes
**downward only**: a transaction plus its descendants, its own subtree. Ancestors and siblings are
excluded. That is the reading the service implements, and the example is in the suite verbatim as
an executable check on it.

### PUT replaces, and says which happened

The specification calls `transaction_id` "identificador de una nueva transacción" and shows
`{"status":"ok"}`, but says nothing about re-using an identifier. Two readings were possible:
reject re-use as a conflict, or follow PUT's defined semantics.

We follow [RFC 9110 §9.3.4](https://www.rfc-editor.org/rfc/rfc9110#section-9.3.4): PUT *replaces*
the state of the target resource, and a client choosing the URI is exactly what PUT is for. An
unused identifier is created and answered `201` with a `Location` header; an identifier in use is
replaced and answered `200`. Both carry the prescribed body.

Rejecting re-use with `409` would have been simpler — it makes the store append-only, so every new
transaction is a leaf and cycles become impossible by construction. We chose not to buy that
simplicity by bending the verb, because it would also take the choice away from the client.

Instead the choice is the client's, expressed the standard way
([RFC 9110 §13.1.2](https://www.rfc-editor.org/rfc/rfc9110#section-13.1.2)): sending
`If-None-Match: *` means "create, never replace", and the service answers `412 Precondition Failed`
if the identifier is taken. The check and the write happen inside the same synchronized step, so two
concurrent conditional creates cannot both find the identifier free.

The one deviation from the specification's literal examples is the `201` on create where it prints
a bare `{"status":"ok"}`. The body is unchanged; only the status code is more specific.

### Yes, a PUT may change `parent_id`

This follows directly from PUT replacing rather than patching, and it is worth stating outright
because it is the reason cycle detection exists at all.

```bash
# 10 <- 11 <- 12, and a separate root 20
curl localhost:8080/transactions/sum/10        # {"sum":20000.0}

# move 11 (and everything under it) from 10 to 20, changing its type in the same write
curl -X PUT localhost:8080/transactions/11 -H 'Content-Type: application/json' \
  -d '{"amount":10000,"type":"cars","parent_id":20}'                         # 200

curl localhost:8080/transactions/sum/10        # {"sum":5000.0}
curl localhost:8080/transactions/sum/20        # {"sum":15001.0}
curl localhost:8080/transactions/types/cars    # [10,11,20]
```

Note that 12 was never mentioned in that request, but it hangs off 11 and so travels with it: links
are stored on the child, so moving a transaction moves its whole subtree, and both sums change to
match. The type index is re-maintained in the same write.

**The sharp edge:** because PUT carries the *complete* new state, omitting `parent_id` means "this
transaction has no parent", not "leave its parent alone". A client that re-sends a transaction
without the field will silently detach it, and its subtree with it. That is correct PUT semantics
and both cases are pinned by tests, but it is the behaviour most likely to surprise. Partial updates
are what `PATCH` is for, and this service deliberately does not offer one — there is no half-update
in the specification, and inventing one would add a second way to write with different rules.

### A missing parent is 422, not 404

`404` on `PUT /transactions/11` would say *the target URI* does not exist — but that resource is
precisely what the request is creating, so the code would contradict the request. A body that
references a non-existent parent is a well-formed request failing a *referential* rule, which is
what [`422 Unprocessable Content`](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.21) means.

Rejecting the write also guarantees every `parent_id` resolves, so the store never holds a dangling
link and the traversal is total.

### Cycles are a conflict, so 409

Because replacing a transaction may re-parent it, a cycle is genuinely reachable: store `10 → 11 →
12`, then re-`PUT` 10 with `parent_id: 12`. That request conflicts with current state, which is
`409 Conflict`. Self-parenting is the degenerate case of the same rule.

The check walks **up** from the proposed parent looking for the identifier, which costs the depth
of the chain, rather than walking down the whole subtree, which would cost its size.

This rule is what keeps the parent links a forest, and a forest is what makes the sum terminate.

### An unknown type is not an error

`GET /transactions/types/unknown` answers `200 []`. A type is a label transactions carry, not an
entity that can be absent — there is no resource missing. Only `GET /sum/{id}`, which addresses a
specific transaction, can produce `404`.

### Sum traverses on read, and iteratively

The store keeps a `parent → children` index, so summing walks the subtree directly instead of
scanning every transaction. Writes stay O(1) and reads cost the size of the subtree.

The alternative is caching a running total on each ancestor at write time, making reads O(1) and
writes O(depth). It was rejected because cached totals are state that can go stale, and every
re-parent would have to move a subtree's total between two chains — a much easier thing to get
subtly wrong, for a read path that is already fast. Under a read-heavy production load with deep
trees that trade would be worth revisiting; at this scale it is not.

The traversal is **iterative**, not recursive: a chain can be far deeper than the stack allows to
recurse, and a 100,000-deep chain is in the suite to prove it. It also carries a `visited` set, so
even a malformed store cannot make it loop forever.

### Only a replacement can close a cycle

The cycle check walks up from the proposed parent, which costs the depth of the chain. Doing that on
**every** write makes appending to a chain O(depth) and building one O(n²) — measurably so: a
100,000-node chain did not finish building in 400 seconds.

But a transaction that is not in the store yet has no descendants, so nothing can be a descendant of
it, so a new identifier cannot close a cycle at all. The walk only runs when the identifier is
already stored, which is exactly the re-parenting case that can. Appending is now O(1) and the same
chain builds and sums in under a second.

`DeepChainTest` holds this with timeouts rather than a comment, because a quadratic append passes
every correctness assertion and simply takes forever.

### Storage: concurrent maps, per-key atomicity

```java
Map<Long, Transaction>        byId
Map<String, NavigableSet<Long>> idsByType          // ConcurrentSkipListSet values
Map<Long, NavigableSet<Long>>   childIdsByParent   // ConcurrentSkipListSet values
```

Both queries are a lookup rather than a scan. The nested sets are `ConcurrentSkipListSet`: thread
safe *and* naturally sorted, so results come out in ascending order with no sorting on read and no
`synchronized` block anywhere. Insertion order was considered and dropped — under concurrent writes
it is not well defined, while ascending order is deterministic and reproducible.

A write does its whole index maintenance inside `byId.compute(...)`. `ConcurrentHashMap` holds that
key's bin for the duration of the mapping function, so "drop the previous transaction from its old
type and parent entries, then add the new one" is atomic *for that identifier*, while writes to
different identifiers never contend.

#### Why concurrent collections and not a lock

The store was first written the other way, with a `ReentrantReadWriteLock` guarding three plain
`HashMap`s, and then changed. The reasoning for the change:

- **A lock would protect against the wrong thing.** Writes are *already* serialized upstream:
  `DefaultTransactionService.save` is `synchronized`, because validating a parent link and storing
  it must be one step. Only one writer is ever in the store. So a write lock guards against a
  second writer that cannot exist, and the contention that actually matters is readers against that
  one writer.
- **A read-write lock makes every reader wait for the in-flight write.** `GET /sum` and
  `GET /types` are the operations a service like this serves constantly, and a subtree traversal is
  not instantaneous. Under the lock, each of those blocks whenever a PUT is mid-flight, and each
  PUT waits for readers to drain. With `ConcurrentHashMap`, readers never block and never take a
  lock at all.
- **The atomicity actually needed is per-identifier, not global.** The only compound operation is
  "move one identifier between index entries", and `byId.compute` scopes exactly that — the map
  holds that one key's bin for the mapping function. A global lock supplies far more mutual
  exclusion than the invariant requires, and charges every reader for it.
- **It keeps the option open.** If the service's global `synchronized` were ever narrowed to
  per-subtree locking, the store already supports concurrent writers to different identifiers with
  no change. A global lock in the store would have to be dismantled first.

The cost is real and worth naming: a lock could have given a whole `sum` traversal one consistent
snapshot, and the concurrent maps cannot. We judged that not worth blocking every read for — see
immediately below.

**The trade-off, stated plainly:** this gives per-key atomicity, not a transactional snapshot across
all three maps. A `sum` traversal running concurrently with writes may observe a tree that changed
underneath it. That is standard for an in-memory service of this kind and far cheaper than
serializing every read behind a global lock. Reads return `List.copyOf` snapshots, so a caller can
neither mutate an index nor trip over a concurrent modification mid-iteration.

`DefaultTransactionService.save` is `synchronized` for a separate reason: validating a parent link
and storing it must be one step, or two concurrent re-parents could each pass validation and close
a cycle between them. It is the only write path into the store, so serializing it is enough.

### Smaller decisions

- **`amount` is a boxed `Double` with `@NotNull`.** A primitive `double` would silently accept a
  missing field as `0.0` — which, now that zero is itself invalid, would turn a missing field into a
  confusing "must be greater than zero" instead of "amount is required".
- **The amount rule is enforced twice, deliberately.** `@Positive` on the request DTO turns it into
  a clean `400` naming the field, and the `Transaction` record refuses the same value in its
  constructor. The second is not redundant: it is what makes the invariant true of the type rather
  than of one entry point, so no future caller can construct an invalid transaction by bypassing the
  web layer. The record checks finiteness *before* sign, since `NaN` fails every comparison and
  would otherwise slip past `amount <= 0`.
- **`parent_id` is mapped with an explicit `@JsonProperty`**, not a global snake_case strategy, so
  one field's naming does not change serialization everywhere.
- **`amount` must be strictly greater than zero.** Negative, zero, `NaN` and infinity are all
  rejected with `400`. This forecloses modelling an outflow as a negative inflow, so a refund is its
  own transaction of a refund `type` rather than a sign flip; a service that later needs signed
  ledger entries would relax this rule rather than work around it.
- **`deleteAll()` is on the port.** It is a legitimate repository operation and the seam that lets
  integration tests reset a store that otherwise lives as long as the process.
- **`SaveResult` exists so the adapter can answer accurately.** The store is the only component
  that knows whether an identifier was free; returning `CREATED` or `REPLACED` is what lets the
  controller choose `201` or `200` without asking a second question and racing the answer.
- **The write side has two methods, not a boolean.** `save` replaces and `create` refuses to; a
  `save(transaction, createOnly)` flag would have hidden two behaviours behind one name.

---

## Testing

The suite is layered, and each layer exists for something the others cannot check.

| Layer | What it pins down |
|---|---|
| `TransactionTest` | Value invariants: a `Transaction` cannot be constructed invalid |
| `InMemoryTransactionRepositoryTest` | Index consistency across replacement, detached reads, concurrent writes |
| `DefaultTransactionServiceTest` | The rules, against a mocked port: parent validation, cycles, traversal, a 10,000-deep chain |
| `DeepChainTest` | The service on the real store at 100,000 nodes: appending stays linear, summing does not recurse |
| `TransactionControllerTest` | Controller slice with a mocked service: which failure becomes which status |
| `TransactionApiIntegrationTest` | Full context, real store: the whole HTTP contract |
| `TransactionHttpEndToEndTest` | A real server on a random port: the challenge example over the wire, and 8,000 concurrent PUTs from 8 clients |

```bash
./mvnw test
```

The service was built test-first. The commit history is the record: the suite lands red in one
commit, then each layer goes green in its own, and every commit after the first is green.

A note on `RestTestClient`: Spring Boot 4 moved `TestRestTemplate` behind a module this project does
not depend on, so the end-to-end test uses `RestTestClient`, which is Boot 4's own replacement and
already on the test classpath.

---

## Running in Docker

```bash
docker compose up --build       # :8080
```

Multi-stage: dependencies resolve in their own layer so editing sources does not re-download them,
and the runtime stage carries a JRE only, running as an unprivileged user. The healthcheck hits a
real endpoint rather than pulling in Actuator for it.

The store is in memory, so the container is stateless and disposable: restarting it is the supported
way to clear every transaction.
