#!/usr/bin/env python3
"""Render recorded measurements only; never invent values for missing runs."""
import argparse
import json
from pathlib import Path
import statistics

p = argparse.ArgumentParser()
p.add_argument('results', type=Path)
a = p.parse_args()
rows = json.loads((a.results/'runs.json').read_text())
env = json.loads((a.results/'environment.json').read_text())
print(f"# k6 result: {env['run_id']}\n")
print(f"Baseline: {env['baseline_commit']}; repeats: {env['repeats']}. Values below are medians of recorded runs.\n")
print('| Version | Mode / seats | API avg ms | API p95 ms | User-flow avg ms | User-flow p95 ms | Response TPS | Success TPS | Errors pooled / worst run |')
print('|---|---|---:|---:|---:|---:|---:|---:|---:|')
for variant in sorted({r['variant'] for r in rows}):
    for mode, seats in [('api',500),('api',300),('flow',500)]:
        selected = [r for r in rows if (r['variant'],r['mode'],r['capacity']) == (variant,mode,seats)]
        if not selected:
            continue
        def med(key):
            values = [r[key] for r in selected if r[key] is not None]
            return statistics.median(values) if values else None
        fields = [med(k) for k in ['booking_avg_ms','booking_p95_ms','flow_avg_ms','flow_p95_ms',
                                   'completed_users_per_second','successful_bookings_per_second']]
        values = ['N/A' if v is None else f'{v:.2f}' for v in fields]
        error = sum(r['unexpected_error_rate']*r['completed_users'] for r in selected)/sum(r['completed_users'] for r in selected)
        worst = max(r['unexpected_error_rate'] for r in selected)
        print(f"| {variant} | {mode}/{seats} | "+' | '.join(values)+f" | {error*100:.3f}% / {worst*100:.2f}% |")
print('\n| Run | Pass | Booked | Sold out | Remaining | Attempt states | Pool active peak / max | Pending peak | Acquire timeouts | Samples |')
print('|---|---|---:|---:|---:|---|---|---:|---:|---:|')
for r in rows:
    v=r['verification']; pool=r['pool']
    print(f"| {r['label']} | {r['passed']} | {r['booked']} | {r['sold_out']} | {v['remaining']} | "
          f"{json.dumps(v['attempt_states']) if v['attempt_states'] is not None else 'N/A'} | "
          f"{pool['observed_active_peak']} / {pool['configured_max_observed']} | {pool['observed_pending_peak']} | {pool.get('observed_acquire_timeout_delta', 'N/A')} | {pool['sample_count']} |")
print(f"\nRecorded measured runs: {len(rows)}; passed: {sum(r['passed'] for r in rows)}.")
print('TPS is measured burst throughput, not sustainable maximum capacity. Normal sold-out responses are not technical errors.')

print('\n| Version | Mode / seats | Sample active avg median | Active peak | Pending peak | Acquire timeout delta sum |')
print('|---|---|---:|---:|---:|---:|')
for variant in sorted({r['variant'] for r in rows}):
    for mode, seats in [('api', 500), ('api', 300), ('flow', 500)]:
        selected = [r for r in rows if (r['variant'], r['mode'], r['capacity']) == (variant, mode, seats)]
        if not selected:
            continue
        pools = [r['pool'] for r in selected]
        avgs = [p['observed_active_avg'] for p in pools if p['observed_active_avg'] is not None]
        active = [p['observed_active_peak'] for p in pools if p['observed_active_peak'] is not None]
        pending = [p['observed_pending_peak'] for p in pools if p['observed_pending_peak'] is not None]
        timeouts = [p.get('observed_acquire_timeout_delta') for p in pools]
        timeout_sum = sum(timeouts) if all(t is not None for t in timeouts) else 'N/A'
        print(f"| {variant} | {mode}/{seats} | {statistics.median(avgs) if avgs else 'N/A'} | "
              f"{max(active) if active else 'N/A'} | {max(pending) if pending else 'N/A'} | {timeout_sum} |")
print('Active averages are sample means, not time-weighted utilization. Timeout deltas include the monitoring window around each burst.')
