# Showing the conditions actually executed

## What: the problem in plain words, with a concrete example

“How many ships arrived today?” can mean distinct vessels or arrival events, different ports or a different time zone. A fluent answer can hide those choices. The copilot now shows a code-generated query basis and tables from the actual validated query before its short model summary, so an operator can see what was counted.

## Why: requirements and constraints that drove the design

The displayed conditions must come from executed arguments, not model prose. Port/vessel names may be ambiguous or contain instructions. Tenant identity cannot be model-selected. Counting distinct vessels across ports cannot be computed by summing group counts because one vessel can visit several ports.

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

Name resolution runs listFences/findAssets first. Generic queryEvents accepts fence IDs and vessel MMSIs only. Code validates metric (event_count or distinct_vessels), optional ENTER/EXIT, owned ID lists, a bounded interval and an allowed grouping (port, vessel, UTC day, type or none). Relative windows resolve against one server clock. The API compiles a validated EventQuerySpec into whitelisted SQL expressions and bound JDBC parameters.

The same spec creates an execution receipt containing canonical arguments and names loaded from owned database rows. Totals and groups run in a repeatable-read transaction; the full distinct total is independently computed, not summed from groups. The API returns at most 50 displayed groups and reports truncation. Every copilot RPC returns a Presentation rendered by the API ([ADR 044](../decisions/044-api-rendered-copilot-results.md), [Presentations.java:1](../../api/src/main/java/dev/fleet/api/internal/Presentations.java#L1)): the JSON the model reads, with an ISO-8601 UTC string beside every epoch value; the conditions (status shows selected vessel, event tools show scope/type/time/limits, nearby shows center/radius, fence/name lookup shows match scope, confirmed-state summaries show membership); result tables; and the time the replicated port data is current to.

The Python copilot copies those conditions and tables into the structured queryBasis, prepends them with the data-as-of time to the answer text, escapes them, and adds assumptions that depend on the operator's wording (UTC default, arrival meaning ENTER, chosen window, radius or row limit). Invalid, skipped and unavailable queries are not represented as successful executions. Descriptions/prompt require clarification when name resolution yields zero or multiple matches; a multiple vessel match stops queued dependent reads in code.

[EventQuerySpec.java:1](../../common/src/main/java/dev/fleet/common/EventQuerySpec.java#L1); [EventQuerySql.java:14](../../api/src/main/java/dev/fleet/api/copilot/EventQuerySql.java#L14); [CopilotStore.java:62](../../api/src/main/java/dev/fleet/api/copilot/CopilotStore.java#L62); [Presentations.java:1](../../api/src/main/java/dev/fleet/api/internal/Presentations.java#L1); [transparency.py:1](../../copilot/fleet_copilot/transparency.py#L1); [timerange.py:1](../../copilot/fleet_copilot/timerange.py#L1).

**Generic model-facing spec and receipt fields:**

| Field | Where the value comes from and how code uses it | Optional behavior |
|---|---|---|
| metric | Model selects event_count or distinct_vessels; validation chooses COUNT or COUNT DISTINCT | Required; no arbitrary expression. |
| eventType | Model selects ENTER/EXIT; code binds the predicate and renders arrival/departure meaning | Absent includes both. |
| ports | Fence IDs returned by owned name resolution; API validates each and binds the filter | Empty/absent includes all owned ports; names are not accepted here. |
| vessels | MMSIs returned by owned asset resolution; API validates and binds the filter | Empty/absent includes all owned vessels. |
| timeWindow | One allowed relative name interpreted against the turn clock | Use this or both from/to, not both representations. |
| from / to | ISO instants supplied by the model and parsed in code into inclusive bounds | Required together when no relative window is used. |
| groupBy | Model selects one allowed grouping; code chooses a whitelisted SQL fragment | Required; none gives an ungrouped result. |
| receipt.arguments | API canonical values after validation/defaults/clamping; renderer shows executed conditions | A successful data query without a receipt fails closed. No tenant field is displayed here. |
| receipt.ports / vessels | API loads names for validated IDs from scoped database rows | Empty for an unselected scope; never copied from model prose. |
| total / rows / truncated | SQL produces the full metric total and ordered capped groups; code renders values | Empty groups remain empty; truncation does not change the full total. |
| queryBasis | Receipt tool and canonical arguments, API-rendered conditions and tables, data-as-of time, copilot-added assumptions | Present as a structured response field even when evaluation capture is disabled; no tools gives an explicit empty basis. |

## Alternatives considered and trade-offs

Free text-to-SQL would expose an enormous unvalidated language surface and makes displayed intent hard to bind to execution. A small semantic layer supports explicit metrics/grouping without arbitrary joins. ID-only arguments prevent name strings from silently choosing an entity. Code-rendered facts reduce conversion/counting errors; model prose remains useful for a short explanation but is not trusted as the basis source.

## Failure modes and how each is handled

Foreign IDs, unsupported groupings, fractional MMSIs and invalid/missing/reversed/overlong bounds are rejected before SQL. Query values are parameters, not concatenated SQL. Names/tables are escaped and bounded so data cannot inject display Markdown. A missing execution receipt fails closed. Table truncation is disclosed separately from full totals. Multiple-match vessel resolution yields a question, not an arbitrary ID; port ambiguity also has prompt/description guidance, without claiming equally complete runtime enforcement.

## Problems encountered and how they were solved

Round-2 failure analysis supported aggregation, fleet state and name tools in a3514a6. c6abbc3 adds matching ISO UTC fields after number-to-time conversions failed. 2114095 adds validated semantic queries and receipts; 6af5dc3 renders basis and tables from them. b82722e adds separate evaluation tasks/checks. These last query-transparency changes were locally tested after round 3; retained model results cannot demonstrate their model behavior. Independent distinct totals address the double-counting design risk, not a recorded production counting incident.

## Evidence: tests by name, measured numbers with conditions

IngestIT.copilotReadsAreReadOnlyPresentedAndRefusedWhenTheReplicaLags checks the rendered conditions, model JSON, tables and data-as-of time from a real MySQL read. Python tests test_query_basis_and_tables_come_from_the_api_and_prose_is_only_the_summary and test_relative_windows_are_resolved_in_code_with_stated_assumptions cover assembly, unchanged ISO times and assumptions. EventQuerySqlTest and QueryReceiptTest cover SQL/arguments/owned names; IngestIT covers grouped SQL in MySQL. The separate eight-task query-transparency-v1 suite has not been run against a model.

## Known limits and what production would need

There is no guarantee the selected query matches an ambiguous natural-language intent: transparency makes the choice visible. Default detection uses conservative literal cues, not complete semantic intent parsing. Model summaries can still err. Selected IDs are capped at 50 each, range at 366 days; result tables are capped. These are local test/configuration facts, not a fresh model score. Frozen answer-checker rules and old task fixtures remain unchanged.

## Likely interview questions with short, precise answers

**Q: Who writes the query basis?**

A: Code using API execution receipts, not the model.

**Q: Where do displayed names come from?**

A: Tenant-owned database rows loaded after validating the selected IDs.

**Q: Why IDs only?**

A: Name resolution is a separate visible step and ambiguous names cannot silently choose a row.

**Q: What if two vessel names match?**

A: Ask for clarification and skip queued dependent reads.

**Q: What does the generic spec allow?**

A: Only approved event metrics, filters, windows and groupings.

**Q: Is SQL parameterized?**

A: Yes; only whitelisted SQL expressions vary and all values are bound.

**Q: Why not sum distinct groups?**

A: One vessel can be in multiple groups; the overall distinct total is computed independently.

**Q: Can a truncated table reduce the total?**

A: No; the total query is independent and table truncation is disclosed.

**Q: What are assumptions?**

A: Displayed system choices such as UTC interpretation, radius or a relative window.

**Q: Has it improved the model scores?**

A: Not measured: the feature has local tests and a separate unrun model suite.
