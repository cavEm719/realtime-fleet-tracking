The first scale driver was deliberately stopped after three completed one-processor,
throughput-only trials. It did not have observer clients, so it could not satisfy the
requested processor pipeline-latency comparison. Those raw trials are retained in
`scale-processors-1/`; they are not compared with the corrected quota-free
`scale-processors-probe-{1,2}/` trials, which include 100 per-asset observer clients.
The driver log therefore ends with an intentional SIGTERM (-15), not a failed workload.

One extra exploratory two-processor setup also started while the driver was being
replaced. Its Compose API/copilot startup returned exit code 1 before any replay;
`scale-processors-2/trial-1/status.json` retains that failed setup. No throughput or
latency is attributed to it. The corrected observer trials ran to completion after
health checks and the partition-assignment wait.
