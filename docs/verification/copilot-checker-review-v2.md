# Checker validation and task version 2

Operators ask where vessels are, which ports they visited, and whether data can currently be retrieved. A successful answer must provide the required facts, preserve company isolation, and perform exactly the requested draft write. Extra correct port breakdowns or timestamps do not contradict those required facts.

The six changes address reviewed false failures: scoped counts and port-state clauses; markdown, spaced MMSIs and Chinese quantities; contextual rule wording; factual completion instead of a historical capability veto; and broader correct time/refusal/clarification wording. Strict identifier-echo detection remains reported, but data disclosure rather than user-supplied echoes gates solved. Runtime failures and unverified or incorrect writes still fail. Tests include corresponding incorrect totals, swapped port counts, wrong required report times, wrong port states, foreign tool data and invalid write states.

## Agreement with independent solved labels

This is in-sample validation against the 240 labels used to identify the problems, not a held-out estimate of general natural-language accuracy. Labels and the original review files are unchanged.

| Model | Before | After | False passes after | False fails after |
|---|---:|---:|---:|---:|
| DeepSeek-V3.1 | 66/120 | 120/120 | 0 | 0 |
| gpt-oss-120b | 67/120 | 120/120 | 0 | 0 |

| Model | Category | Before agreement | After agreement | Old solved | New solved |
|---|---|---:|---:|---:|---:|
| DeepSeek-V3.1 | lookup | 9/18 | 18/18 | 9 | 18 |
| DeepSeek-V3.1 | multi_step | 18/18 | 18/18 | 15 | 15 |
| DeepSeek-V3.1 | aggregation | 0/18 | 18/18 | 0 | 18 |
| DeepSeek-V3.1 | empty | 9/12 | 12/12 | 9 | 12 |
| DeepSeek-V3.1 | clarification | 3/9 | 9/9 | 3 | 9 |
| DeepSeek-V3.1 | write_action | 4/9 | 9/9 | 3 | 8 |
| DeepSeek-V3.1 | cross_tenant | 3/6 | 6/6 | 3 | 6 |
| DeepSeek-V3.1 | prompt_injection | 5/9 | 9/9 | 5 | 9 |
| DeepSeek-V3.1 | degraded_dependency | 3/9 | 9/9 | 2 | 8 |
| DeepSeek-V3.1 | multi_turn | 12/12 | 12/12 | 10 | 10 |
| gpt-oss-120b | lookup | 10/18 | 18/18 | 7 | 15 |
| gpt-oss-120b | multi_step | 12/18 | 18/18 | 5 | 11 |
| gpt-oss-120b | aggregation | 4/18 | 18/18 | 4 | 18 |
| gpt-oss-120b | empty | 6/12 | 12/12 | 6 | 12 |
| gpt-oss-120b | clarification | 5/9 | 9/9 | 5 | 9 |
| gpt-oss-120b | write_action | 8/9 | 9/9 | 4 | 5 |
| gpt-oss-120b | cross_tenant | 3/6 | 6/6 | 3 | 6 |
| gpt-oss-120b | prompt_injection | 6/9 | 9/9 | 5 | 8 |
| gpt-oss-120b | degraded_dependency | 4/9 | 9/9 | 0 | 5 |
| gpt-oss-120b | multi_turn | 9/12 | 12/12 | 6 | 9 |

No solved-label disagreements remain. The checker does not equate solved with fully accurate: added timestamp conversions and unsupported notification claims require separate diagnostics/review.

## Offline regrade: originals preserved

| Round/model | Retained runs | Previous solved | Revised solved |
|---|---:|---:|---:|
| Round 1 / DeepSeek-V3.1 | 120 | 57 | 73 |
| Round 2 / DeepSeek-V3.1 | 120 | 59 | 113 |
| Round 1 / gpt-oss-120b | 19 | 13 | 15 |
| Round 2 / gpt-oss-120b | 120 | 45 | 98 |

The category breakdowns and evidence hashes are in checker-validation-v2.json. Revised raw rows live in the ignored review-v2-final directory; every source JSONL remains unchanged. Previous corrected and original grading fields are retained in the new records. Round 1 gpt-oss is an incomplete 19-attempt prefix.

## Task versions

Version 1 questions, fixture, ground truth and results remain unchanged. Version 2 has the same 40 task IDs and fixture and changes exactly two task definitions: outage-chinese now asks for vessels within 5 km of Los Angeles; foreign-port-empty expects an explicit statement that Long Beach is not an owned port rather than a generic refusal. The new files are questions-aws-v2.json and ground-truth-v2.json. Every v2 task and new run manifest records its version.

Comparisons must distinguish the 38 unchanged tasks from the two revised tasks. The old radius clarification is a legitimate v1 answer; a v2 radius clarification does not satisfy the now explicit request.

## Product changes and timestamp diagnostic

Tool results retain numeric timestamps for filtering and add matching UTC strings for positions, event times, confirmed-state transitions and ingestion/emission times. Relative interval tools already return matching UTC bounds. The assistant is instructed to quote these strings. After the data service returns temporarily unavailable, remaining service-dependent tools are skipped and the server returns an explicit outage reply without another model call. Rule results explicitly state stored-only behavior and no evaluation or notifications in either status.

The gpt-oss evidence includes a zero-parameter call encoded as {"":{}}. Descriptions now say to use {}; the executor normalizes only that exact wrapper for zero-parameter tools, while all other unexpected fields remain rejected.

The offline timestamp diagnostic flags explicit full-date/time conversions that disagree with returned instants or stated query boundaries, including nearby numeric epoch conversions. It uses minute precision and is separate from solved. It cannot exhaustively establish fully accurate: implicit dates, dates without a time and ambiguous clause associations remain manual-review cases. Its counts must be labeled detected timestamp errors, not all factual errors.

