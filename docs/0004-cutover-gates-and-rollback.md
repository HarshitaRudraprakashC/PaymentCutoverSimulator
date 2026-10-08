# ADR-0004: Rollout gates and rollback

## The problem

Moving traffic to the new processor is the riskiest part of the migration. A bug in the new integration moves real money wrongly.

So we need to:
- expose it slowly,
- stop automatically when something looks wrong,
- back out instantly without leaving payments half-done.

## The decision

**1. Move merchants, not single payments.**

```text
bucket = hash(merchantId) % 100
bucket < rolloutPercent  →  NEW
otherwise                →  OLD
```

Raising the percentage only adds merchants. Nobody flips back and forth.

**2. A payment stays on its processor.** The processor is chosen at authorization and saved on the payment. Capture, status checks and settlement always go to the same one.

**3. Gates on the way up.** Steps are fixed: `0 → 5 → 25 → 50 → 100`. Moving up is refused with `409` unless:

```text
reconciliation ran in the last hour
0 unresolved problems on NEW
NEW capture failure rate < 1%
no stuck pending payments on NEW
```

**4. No gates on the way down.** Rollback, even straight to 0%, is always allowed. Payments already on NEW finish on NEW.

## Why

- Moving forward needs **evidence**, not hope. The migration run proves it: a planted bug on NEW is caught by reconciliation, and the gate refuses to go from 25% to 50%.
- Gates in code can't be skipped under deadline pressure. Checklists can.
- Rollback lowers risk, so it should never wait for anything.
- Sticky payments mean rollback never strands a payment: you can't capture on OLD something that was authorized on NEW.

## What it costs

- The gate is only as good as reconciliation. A problem it doesn't check for can slip through. That's why the naive control run exists: to prove the checks actually fire.
- With few merchants, or one very large one, "5% of merchants" can be far more than 5% of the money. A real system would weight cohorts by volume.
- Switching off the old processor completely (after a full settlement cycle at 100%) isn't built.

## Options I didn't take

- **Route each payment randomly.** Smooth percentages, but one merchant's money would come from two processors, making reconciliation messy.
- **Send every capture to both processors (shadow traffic).** A capture moves money, so this would charge twice.
- **A human checks a dashboard before each step.** Simpler, but easy to skip when under pressure.
