# Validation results — 28 October 2026

The initial two-application paper workflow was verified using synthetic market data and temporary databases.

| Check | Result |
|---|---|
| Python regressions | 13 passed |
| Java regressions and integration checks | 53 passed, zero failures/errors/skips in current Surefire reports |
| Browser-code regressions (Node) | 3 passed |
| New and existing JavaScript syntax | Passed |
| Full Spring Boot startup | Passed on a temporary loopback port |
| Versioned Java-to-Python HTTP | Passed with a temporary API token and SQLite storage |
| Runnable Java packaging | Passed after tests |

Verified behavior includes raw provider provenance preservation, compact report lists, saved-report rendering, fragmented SSE and Unicode decoding, completed-candle indicators, session VWAP, news mapping/deduplication, historical news cutoff, valid paper proposals, rejected unverified data, quote timing after slow fetches, request replay/conflict handling, and prevention of late events reopening a closed position.

Java checks cover instrument resolution, actual broker timestamp handling, shared read limits and reserved risk capacity, concurrent duplicate intents, stale/expired proposals, independent loss limits, kill-switch exits without AI, unresolved quote incidents, outbox/inbox delivery, separate administrator authorization, and verified-calendar gates.

The full application test applied the PostgreSQL-style migrations to an in-memory H2 database, served paper-workspace.html over HTTP, rejected unauthenticated risk requests, and committed a kill-switch update through the application's JPA transaction manager.

Database text mappings were exercised in PostgreSQL-mode and Oracle-mode H2. The required Flyway PostgreSQL plugin was also checked. These checks do not replace migration testing on your actual PostgreSQL 18.x or Oracle installation.

The Java build produced:

    backend/target/groww-ai-options-trader-0.1.0-SNAPSHOT.jar

The Python service, Java HTTP clients and temporary Spring server used test data only. No authenticated Groww account calls or live orders were sent. No migrations were applied to your configured databases. No production service was restarted.

UI checks cover code behavior, syntax and HTTP serving; they do not represent a complete manual browser acceptance session. News feed entitlements, current instrument/history availability, actual exchange session data, native database connectivity and statistical strategy performance still require validation in the configured environment.

See PAPER_PLATFORM_API.md for reproducible commands and setup, and MASTER_PROMPT_REVIEW.md for implemented scope and outstanding production work.