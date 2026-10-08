
Markdown View 
AA
Design and build guide
The README explains what the project does and why. This file is the build guide: the parts, the tables, the order to build them, and how the migration run works.
Build time: about 12.5 hours.

The parts
PaymentController        the API: authorize, capture, get
IdempotencyService       saves idempotency keys, replays answers
PaymentService           the payment state machine
ProcessorRouter          picks OLD or NEW, sticks to it
OldProcessor             fake processor, no duplicate protection
NewProcessor             fake processor, rejects duplicate references
FailureInjector          makes processors fail on purpose
PendingResolver          finishes payments stuck in CAPTURE_PENDING
LedgerService            writes balanced ledger entries
SettlementJob            reads processor reports, marks payments SETTLED
ReconciliationJob        compares our records with the processors'
MigrationController      rollout percentage + gates
OutboxRelay              sends outbox events to Kafka
MerchantBalanceConsumer  reads Kafka, keeps balances per merchant
MigrationRunner          runs the full migration and prints the log
The two fake processors live in the same app, each with its own table. That's what lets us break them on command.

Failure modes you can switch on
Processors:
  FAIL_BEFORE             fails without capturing
  TIMEOUT_AFTER_SUCCESS   captures, then the reply is "lost"
  CRASH_AFTER_SUCCESS     captures, then our service "crashes" before saving
  LATENCY_MS              slow responses
  UNDER_CAPTURE           captures 1 cent less (the planted bug)

Kafka:
  DUPLICATE_PUBLISH       every event sent twice
  CRASH_BEFORE_MARK       relay sends, then "crashes" before marking it sent
  REPUBLISH_STALE         an old event is sent again later
  POISON                  an event that can never be processed

Database tables
payments (
  id, merchant_id, amount_minor, currency,
  processor,            -- OLD or NEW, set once at authorization
  status,               -- AUTHORIZED, CAPTURE_PENDING, CAPTURED, SETTLED, FAILED
  version,              -- goes up on every change
  capture_attempts, pending_since, created_at, updated_at
)

idempotency_keys (
  merchant_id, key,     -- primary key together
  request_hash,         -- to spot the same key with a different request
  state,                -- IN_PROGRESS or COMPLETED
  locked_until,         -- so a crashed request can be taken over
  payment_id, response_code, response_body, created_at
)

ledger_entries (
  id, journal_id, payment_id,
  account,              -- e.g. PROCESSOR_RECEIVABLE:NEW, MERCHANT_PAYABLE
  direction,            -- DEBIT or CREDIT
  amount_minor, created_at
)

old_processor_txns / new_processor_txns (
  id, reference,        -- "<paymentId>:capture"
  type, amount_minor, status, created_at
)
-- only new_processor_txns has UNIQUE (reference, type)

rollout_config    (new_percent)
recon_runs        (id, started_at, finished_at, payments_checked, mismatches)
recon_mismatches  (id, run_id, payment_id, processor, type, expected, actual,
                   resolved, resolved_by, resolution_note, resolved_at)

outbox_events     (id, event_id, payment_id, payment_version, type, payload,
                   created_at, published_at)
processed_events  (event_id)
merchant_balances (merchant_id, captured_minor, settled_minor)
payment_projection_versions (payment_id, applied_version)
Rules the database enforces:
amounts must be > 0
one idempotency key per merchant
one capture journal per payment
state changes use: UPDATE ... WHERE status = <expected> AND version = <expected>

How a capture runs
transaction 1
   +── save idempotency key (IN_PROGRESS)
   +── AUTHORIZED → CAPTURE_PENDING
COMMIT

call the processor   (no transaction open)

transaction 2
   +── CAPTURED + ledger entries + outbox event
   |     or back to AUTHORIZED if it definitely failed
   |     or stay CAPTURE_PENDING if we don't know
   +── idempotency key → COMPLETED
COMMIT
If anything crashes between these steps, the payment is left in CAPTURE_PENDING and the resolver picks it up.

The migration run
MigrationRunner drives the whole migration through the real API, in batches of 1,000 payments with 2% timeouts:
1. 0 → 5%      send a batch, settle, reconcile          gate passes
2. 5 → 25%     send a batch                              gate passes
3. 25%         switch on UNDER_CAPTURE, send a batch     reconciliation finds mismatches
4. 25 → 50%    ask to move up                            BLOCKED (409)
5. 25%         switch the bug off, operator resolves     0 open problems
6. 25 → 50%    ask again, send a batch                   gate passes
7. 50%         authorize a batch, then roll back to 0%   rollback allowed
8. 0%          capture that batch                        all finish on NEW
9. 0 → 100%    step back up, one batch per step          all gates pass
At the end it prints: payments, failures injected, duplicate charges (must be 0), lost charges (must be 0), ledger balanced, and Kafka drift (must be 0).
Two notes:
The "reconciliation ran in the last hour" rule uses a fake clock here, so the run doesn't wait an hour.
Resolving a problem only records who resolved it and why. It never changes money.

Build order
Step
What to build
Hours
1
Spring Boot app, Docker Compose (Postgres + Kafka), Flyway, first Testcontainers test
1.0
2
Payment state machine and ledger
1.5
3
Idempotency keys
1.0
4
Fake processors, failure injector, router, rollout setting
1.5
5
Pending resolver
1.0
6
Settlement, reconciliation, rollout gates
1.5
7
Outbox, Kafka relay, consumer, dead-letter queue
2.5
8
Migration runner
1.0
9
Failure tests, safe vs naive run, README numbers
1.5

Total
12.5
Short on time? Keep the migration run and Kafka. Skip tests S4, S5, S6, S10 and S14. That saves about 2 hours.
