import http.server,threading,time,json,subprocess,pathlib,uuid
out=pathlib.Path('test-results/inventory-alerting/current').resolve(); out.mkdir(parents=True,exist_ok=True); records=[]; mode=[1]
class Handler(http.server.BaseHTTPRequestHandler):
 def log_message(self,*args): pass
 def do_GET(self):
  data=f'booking_inventory_incomplete_current{{phase="RESERVE_PENDING"}} {mode[0]}\nbooking_inventory_incomplete_current{{phase="RELEASE_PENDING"}} 0\nbooking_inventory_observation_last_success_timestamp_seconds {time.time()}\n'.encode()
  self.send_response(200);self.end_headers();self.wfile.write(data)
 def do_POST(self):
  records.append({'time':time.time(),'body':self.rfile.read(int(self.headers['Content-Length'])).decode()});self.send_response(200);self.end_headers();self.wfile.write(b'ok')
server=http.server.ThreadingHTTPServer(('0.0.0.0',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start();port=server.server_address[1]
name='tf-inventory-alert-'+uuid.uuid4().hex[:8]
def docker(*args): return subprocess.run(['docker',*args],check=True,capture_output=True,text=True).stdout
(out/'alertmanager.yml').write_text(f'''route:
  receiver: local
  group_by: [alertname, phase]
  group_wait: 1s
  group_interval: 1s
  repeat_interval: 1h
receivers:
  - name: local
    slack_configs:
      - api_url: http://host.docker.internal:{port}/slack
        send_resolved: true
        title: '{{{{ .Status }}}}'
''')
(out/'prometheus.yml').write_text(f'''global:
  scrape_interval: 1s
  evaluation_interval: 1s
rule_files: [/rules/inventory-observation.yml]
alerting:
  alertmanagers:
    - static_configs:
        - targets: ['{name}-am:9093']
scrape_configs:
  - job_name: blog-concert-app
    static_configs:
      - targets: ['host.docker.internal:{port}']
''')
try:
 docker('network','create',name)
 docker('run','-d','--name',name+'-am','--network',name,'-v',str(out/'alertmanager.yml')+':/etc/alertmanager/alertmanager.yml:ro','prom/alertmanager:v0.27.0')
 docker('run','-d','--name',name+'-prom','--network',name,'-v',str(out/'prometheus.yml')+':/etc/prometheus/prometheus.yml:ro','-v',str(pathlib.Path('prometheus/rules').resolve())+':/rules:ro','prom/prometheus:v2.51.2')
 deadline=time.time()+90
 while time.time()<deadline and not any('firing' in x['body'] for x in records):time.sleep(1)
 assert any('firing' in x['body'] for x in records),'firing not delivered'
 mode[0]=0
 deadline=time.time()+60
 while time.time()<deadline and not any('resolved' in x['body'] for x in records):time.sleep(1)
 assert any('resolved' in x['body'] for x in records),'resolved not delivered'
 print('PASS local Slack-compatible delivery: firing and resolved')
finally:
 (out/'slack-receiver.json').write_text(json.dumps(records,indent=2))
 for suffix in ('-prom','-am'):
  logs=subprocess.run(['docker','logs',name+suffix],capture_output=True,text=True);(out/(suffix+'.log')).write_text(logs.stdout+logs.stderr)
  subprocess.run(['docker','rm','-f',name+suffix],capture_output=True)
 subprocess.run(['docker','network','rm',name],capture_output=True);server.shutdown()
