# ADR-0005: Kafka through an outbox

## The problem

Other parts of the business need to know when a payment is captured or settled: notifications, balances, analytics. They get this through Kafka.

Two things can go wrong.

**1. Saving and publishing are two separate steps.**

```text
database commit succeeds
        |
        X crash
Kafka publish never happens   →  event lost
```

Or the other way round: publish first, then the commit fails, and we've announced something that never happened.

**2. Kafka can deliver an event more than once, or out of order.**

## The decision

**Outbox.** The event is written to an `outbox_events` table **in the same transaction** as the payment change:

```text
one transaction
   +── payment → CAPTURED
   +── ledger entries
   +── outbox event
COMMIT
```

The event exists if and only if the change was saved.

**Relay.** A background job picks up unsent outbox rows, sends them to Kafka (keyed by payment ID), and marks them sent. If it crashes after sending but before marking, it sends again. That's expected.

**Consumer.** The merchant balance consumer handles each event in one database transaction:

```text
seen this event ID before?          →  skip
older than what we already applied? →  skip
otherwise                           →  update the merchant's balance
```

It tells Kafka "done" only after that transaction commits.

**Broken events.** After 3 failed attempts, the event goes to a dead-letter queue and the consumer moves on.

**Check.** Reconciliation compares each merchant's Kafka-built balance with the ledger. A difference is flagged as `PROJECTION_DRIFT`, so a lost or broken event can't go unnoticed.

## Why

- No lost events and no false events, without a distributed transaction.
- Duplicates and old events are harmless.
- The ledger stays the source of truth. Kafka builds views from it.
- Kafka carries **facts that already happened**, never commands that move money.

## What it costs

- Every consumer must handle duplicates. This project shows how with one consumer.
- Events arrive a second or so after the change, which is fine for balances and notifications.
- Kafka adds a container to run locally and about 2.5 hours to the build.

## Options I didn't take

- **Publish to Kafka right after the commit.** Loses events on a crash.
- **Debezium (reads changes straight from the database log).** Common in production, but needs more infrastructure to run locally.
- **Kafka's exactly-once feature.** It only works inside Kafka. Our consumer writes to Postgres, so it still needs duplicate checks.
- **Send the capture command itself through Kafka.** No safer, and harder to follow (see ADR-0003).
