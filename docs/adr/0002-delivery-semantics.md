# ADR-0002: A timeout means "unknown"

## The problem

When a call to a processor times out, we can't know which of these happened:

```text
request lost on the way      →  nothing captured
captured, reply lost         →  money moved
```

The two processors also behave differently:

```text
NEW processor   same reference twice  →  one capture
OLD processor   same reference twice  →  two captures
```

During the migration we have to be safe against the weaker one.

## The decision

A timeout puts the payment in `CAPTURE_PENDING`: **we asked, and we don't know yet.**

Then, every recovery path (client retry, scheduled resolver, reconciliation) follows the same rule:

```text
ask the processor: "did you capture <paymentId>:capture?"
   |
   +── yes, right amount  →  CAPTURED, post to the ledger
   |
   +── never saw it       →  send the capture again, same reference
   |
   +── no answer          →  wait, ask again later
```

Finishing a payment is safe to repeat: the database allows only one `CAPTURE_PENDING → CAPTURED` change and one capture journal per payment.

## Why

```text
retry on timeout       →  double charge on the old processor
mark failed on timeout →  customer charged, our records say "failed"
ask first              →  one charge, correct records
```

Exactly-once delivery between two systems isn't possible. What we can build is: calls may happen more than once, but the **effect** happens once.

## What it costs

- Clients can get `202 CAPTURE_PENDING` and must retry or check back.
- For a short time, money has moved but our ledger doesn't show it yet. Reconciliation flags anything stuck for more than 15 minutes.
- **One narrow gap remains.** If the first request is delayed in the network and arrives at the old processor *after* we were told "never saw it", the customer could be charged twice. Real fixes: require duplicate protection from every processor, or wait longer before re-sending. Reconciliation still catches it as `DUPLICATE_AT_PROCESSOR`.

## Options I didn't take

- **Retry on timeout.** Double charges. This is the bug the project exists to show.
- **Mark failed on timeout.** Loses real charges.
- **A distributed transaction with the processor.** Processors don't offer one.
