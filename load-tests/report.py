#!/usr/bin/env python3
"""
Turns k6's JSON summaries into the markdown tables in docs/PERFORMANCE.md.

Exists so the published numbers are derived mechanically from the raw summaries rather than
transcribed by hand. Only the phase:measure window is read - the warm-up rung of every step is
discarded, which is the whole reason the two phases are separate k6 scenarios.

    python3 load-tests/report.py                 # every scenario found in results/
    python3 load-tests/report.py contention      # just one
"""
import json
import pathlib
import re
import sys

RESULTS = pathlib.Path(__file__).parent / "results"

# The metrics carrying per-step tags, as declared by steppedThresholds() in lib/config.js.
STEP_RE = re.compile(r"^(?P<metric>[a-z_0-9]+)\{step:(?P<step>\d+),phase:measure\}$")


def load(scenario):
    path = RESULTS / f"{scenario}.json"
    if not path.exists():
        return None
    return json.loads(path.read_text())


def per_step(data):
    steps = {}
    for key, entry in data.get("metrics", {}).items():
        m = STEP_RE.match(key)
        if not m:
            continue
        step = int(m.group("step"))
        steps.setdefault(step, {})[m.group("metric")] = entry.get("values", {})
    return dict(sorted(steps.items()))


def fmt(value, digits=1):
    return "-" if value is None else f"{value:.{digits}f}"


def table(scenario, data):
    steps = per_step(data)
    if not steps:
        return f"_no per-step metrics found for {scenario}_\n"

    lines = [
        "| VUs | req/s | p50 ms | p95 ms | p99 ms | accepted | 503 retry-exhausted | 503 % | 422 | 409 | other |",
        "|----:|------:|-------:|-------:|-------:|---------:|--------------------:|------:|----:|----:|------:|",
    ]
    for vus, m in steps.items():
        dur = m.get("http_req_duration", {})
        reqs = m.get("http_reqs", {})
        total = reqs.get("count") or 0
        exhausted = (m.get("retry_exhausted_503") or {}).get("count", 0)
        pct = (exhausted / total * 100) if total else 0.0
        lines.append(
            f"| {vus} | {fmt(reqs.get('rate'))} | {fmt(dur.get('p(50)'))} | {fmt(dur.get('p(95)'))} "
            f"| {fmt(dur.get('p(99)'))} | {int((m.get('accepted') or {}).get('count', 0))} "
            f"| {int(exhausted)} | {fmt(pct, 2)} "
            f"| {int((m.get('insufficient_funds_422') or {}).get('count', 0))} "
            f"| {int((m.get('idempotency_conflict_409') or {}).get('count', 0))} "
            f"| {int((m.get('other_error') or {}).get('count', 0))} |"
        )
    return "\n".join(lines) + "\n"


def breakpoint_note(data, threshold_pct=1.0):
    """First rung whose retry-exhaustion rate crosses the stated threshold."""
    for vus, m in per_step(data).items():
        total = (m.get("http_reqs") or {}).get("count") or 0
        exhausted = (m.get("retry_exhausted_503") or {}).get("count", 0)
        if total and (exhausted / total * 100) >= threshold_pct:
            return vus, exhausted / total * 100
    return None, None


def settlement(scenario):
    path = RESULTS / f"settlement-{scenario}.json"
    if not path.exists():
        return None
    return json.loads(path.read_text())


def main():
    scenarios = sys.argv[1:] or ["baseline", "contention", "read-heavy", "cross-currency"]
    for scenario in scenarios:
        data = load(scenario)
        print(f"\n## {scenario}\n")
        if data is None:
            print(f"_no results/{scenario}.json - run ./load-tests/run.sh {scenario}_\n")
            continue
        print(table(scenario, data))

        vus, pct = breakpoint_note(data)
        if vus is not None:
            print(f"\n**Retry exhaustion crosses 1% at {vus} concurrent VUs** ({pct:.2f}%).\n")

        s = settlement(scenario)
        if s:
            print(
                f"Settlement: {s['settledTotal']} settled, {s['failedTotal']} failed, "
                f"{s['pendingAtLoadStop']} still in flight when load stopped, "
                f"backlog drained in {s['backlogDrainSeconds']}s.\n"
            )


if __name__ == "__main__":
    main()
