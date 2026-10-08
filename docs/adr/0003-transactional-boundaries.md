# ADR-0003: No transaction open during a processor call

## The problem

A capture touches two systems: our database and the processor. No single transaction can cover both.

Where we start and end our transactions decides what a crash can leave behind.

## The decision

A capture is split into two short transactions, with the processor call **between** them:

```text
transaction 1
   +── save the idempotency key
   +── AUTHORIZED → CAPTURE_PENDING
COMMIT                                   ← our intent is now saved

call the processor                       ← no transaction open

transaction 2
   +── CAPTURED + ledger entries + outbox event
   +── idempotency key → COMPLETED
COMMIT
```

Rule: **save what you're about to do, then do it.**

## Why

What a crash leaves behind at each point:

```text
before transaction 1     nothing happened, client retries cleanly
after 1, before the call payment pending, processor has nothing → resolver re-sends
after the call, before 2 payment pending, processor captured   → resolver finishes it
after 2                  done, a retry just gets the saved answer
```

Every crash point can be recovered.

If the processor call were *inside* a transaction:
- a slow processor would hold database locks and connections, and could exhaust the pool;
- a rollback after a successful capture would erase our only record that we asked, while the money had moved.

The payment change, ledger entries and outbox event are always in the same transaction. A `CAPTURED` payment without its ledger entries, or without its event, can't exist.

## What it costs

Two transactions per capture instead of one. That doesn't matter at this scale, and it's standard practice at large scale.

## Options I didn't take

- **One transaction around everything.** Simpler code, but a timeout plus rollback loses the record while the money has moved.
- **Send the capture through a queue.** Fully asynchronous, but no safer: the same "save intent first" rule is what makes it safe. Kept synchronous so the flow is easy to follow.
