#!/usr/bin/env python3
"""Isolated offline daemon/JNI slow-read recovery acceptance. Never connects to owner Core or wallet."""
from pathlib import Path
import tempfile,subprocess,hashlib,json,urllib.request,socket,time,threading,http.server,os
os.umask(0o077)
repo=Path(__file__).resolve().parents[2]
out=Path(__file__).resolve().parent
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--monerod',type=Path,required=True)
args=parser.parse_args()
jar=repo/'target/qortium-1.8.1.jar'
if not jar.is_file(): raise RuntimeError('Package Core before running this acceptance')
daemon=args.monerod.resolve()
if hashlib.sha256(daemon.read_bytes()).hexdigest()!='9b3b2676ea7868c1a7186feea9569c2cf7683ae79d2fcc769c846a91c810a1f5':
 raise RuntimeError('Use the verified official Linux x86_64 Monero 0.18.5.1 daemon')
def port():
 with socket.socket() as s: s.bind(('127.0.0.1',0));return s.getsockname()[1]
with tempfile.TemporaryDirectory(prefix='xmr-slow-daemon-') as directory:
 root=Path(directory);rpcport=port();process=None;proxy=None;faults=[]
 def rpc(method,params=None):
  req=urllib.request.Request(f'http://127.0.0.1:{rpcport}/json_rpc',data=json.dumps({'jsonrpc':'2.0','id':'synthetic','method':method,'params':params or {}}).encode(),headers={'Content-Type':'application/json'})
  with urllib.request.urlopen(req,timeout=10) as response:return json.load(response)['result']
 class Proxy(http.server.BaseHTTPRequestHandler):
  protocol_version='HTTP/1.1'
  def log_message(self,*args): pass
  def serve(self):
   body=self.rfile.read(int(self.headers.get('Content-Length','0')))
   if (root/'arm-delay').exists() and self.path=='/getblocks.bin' and not faults:
    method=None
    if self.path=='/json_rpc':
     try: method=json.loads(body).get('method')
     except ValueError: pass
    faults.append({'path':self.path,'method':method,'injectedDelayMillis':2500});time.sleep(2.5)
   request=urllib.request.Request(f'http://127.0.0.1:{rpcport}'+self.path,data=body if self.command=='POST' else None,headers={'Content-Type':self.headers.get('Content-Type','application/json')},method=self.command)
   with urllib.request.urlopen(request,timeout=10) as response: data=response.read();code=response.status;ctype=response.headers.get('Content-Type','application/json')
   self.send_response(code);self.send_header('Content-Length',str(len(data)));self.send_header('Content-Type',ctype);self.end_headers();self.wfile.write(data)
  do_POST=serve;do_GET=serve
 try:
  with (root/'daemon.log').open('w') as log:
   process=subprocess.Popen([str(daemon),'--regtest','--offline','--fixed-difficulty','1','--data-dir',str(root/'chain'),'--rpc-bind-ip','127.0.0.1','--rpc-bind-port',str(rpcport),'--p2p-bind-ip','127.0.0.1','--p2p-bind-port',str(port()),'--no-zmq','--non-interactive','--disable-dns-checkpoints','--check-updates','disabled','--rpc-ssl','disabled','--max-concurrency','1','--log-file',str(root/'native.log')],cwd=root,stdout=log,stderr=subprocess.STDOUT)
  until=time.monotonic()+30
  while True:
   try:
    if rpc('get_info')['offline']: break
   except (OSError,ValueError,KeyError): pass
   if time.monotonic()>until: raise RuntimeError('Offline daemon failed to start')
   time.sleep(.2)
  fixture=json.loads((repo/'src/test/resources/monero/derivation-v1.json').read_text())['fixtures'][0]['address']
  rpc('generateblocks',{'wallet_address':fixture,'amount_of_blocks':12})
  proxy=http.server.ThreadingHTTPServer(('127.0.0.1',0),Proxy);threading.Thread(target=proxy.serve_forever,daemon=True).start()
  subprocess.run(['javac','-proc:none','-cp',str(jar),'-d',str(root),str(out/'SlowReadRecovery.java')],check=True)
  result=subprocess.run(['java','-Dlog4j.configurationFile='+str(repo/'preview/log4j2.properties'),'-Dqortium.log.dir='+str(root),'-cp',str(root)+':'+str(jar),'org.qortium.crosschain.monero.SlowReadRecovery',str(root),f'http://127.0.0.1:{proxy.server_port}'],capture_output=True,text=True,timeout=35,cwd=root)
  if result.returncode: raise RuntimeError('Probe failed: '+result.stderr[-2000:])
  proof=json.loads(result.stdout.strip())
  if len(faults)!=1 or faults[0]['path']!='/getblocks.bin': raise RuntimeError('Expected exactly one delayed binary block response')
  proof.update({'fault':faults[0],'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'scope':'Fresh synthetic offline regtest only; no owner process/file access'})
  print(json.dumps(proof))
 finally:
  if proxy: proxy.shutdown();proxy.server_close()
  if process:
   process.terminate()
   try: process.wait(timeout=10)
   except subprocess.TimeoutExpired: process.kill();process.wait(timeout=5)
