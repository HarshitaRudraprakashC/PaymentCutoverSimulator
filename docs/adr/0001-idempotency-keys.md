# ADR-0001: Idempotency keys

## The problem

Clients retry. After a timeout, after an error, sometimes twice at the same moment.

For a capture, "the same request arrived twice" must never mean "the money moved twice".

This matters even more during the migration: the old processor has no duplicate protection, so our API is the only thing standing between a retry and a second charge.

## The decision

Every write needs an `Idempotency-Key` header. Keys are stored per merchant.

```text
first time we see the key   →  save it as IN_PROGRESS, do the work, save the answer
same key, same request      →  return the saved answer
same key, different request →  422 (the client has a bug)
same key, still running     →  409, try again shortly
same key, worker crashed    →  take over, but ask the processor first (ADR-0002)
```

The key is saved **in the same transaction** as the payment change it protects. Two identical requests can never both get through.

A second layer: every processor call uses a fixed reference, `<paymentId>:capture`. The new processor rejects a repeated reference on its own.

## Why

- Retrying with the same key is always safe. That's what clients need.
- Checking the request body catches a client reusing a key by mistake, instead of silently ignoring it.
- A lease on `IN_PROGRESS` keys means a crash can't leave a key stuck forever.

## What it costs

- One more table and a bit more logic on every write.
- Deleting old keys (e.g. after 24 hours) isn't built.

## Options I didn't take

- **No keys, just the state machine.** It blocks double captures, but can't tell your retry from someone else's request, and ignores a changed body.
- **Keys in Redis.** Faster, but the key and the payment would live in different systems and couldn't be saved together.
