# Real-time vessel tracking

Position reports come in over HTTP, go through Kafka and end up in Redis as the
latest known position per vessel. MySQL keeps the confirmed port arrivals, so a
restart or a replay does not create a duplicate.

Work in progress.
