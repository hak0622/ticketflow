#!/usr/bin/env python3
"""Paired profiling-off/on API-500 runs using the unchanged k6 workload and resources."""
import hashlib
import json
import shutil
import time
import uuid
from pathlib import Path
from run import ROOT, Environment, build_jar, command, measure


def main():
    runid = time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
    results = ROOT / 'build/performance' / ('timing-' + runid)
    results.mkdir(parents=True)
    print('Results:', results, flush=True)
    override = results / 'profiling-compose.yml'
    override.write_text('services:\n  app:\n    environment:\n      BOOKING_PROFILING_ENABLED: ${BOOKING_PROFILING_ENABLED:-false}\n')
    jar = build_jar(ROOT, results / 'build.log')
    docker = json.loads(command(['docker', 'info', '--format', '{{json .}}'])[0])
    sources = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
               for p in (ROOT/'src/main').rglob('*') if p.is_file()}
    metadata = {'run_id':runid, 'started_at':time.strftime('%Y-%m-%dT%H:%M:%S%z'),
                'docker_cpus':docker['NCPU'], 'docker_memory_bytes':docker['MemTotal'],
                'docker_version':docker['ServerVersion'], 'jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
                'source_sha256':sources, 'variants':['control','profiled'], 'repeats':3}
    images = command(['docker','image','inspect','mysql:8.4','redis:7-alpine',
                      'eclipse-temurin:17-jre-jammy','grafana/k6:1.0.0','--format','{{json .RepoDigests}}'])[0]
    metadata['image_digests'] = [json.loads(line) for line in images.splitlines()]
    (results/'environment.json').write_text(json.dumps(metadata, indent=2))
    rows = []
    for name, enabled in [('control',False), ('profiled',True)]:
        folder = results/name
        folder.mkdir()
        saved = folder/'app.jar'
        shutil.copy2(jar, saved)
        env = Environment('tfperf-timing-'+runid+'-'+name, saved, folder)
        env.base += ['-f',str(override)]
        env.env['BOOKING_PROFILING_ENABLED'] = str(enabled).lower()
        try:
            print('Starting',name,flush=True)
            env.start()
            warmup = measure(env,'warmup',500,'api',True,warmup=True)
            if not warmup['passed']:
                raise RuntimeError(name+' warmup failed')
            for repeat in range(1,4):
                row = measure(env,f'{name}-api-500-r{repeat}',500,'api',True)
                rows.append(dict(variant=name,repeat=repeat,**row))
                (results/'runs.json').write_text(json.dumps(rows,indent=2))
        finally:
            # Buffered diagnostics are emitted on graceful shutdown, outside measured requests.
            try:
                env.dc('stop','-t','60','app',logfile=folder/'app-stop.log',check=False)
            finally:
                env.close()
    assert all(hashlib.sha256((ROOT/f).read_bytes()).hexdigest()==h for f,h in sources.items())
    metadata['sources_unchanged_during_measurement'] = True
    (results/'environment.json').write_text(json.dumps(metadata,indent=2))
    for file in ['timing.py','run.py','booking.js','compose.yml']:
        shutil.copy2(ROOT/'perf'/file, results/file)
    print('Finished:',results,'all_passed=',all(r['passed'] for r in rows),flush=True)
    if not all(r['passed'] for r in rows):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
