# Resume visibility correction

An older vessel report may produce a durable event after a newer report has
already reached a client. Resume now uses the MySQL `created_at` timestamp
(TIMESTAMP(6), UTC epoch microseconds on the wire) and row ID, with a configurable
five-second look-back overlap. Pagination retains a fixed overlap anchor.

Only client-confirmed `seenEventIds` found in tenant-scoped history seed the new
connection's dedupe set. The list is capped at 200 valid lowercase SHA-256 IDs.
Other overlap rows remain eligible for replay; clients dedupe across connections.
The per-vessel/fence report-time guard is unchanged.

Verified 2026-10-08 at 17:17:42 America/New_York in the original Desktop checkout:
`mvn -B -ntp verify`: BUILD SUCCESS, 131 tests, no failures/errors/skips.
Tests include a MySQL transaction with a smaller ID committing after a larger
visible row, a later-created event carrying earlier report time after reconnect,
history/live dedupe, bounded/invalid confirmed IDs, and unchanged tenant routing.
