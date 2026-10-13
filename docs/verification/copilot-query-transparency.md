# Copilot query transparency

An operator asks about vessel locations or port arrivals in ordinary language. The assistant first resolves names to company-owned identifiers, then asks the API to run a bounded data query. The API records the conditions it actually validated and executed. Copilot code uses that record to show the operator the selected vessels, ports, event meaning, time interval and limits, followed by data tables. The model supplies a short summary; it does not generate the query-basis section or its tables.

## Response and execution sequence

1. The HTTP request selects the company on the server, as before. Tool schemas do not expose a tenant parameter.
2. `listFences` lists owned active port areas, and `findAssets` resolves an exact vessel name. Tool descriptions and the system prompt require clarification for zero or multiple name matches. For vessel lookup, code also ends the turn with a clarification and skips subsequently queued dependent tools when the result is not one unique match.
3. The model supplies IDs to a data tool. Names supplied in the model's prose or arguments cannot become display labels for those IDs. The API reads display names from its tenant-scoped database.
4. API validation rejects unknown/foreign IDs, invalid values and unsupported query combinations. The generic query produces an immutable canonical specification: identifier lists are deduplicated and sorted. That same object supplies both JDBC parameters and the execution receipt.
5. Every successful data-tool response includes a typed `QueryReceipt`. It records the tool name, canonical arguments without the tenant, and database names for explicitly selected IDs. Limits in a receipt reflect effective clamping, rather than the model's requested value. Ignored fields are excluded. An older API returning no receipt fails closed; no unverified basis is fabricated.
6. Each turn collects only receipts of successful executions. Invalid, unavailable, unknown, skipped and tool-cap-rejected calls do not become successful query entries. A local time-resolution tool records its exact resolved interval in code. A single request clock supplies the prompt, relative windows and evaluation evidence.
7. Every normal reply returns `queryBasis`, including an empty array for a turn without successful queries. The answer begins with `Query basis`, conditions, explicit assumptions and result tables, then `Summary`. Outage, clarification and tool-limit replies use this same path. Transport errors remain HTTP errors, rather than successful copilot replies.

Each `queryBasis` entry has:

| Field | Value and origin | Use |
|---|---|---|
| `tool` | Successful tool name from its execution receipt | Identify the actual operation |
| `arguments` | Canonical validated values, serialized as strings; list values are JSON arrays | Machine-readable conditions after normalization/defaults |
| `text` | Business-language renderer using those values and database names | Display the actual selected scope, interval, metric and limits |
| `assumptions` | Code-generated conventions and interpretations | Make UTC, ENTER meaning, relative-date interpretation, implicit radius and list limits visible |
| `results` | Code-built tables from the successful typed response's presentation | Display counts and returned records without model arithmetic |

Assumption detection is deliberately conservative: code checks literal UTC/ISO-Z mentions and numeric distance units in the current user message, rather than asking the model whether a default was explicit. Relative-window interpretations and list limits are always displayed. This does not prove that the model understood the user's intent; it makes the executed interpretation reviewable. A user's intended local day can differ from the displayed UTC day.

Result tables retain at most 50 rows, 32 columns and 512 characters per cell. API lists/group rows are also capped at 50; the existing model-result byte cap remains in force. Truncated groups/details are labeled, while aggregate totals are computed over the full interval. Empty lists show no matching rows; missing latest positions remain unavailable, not invented. Database names are untrusted display data: Markdown/HTML characters are escaped in the answer.

## Generic event query

The new `queryEvents` tool accepts one `spec` object. It cannot accept SQL, company IDs or display names.

| Spec field | Meaning and validation |
|---|---|
| `metric` | Required `event_count` (number of confirmed events) or `distinct_vessels` (unique MMSIs in the selected events) |
| `eventType` | Optional `ENTER` or `EXIT`; absent means both. Arrival means a confirmed ENTER, not a raw position. |
| `ports` | Optional array of at most 50 positive fence IDs; every ID must belong to the company. Empty/absent means all matching owned ports. |
| `vessels` | Optional array of at most 50 positive MMSIs; every ID must belong to the company. Empty/absent means all matching owned vessels. |
| `timeWindow` | One of `today`, `yesterday`, `last24h`, `last7days`, `thisWeek`; supply this or both explicit bounds, never both. |
| `from`, `to` | ISO-8601 instants resolved to UTC epoch milliseconds by code; inclusive bounds, nonnegative and at most 366 days apart. |
| `groupBy` | Required `port`, `vessel`, `day`, `type` or `none`. Day groups are UTC calendar days. All listed metrics/groupings are supported; other expressions fail validation. |

