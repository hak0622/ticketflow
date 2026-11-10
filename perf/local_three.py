#!/usr/bin/env python3
"""Three local JVMs, real k6 requests, shared disposable MySQL/Redis."""
import argparse
from collections import Counter
import hashlib
import json
import re
import shutil
import subprocess
import threading
import time
import uuid

from run import ROOT, Environment, PoolMonitor, build_jar, command, http, measure, metric


class ResourceMonitor:
    """Docker's streaming samples plus macOS memory snapshots, including startup."""
    def __init__(self, project, folder):
        self.project = project
        self.folder = folder
        self.stop = threading.Event()
        self.samples = []
        self.errors = (folder / 'resources-errors.log').open('w')
        self.process = subprocess.Popen(
            ['docker', 'stats', '--all', '--no-trunc', '--format', '{{json .}}'],
            stdout=subprocess.PIPE, stderr=self.errors, text=True)
        self.docker_thread = threading.Thread(target=self.collect_docker, daemon=True)
        self.host_thread = threading.Thread(target=self.collect_host, daemon=True)
        self.docker_thread.start()
        self.host_thread.start()

    def collect_docker(self):
        with (self.folder / 'resources.ndjson').open('w') as file:
            for line in self.process.stdout:
                try:
                    stats = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if not stats.get('Name', '').startswith(self.project + '-'):
                    continue
                sample = {'time_ms': time.time() * 1000, 'stats': stats}
                self.samples.append(sample)
                file.write(json.dumps(sample) + '\n')
                file.flush()

    def collect_host(self):
        with (self.folder / 'host-memory.ndjson').open('w') as file:
            while not self.stop.is_set():
                sample = {'time_ms': time.time() * 1000}
                for name, args in [('vm_stat', ['vm_stat']), ('swap', ['sysctl', '-n', 'vm.swapusage'])]:
                    result = subprocess.run(args, capture_output=True, text=True, timeout=10)
                    sample[name] = result.stdout if result.returncode == 0 else result.stderr
                file.write(json.dumps(sample) + '\n')
                file.flush()
                self.stop.wait(2)

    def finish(self, start, end):
        self.stop.set()
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.docker_thread.join(10)
        self.host_thread.join(15)
        self.errors.close()
        summary = {}
        for sample in self.samples:
            stats = sample['stats']
            row = summary.setdefault(stats['Name'], {'samples': 0, 'load_window_samples': 0,
                                                     'cpu_peak_percent': None, 'memory_peak_mib': None,
                                                     'cpu_samples': 0, 'memory_samples': 0})
            row['samples'] += 1
            row['load_window_samples'] += int(start <= sample['time_ms'] <= end)
            # Docker reports '--' for containers which have not started or have exited.
            # Preserve missing measurements instead of treating them as zero usage.
            cpu = re.fullmatch(r'([\d.]+)%', stats.get('CPUPerc', ''))
            if cpu:
                row['cpu_samples'] += 1
                row['cpu_peak_percent'] = max(row['cpu_peak_percent'] or 0, float(cpu.group(1)))
            memory = re.fullmatch(r'([\d.]+)(B|KiB|MiB|GiB)', stats.get('MemUsage', '').split('/')[0].strip())
            if memory:
                amount, unit = memory.groups()
                mib = float(amount) * {'B': 1 / 1048576, 'KiB': 1 / 1024, 'MiB': 1, 'GiB': 1024}[unit]
                row['memory_samples'] += 1
                row['memory_peak_mib'] = max(row['memory_peak_mib'] or 0, mib)
        (self.folder / 'resources-summary.json').write_text(json.dumps(summary, indent=2))
        return summary


