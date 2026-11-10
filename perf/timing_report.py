#!/usr/bin/env python3
"""Summarize measured per-request timings. Nested timers must not be added together."""
import argparse
import csv
import json
import statistics
from collections import Counter
from pathlib import Path


def quantile(values, fraction):
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def distribution(values):
    return dict(n=len(values), mean=statistics.mean(values), p50=quantile(values, .5),
                p90=quantile(values, .9), p95=quantile(values, .95), p99=quantile(values, .99),
                min=min(values), max=max(values))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('results', type=Path)
    args = parser.parse_args()
    root = args.results
    runs = json.loads((root/'runs.json').read_text())
    traces = []
    for line in (root/'profiled/app.log').read_text().splitlines():
        if '[BOOKING_TIMING] {' in line:
            traces.append(json.loads(line.split('[BOOKING_TIMING] ', 1)[1]))
    (root/'request-timings.json').write_text(json.dumps(traces, indent=2))
    required = ['create.transaction','create.connection.acquire','create.connection.hold',
                'create.sql.attempt_insert','create.commit','execute.transaction',
                'execute.connection.acquire','execute.connection.hold','execute.sql.attempt_lock',
                'execute.sql.booking_lookup','execute.sql.concert_lookup','execute.redis.reserve',
                'execute.sql.booking_insert','execute.sql.attempt_update','execute.commit','service.total']
    selected = []
    checks = {}
    for run in [r for r in runs if r['variant']=='profiled']:
        samples = [t for t in traces if t['concertId']==run['concert_id']]
        checks[run['label']] = dict(records=len(samples), unique_requests=len({t['requestId'] for t in samples}),
            all_booked=all(t['outcome']=='BOOKED' for t in samples),
            all_closed=all(t['unclosed']==0 for t in samples),
            all_required_once=all(all(t['counts'].get(k)==1 for k in required) for t in samples))
        for trace in samples:
            trace['run'] = run['label']
            ns = trace['ns']
            ns['derived.acquire_sum'] = ns['create.connection.acquire'] + ns['execute.connection.acquire']
            ns['derived.hold_sum'] = ns['create.connection.hold'] + ns['execute.connection.hold']
            ns['derived.sql_sum'] = sum(value for key,value in list(ns.items()) if '.sql.' in key)
            ns['derived.commit_sum'] = ns['create.commit'] + ns['execute.commit']
            ns['derived.service_other'] = ns['service.total'] - ns['derived.acquire_sum'] - ns['derived.hold_sum']
            ns['derived.hold_other'] = ns['derived.hold_sum'] - ns['derived.sql_sum'] - ns['derived.commit_sum'] - ns['execute.redis.reserve']
        selected += samples
    valid = all(c['records']==500 and c['unique_requests']==500 and c['all_booked'] and c['all_closed']
                and c['all_required_once'] for c in checks.values()) and len(checks)==3
    (root/'timing-checks.json').write_text(json.dumps(dict(passed=valid,checks=checks),indent=2))
    if not valid:
        raise RuntimeError('Missing/duplicate timing samples; inspect timing-checks.json')
    groups = {'pooled_1500':selected}
    groups.update({run['label']:[t for t in selected if t['run']==run['label']]
                   for run in runs if run['variant']=='profiled'})
    summary = {}
    for name,samples in groups.items():
        keys = sorted({key for sample in samples for key in sample['ns']})
        summary[name] = {key:distribution([s['ns'][key]/1e6 for s in samples if key in s['ns']]) for key in keys}
    (root/'stage-distributions-ms.json').write_text(json.dumps(summary,indent=2))
    with (root/'stage-samples.csv').open('w') as file:
        writer = csv.writer(file)
        writer.writerow(['run','requestId','metric','milliseconds'])
        for sample in selected:
            for key,value in sample['ns'].items():
                writer.writerow([sample['run'],sample['requestId'],key,value/1e6])
    lines = ['# Booking timing distributions','',
             'Pooled successful requests, milliseconds. Nested repository/transaction/SQL timers overlap.', '',
             '| Metric | n | Mean | p50 | p90 | p95 | p99 | Max |',
             '|---|---:|---:|---:|---:|---:|---:|---:|']
    for key,d in summary['pooled_1500'].items():
        lines.append(f"| {key} | {d['n']} | " + ' | '.join(f"{d[x]:.3f}" for x in ['mean','p50','p90','p95','p99','max'])+' |')
    (root/'stage-report.md').write_text('\n'.join(lines)+'\n')
    print(json.dumps(dict(passed=valid, measured_requests=len(selected)),indent=2))


if __name__=='__main__':
    main()
