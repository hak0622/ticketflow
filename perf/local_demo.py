#!/usr/bin/env python3
"""Start an isolated local UI demo; keep it running until explicitly stopped."""
import json
import sys
from run import Environment, ROOT

folder = ROOT / 'test-results' / 'payment-stage1-demo'
folder.mkdir(parents=True, exist_ok=True)
jar = next((ROOT / 'build/libs').glob('*.jar'))
env = Environment('tfperf-payment-demo', jar, folder)
overlay = folder / 'compose.yml'
overlay.write_text('''services:
  app:
    cpus: 1
    mem_limit: 768m
    entrypoint: ["java", "-Xms256m", "-Xmx384m", "-jar", "/app/app.jar"]
    ports: !override
      - "127.0.0.1:8080:8080"
      - "127.0.0.1::8081"
    environment:
      PAYMENT_MOCK_ENABLED: "true"
''')
env.base += ['-f', str(overlay)]
if sys.argv[1:] == ['start']:
    env.start()
    cid = env.concert('LOCAL_DEMO_3_TICKETS', 3, 'flow')
    (folder/'demo.json').write_text(json.dumps({'concert_id': cid, 'url': env.url, 'project': env.project}, indent=2))
    print('Demo ready: http://localhost:5173 ; API: http://localhost:8080 ; concert:', cid)
elif sys.argv[1:] == ['restart']:
    env.dc('up', '-d', '--force-recreate', 'app', logfile=folder/'restart.log')
    print('Demo app restarted; database and Redis retained')
elif sys.argv[1:] == ['stop']:
    env.dc('stop', logfile=folder/'stop.log')
else:
    raise SystemExit('Usage: python3 perf/local_demo.py start|restart|stop')