class ThreeApps(Environment):
    def __init__(self, project, jar, results, users):
        super().__init__(project, jar, results, users=users)
        self.base = ['docker', 'compose', '-f', str(ROOT / 'perf/compose.local-three.yml'), '-p', project]
        self.k6_base_url = 'http://gateway:8080'
        self.metrics_urls = {}

    def start(self):
        # Bootstrap schema and JWT fixtures with one JVM before validate-only replicas start.
        super().start()
        self.metrics_urls['app'] = self.metrics_url
        self.dc('up', '-d', 'app-2', 'app-3', logfile=self.results / 'replicas-start.log')
        for service in ['app-2', 'app-3']:
            port = self.dc('port', service, '8081')[0].strip().rsplit(':', 1)[1]
            url = 'http://127.0.0.1:' + port
            self.metrics_urls[service] = url
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                try:
                    if json.loads(http(url + '/actuator/health')).get('status') == 'UP':
                        break
                except Exception:
                    pass
                time.sleep(1)
            else:
                raise RuntimeError(service + ' failed readiness')
        self.dc('up', '-d', 'gateway', logfile=self.results / 'gateway-start.log')
        port = self.dc('port', 'gateway', '8080')[0].strip().rsplit(':', 1)[1]
        self.url = 'http://127.0.0.1:' + port

    def close(self):
        try:
            for service in ['app-2', 'app-3', 'gateway']:
                self.dc('logs', '--no-color', service, logfile=self.results / (service + '.log'), check=False)
            ids = self.dc('ps', '-a', '-q')[0].split()
            if ids:
                command(['docker', 'inspect', *ids], logfile=self.results / 'container-inspect.json', check=False)
        finally:
            super().close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--users', type=int, choices=[100, 300, 500], default=100)
    parser.add_argument('--capacity', type=int)
    parser.add_argument('--mode', choices=['api', 'flow'], default='api')
    args = parser.parse_args()
    capacity = args.capacity if args.capacity is not None else args.users
    if not 1 <= capacity <= args.users or (args.mode == 'flow' and capacity != args.users):
        parser.error('Capacity must be 1..users; flow currently requires capacity == users')
    run_id = time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
    folder = ROOT / 'build/performance' / ('local-three-' + run_id)
    folder.mkdir(parents=True)
    print('Results:', folder, flush=True)
    jar = build_jar(ROOT, folder / 'build.log')
    saved = folder / 'app.jar'
    shutil.copy2(jar, saved)
    metadata = {
        'users': args.users, 'capacity': capacity, 'mode': args.mode,
        'app_count': 3, 'warmup': False, 'purpose': 'local smoke and consistency validation',
        'docker': json.loads(command(['docker', 'info', '--format', '{{json .}}'])[0]),
        'jar_sha256': hashlib.sha256(saved.read_bytes()).hexdigest(),
        'source_sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in (ROOT / 'src/main').rglob('*') if p.is_file()},
    }
    (folder / 'environment.json').write_text(json.dumps(metadata, indent=2))
    for name in ['compose.yml', 'compose.local-three.yml', 'nginx.local-three.conf', 'booking.js', 'run.py', 'local_three.py']:
        shutil.copy2(ROOT / 'perf' / name, folder / name)
    env = ThreeApps('tfperf-local-three-' + run_id, saved, folder, args.users)
    monitors = {}
    resources = ResourceMonitor(env.project, folder)
    try:
        env.start()
        for service, url in env.metrics_urls.items():
            if service != 'app':
                monitor = PoolMonitor(url, folder / (service + '-pool.ndjson'))
                monitors[service] = monitor
                monitor.thread.start()
        row = measure(env, 'smoke', capacity, args.mode, modern=True)
        summary = json.loads((folder / 'smoke.json').read_text())
        start = metric(summary, 'user_started_at_ms', 'min')
        end = metric(summary, 'user_finished_at_ms', 'max')
        row['instance_pools'] = {'app': row['pool']}
        for service, monitor in monitors.items():
            row['instance_pools'][service] = monitor.finish(start, end)
        row['resources'] = resources.finish(start, end)
        resources = None
        row['resource_scope'] = 'Peaks cover startup through verification; load_window_samples identifies burst coverage'
        logs = env.dc('logs', '--no-color', '--no-log-prefix', 'gateway')[0]
        (folder / 'gateway-routing.log').write_text(logs)
        pattern = rf'^(\S+) POST /api/concerts/{row["concert_id"]}/booking (\d+)$'
        routes = Counter(match.group(1) for match in re.finditer(pattern, logs, re.M))
        row['booking_routes'] = dict(routes)
        row['routing_passed'] = len(routes) == 3 and sum(routes.values()) == args.users
        row['replica_telemetry_passed'] = all(p['sample_count'] > 0 and p['observed_active_peak'] is not None
                                             for p in row['instance_pools'].values())
        row['passed'] = row['passed'] and row['routing_passed'] and row['replica_telemetry_passed']
        (folder / 'result.json').write_text(json.dumps(row, indent=2))
        print(json.dumps({'passed': row['passed'], 'routes': dict(routes),
                          'verification': row['verification']}, indent=2), flush=True)
        if not row['passed']:
            raise SystemExit(1)
    finally:
        for monitor in monitors.values():
            monitor.stop.set()
            monitor.thread.join(20)
        try:
            if resources is not None:
                resources.finish(0, time.time() * 1000)
        finally:
            env.close()


if __name__ == '__main__':
    main()