`today` runs from UTC midnight through the request time. `yesterday` is the prior full UTC day ending at 23:59:59.999. Rolling `last24h`/`last7days` end one millisecond before the request time, preserving the existing time-window contract. `thisWeek` runs from Monday UTC midnight through the request time. Relative windows use the configured fixed clock in offline evaluation and the current request time otherwise.

SQL uses a whitelist of count/group expressions; tenant, time bounds, event type and all IDs are JDBC parameters. Group names come from tenant-scoped joins, not input strings. Totals and bounded groups execute in one read-only repeatable-read transaction. UTC day grouping uses arithmetic from the Unix epoch, avoiding MySQL session timezone conversion. A distinct-vessel total is computed separately: summing distinct counts across ports or days would double-count a vessel that appears in more than one group.

Example tool arguments:

```json
{"spec":{"metric":"event_count","eventType":"ENTER","ports":[1],"timeWindow":"yesterday","groupBy":"none"}}
```

With the fixed clock `2024-01-08T12:00:00Z`, code displays `Los Angeles (fence 1)`, arrivals `(ENTER)`, and the inclusive interval `2024-01-07T00:00:00Z .. 2024-01-07T23:59:59.999Z`. The count is read from the API result and placed in a table. The original aggregate/list tools remain available for compatibility.

## Versioned evaluation

`copilot-eval/query-transparency-v1.json` is a new eight-task suite, generated from the unchanged fixed fixture by `query_tasks.py`. It covers different wording, event versus distinct-vessel counts, port/vessel/day grouping, calendar versus rolling windows, a named vessel, and Chinese. Reference totals and group counts come from fixture records in code.

`query_basis.py` independently checks successful executed calls against canonical basis arguments, displayed bounds/metric/grouping/event type, tenant-owned fixture names, and the presence of structured results/assumptions. It requires evaluation mode to inspect actual calls. Without that evidence it reports an unverifiable result rather than trusting model prose. It does not import or modify the frozen answer checker.

For a future explicitly authorized model run, `run_query_suite.py` runs only this new suite against an existing configured copilot endpoint, saves replies and reference facts, and applies the independent transparency check. It requires `--allow-model-calls` and refuses to overwrite an existing result file. It was not executed against a model for this change.

The reviewed task versions, fixture, ground truth, checkers and retained model results are unchanged. The two untracked round-3 review files are also unchanged.

## Local verification — 2026-10-10

| Check | Measured result |
|---|---|
| `mvn -B -ntp verify` with Homebrew OpenJDK 21.0.12.1 | BUILD SUCCESS; 163 tests, 0 failures/errors/skips |
| Offline evaluator unit tests | 89 passed, including six new transparency tests; no model calls |
| Bench/deployment tooling unit tests | 30 passed; no benchmarks executed |
| Public-safety script tests | 6 passed |
| Public-safety check before each commit | PASS |

Coverage includes all ten tool renderers; canonical argument/SQL parameter agreement; tenant-foreign port and vessel rejection before SQL; missing/reversed/overlong bounds; unsupported grouping/metric/type; nested unknown fields and fractional MMSIs; all SQL groupings against local MySQL through in-process gRPC; full totals beyond the 50-event detail cap; server-loaded names; multiple/zero vessel matches; skipped dependent reads; code-generated counts/tables; implicit-radius and UTC assumptions; HTTP `queryBasis` with evaluation disabled; and versioned transparency checks with an injected fake transport.

The existing container integration tests ran locally, including Kafka, MySQL and Redis. No AWS commands or live model calls were made. An initial new unit-test fixture omitted the newly required explicit epoch-zero bound; that fixture was corrected and ownership-check invocation assertions added before the successful full verification.

Frozen file SHA-256 values (unchanged from the starting commit):

| File | SHA-256 |
|---|---|
| `graders.py` | `9b7de1ac3e15c32cc97f853f51713ea90148e6ae168ec70266558fc9762d9d41` |
| `questions-aws.json` | `1a2e9cd9f4d48cf488ceb857c8b807fe374e29cce26d44db2786480a186162ff` |
| `questions-aws-v2.json` | `0ab6ea1b3df7c38d4f4dd7fbcc87487599712675702b90166e6bf17bb368bb3a` |
| `fixture.json` | `ae998bb78173e8c3e56a627798c665ed5ee5ef6db4d236b1a38b4c84e2417c1c` |

The original and v2 ground truth, regrader, retained evaluation runner and timestamp audit also remain byte-for-byte unchanged. These hashes document preservation, not evaluation success on new model runs.
