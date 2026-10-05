# Bounded fleet questions behind a service boundary

## What: the problem in plain words, with a concrete example

An operator asks "how many vessels arrived at Los Angeles yesterday?" and cannot write SQL. The copilot lets the model choose among fixed read-only tools; the API executes the query for the operator's company and renders the conditions and tables the operator can check. The model writes only a short summary.

## Why: requirements and constraints that drove the design

From [requirements](../requirements.md): the model can never change the company (F5.2) or write data (F5.9); conditions and tables are rendered by code (F5.3–F5.4); ambiguous names are clarified (F5.6); outages are stated, never filled in (F5.7); follow-ups work on any instance (F5.8). Each question gets 30 seconds and at most 8 tool calls (N6.1); 100 questions per company per minute means about 330 requests waiting on the model at once (N6.3); a failing model provider must fail fast (N6.4). The copilot depends on an external model and is the least stable component, so it is a separate service: its memory exhaustion, crashes and frequent prompt changes must not stop position processing (N4.3).

## How: the design, with pointers to key code (file:line), SQL, Lua, proto

A Python FastAPI service ([app.py:1](../../copilot/fleet_copilot/app.py#L1)). Each question:

1. Reads the company's quota with `GetTenantPolicy`. If the API cannot be reached, it answers that data is unavailable without calling the model.
2. Spends one token from the company's Redis bucket (the same Lua script as the API's ingest limiter, [ratelimit.py:1](../../copilot/fleet_copilot/ratelimit.py#L1)); over quota returns 429 with `Retry-After`.
3. Takes a Redis lock for the conversation, waiting at most until the deadline, and loads its history ([memory.py:1](../../copilot/fleet_copilot/memory.py#L1)). Keys include the company ID.
4. Runs the tool loop ([service.py:1](../../copilot/fleet_copilot/service.py#L1)): the official OpenAI SDK pointed at DeepSeek ([model.py:1](../../copilot/fleet_copilot/model.py#L1)); tool calls run through [tools.py:1](../../copilot/fleet_copilot/tools.py#L1), which accepts only declared parameters, positive integer IDs and fixed enumerations, and sets the company in every gRPC request. Relative windows are converted in code ([timerange.py:1](../../copilot/fleet_copilot/timerange.py#L1)).
5. Stops early for a clarification (name lookup found zero or several vessels), a data outage, a replica delay or the tool cap, with a fixed answer.
6. Assembles the reply: the query basis rendered by the API ([ADR 044](../decisions/044-api-rendered-copilot-results.md)), assumptions that depend on the operator's wording ([transparency.py:1](../../copilot/fleet_copilot/transparency.py#L1)), then the model's summary. History is saved with 30-minute expiry and a 6-turn cap.

gRPC calls get one retry for a transient `UNAVAILABLE` inside the deadline; "Replicated data is delayed" is not retried. Model calls get one retry for rate limiting, 5xx, timeouts or dropped connections; five consecutive failures open a circuit breaker for 30 seconds ([breaker.py:1](../../copilot/fleet_copilot/breaker.py#L1)). Async IO lets hundreds of questions wait on the model without a thread each.

## Alternatives considered and trade-offs

LangChain/LangGraph would add abstractions for a flow that is one loop over fixed tools; the company ID, call cap and deadline are easier to verify in a direct loop. Pydantic AI fits FastAPI but is less common. Direct database reads from Python would duplicate company filtering in a second language and couple the copilot to the schema; a read-only account prevents writes but not cross-company reads. REST would carry this load too; gRPC is used for the shared, compile-checked contract between Java and Python, and the proto contains only query methods. Keeping history in process memory would lose follow-ups when a request lands on another instance.

## Failure modes and how each is handled

API down: the question is answered as unavailable before any model call; mid-turn, further data tools are skipped and the outage is stated. Replica more than 60 s behind: the API refuses, the copilot says data synchronization is delayed. Model provider failing: one retry, then 502; repeated failures open the breaker. Deadline: the whole turn is cancelled with 504. Concurrent questions in one conversation: serialized by the Redis lock; a waiting request gives up at its deadline. Unknown or extra tool parameters, fractional or string IDs: rejected before any request.

## Problems encountered and how they were solved

The Java version ([ADR 022](../decisions/022-separate-copilot-with-typed-api-owned-data-boundary.md)) had a breaker, retries and a stale policy cache around its own API, and per-instance memory; the requirements did not need the first three and contradicted the last ([ADR 042](../decisions/042-python-copilot-with-hand-written-tool-loop.md)). The first local evaluation pilot showed that without the stale cache an API outage produced an HTTP 503 instead of an answer; the copilot now answers the outage directly. Earlier evaluation rounds exposed epoch-time conversion errors (fixed by ISO strings, ADR 033) and outage tool loops (fixed by stopping dependent tools).

## Evidence: tests by name, measured numbers with conditions

`copilot/tests` (pytest, in-process gRPC server, scripted model, fakeredis): test_company_comes_from_the_server_and_tools_reject_a_tenant_argument, test_query_basis_and_tables_come_from_the_api_and_prose_is_only_the_summary, test_relative_windows_are_resolved_in_code_with_stated_assumptions, test_ambiguous_or_unknown_names_stop_for_clarification, test_unavailable_data_is_reported_without_inventing_results (replica delay and outage), test_tool_calls_are_capped_at_eight_per_question, test_the_whole_question_is_bounded_by_the_deadline, test_a_follow_up_is_answered_by_another_instance, test_questions_are_limited_per_company_per_minute, test_transient_model_errors_retry_once_and_a_failing_provider_opens_the_circuit, test_hundreds_of_questions_wait_on_the_model_concurrently (330 concurrent questions, 0.5 s model latency, complete in under 5 s), test_an_api_outage_is_answered_without_calling_the_model, plus HTTP contract tests. Real-model results are in [evaluation](evaluation.md).

## Known limits and what production would need

The company ID is a request field, not an authenticated identity. Service-to-service calls are unauthenticated. The breaker is per instance. Model costs depend on the provider's current prices.

## Likely interview questions with short, precise answers

**Q: Why a separate service?**

A: The model is the least stable dependency; its memory, crashes and frequent releases must not stop position processing.

**Q: Why Python?**

A: The model tooling ecosystem is Python; the Java API keeps data access.

**Q: Why gRPC between them?**

A: A shared proto gives both languages a compile-checked contract and exposes only query methods; at this volume REST would also be fast enough.

**Q: Why no agent framework?**

A: The flow is one loop over fixed tools; writing it directly keeps the company ID, the 8-call cap and the deadline explicit.

**Q: Can the model reach another company?**

A: No tool has a company parameter; the server sets it on every request and the API filters by it.

**Q: Can it write?**

A: No write RPC exists, and the API reads through a read-only pool with a SELECT-only account on the replica.

**Q: How do follow-ups work across instances?**

A: History is in Redis under company and conversation ID, with a per-conversation lock.

**Q: Where is the circuit breaker?**

A: Only around model calls; the internal API gets a deadline and one retry.

**Q: Do mocked tests prove useful answers?**

A: No; they prove control flow. The [evaluation](evaluation.md) uses real model answers.
