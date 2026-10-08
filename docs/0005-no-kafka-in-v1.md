# ADR-0005: No Kafka in v1

**Status:** Accepted · **Date:** 2026-09-27

## Context

Most fintech backends use Kafka, and the original project idea included Kafka events, duplicate and out-of-order delivery, and a DLQ. The build budget is 6–10 hours, and the project's purpose is to demonstrate **safe processor migration**: payments correctness under partial failure while two processors with different guarantees are live at once.

## Decision

v1 has **no message broker**. Everything runs in one Spring Boot service with Postgres. The fake processors are in-process components with their own tables.

The failure modes Kafka would introduce are still covered, via other paths:

| Kafka failure mode | Where v1 covers the same idea |
|---|---|
| Duplicate delivery | Client retries with the same idempotency key (ADR-0001), concurrent duplicates (S4) |
| At-least-once consumers | Resolver and recon run the same idempotent finalization repeatedly (ADR-0002) |
| Lost/unknown outcome | Timeout-after-success and crash-after-success injection (S2, S3, S7) |
| Poison messages / DLQ | Out of scope; mismatches that can't be auto-resolved are parked for a human, which is the same idea |

## Consequences

- The whole system starts with `docker compose up` and one JVM, and every scenario runs in a single Testcontainers test suite. Reviewers can clone and verify in minutes.
- The project doesn't show Kafka skills. That is a deliberate trade: the correctness reasoning is the scarce proof, and Kafka experience can be shown elsewhere.
- **Stretch goal (v2):** add a transactional outbox (`outbox_events` written in the same transaction as the state change) and a relay that publishes `PaymentCaptured` / `PaymentSettled` to Kafka, with a consumer that proves idempotent handling of duplicated and reordered events. The design already writes intent before side effects (ADR-0003), so this is additive.

## Alternatives considered

- **Kafka between API and processor worker from day one.** More realistic, but it roughly doubles the build (broker setup, serialization, consumer groups, test harness) and the time goes into plumbing instead of the correctness scenarios. Rejected for v1.
