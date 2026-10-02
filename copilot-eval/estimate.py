#!/usr/bin/env python3
"""Planning assumptions for two targets, not measured tokens or a billing ceiling."""
import json
from pathlib import Path


def task_cost(count, input_rate, output_rate, input_tokens=9000, output_tokens=1400):
    return round(
        count * (input_tokens * input_rate + output_tokens * output_rate) / 1_000_000, 6
    )


def suite(repeats, target_rates, judge_rates):
    judged = task_cost(40 * repeats, *judge_rates, 2000, 200)
    calibration = task_cost(14, *judge_rates, 2000, 200)
    warm = task_cost(3 * repeats, *target_rates, 6000, 600)
    priming = task_cost(8 * repeats, *target_rates, 6000, 600)
    target = task_cost(40 * repeats, *target_rates)
    return dict(
        repeats=repeats,
        targetUsd=target,
        taskJudgeUsd=judged,
        calibrationUsd=calibration,
        warmupUsd=warm,
        circuitPrimingUsd=priming,
        totalUsd=round(target + judged + calibration + warm + priming, 6),
    )


def estimate():
    deepseek, gpt = (0.58, 1.68), (0.15, 0.60)
    primary = suite(3, deepseek, gpt)
    comparison = suite(3, gpt, deepseek)
    once = suite(1, gpt, deepseek)
    pilots = sum(task_cost(3, *rates) for rates in (deepseek, gpt)) + sum(
        task_cost(17, *rates, 2000, 200) for rates in (gpt, deepseek)
    )
    standalone_calibrations = sum(
        task_cost(14, *rates, 2000, 200) for rates in (gpt, deepseek)
    )
    total = (
        primary["totalUsd"] + comparison["totalUsd"] + pilots + standalone_calibrations
    )
    reduced = primary["totalUsd"] + once["totalUsd"] + pilots + standalone_calibrations
    return dict(
        kind="planning assumptions; no inference measured",
        target="DeepSeek-V3.1",
        comparison="OpenAI gpt-oss-120b",
        judges={"DeepSeek target": "gpt-oss-120b", "gpt-oss target": "DeepSeek-V3.1"},
        assumedTokensPerTaskRun=dict(
            targetInput=9000, targetOutput=1400, judgeInput=2000, judgeOutput=200
        ),
        pricesUsdPerMillion=dict(deepseek=deepseek, gpt=gpt),
        primary=primary,
        comparisonThreeRuns=comparison,
        comparisonOneRun=once,
        twoPilotsUsd=round(pilots, 6),
        standaloneCalibrationsUsd=standalone_calibrations,
        threeHourHostReserveUsd=3 * 0.6257013698630137,
        combinedHostAndInferenceEstimateUsd=round(total + 3 * 0.6257013698630137, 6),
        threeRunComparisonTotalUsd=round(total, 6),
        oneRunComparisonTotalUsd=round(reduced, 6),
        doubleTokenSensitivityUsd=round(2 * total, 6),
        assumptions="Includes two three-task pilots and 14 judge calibration calls in two standalone checks plus each pilot/full invocation (84 total); no calibration reuse. Includes full-suite outage warm-up/probes. Excludes AWS host costs and any unknown failed-call usage. Use actual pilot tokens before deciding full-run allowance; never subtract credits.",
    )


if __name__ == "__main__":
    result = estimate()
    Path(__file__).with_name("cost-estimate.json").write_text(
        json.dumps(result, indent=2) + "\n"
    )
    print(json.dumps(result, indent=2))
