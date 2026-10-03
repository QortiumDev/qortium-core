#!/usr/bin/env python3
"""Run Core JNI acceptance with synthetic public fixtures and a fresh pinned offline daemon."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--monerod', type=Path, required=True)
    args = parser.parse_args()
    binary = args.monerod.resolve()
    if hashlib.sha256(binary.read_bytes()).hexdigest() != '9b3b2676ea7868c1a7186feea9569c2cf7683ae79d2fcc769c846a91c810a1f5':
        raise RuntimeError('Use the verified official Linux x86_64 Monero 0.18.5.1 daemon')
    os.umask(0o077)
    repo = Path(__file__).resolve().parents[2]
    root = Path(tempfile.mkdtemp(prefix='qortium-xmr-core-regtest-'))
    port = free_port()
    daemon = None
    try:
        with (root / 'daemon.log').open('wb') as output:
            daemon = subprocess.Popen([str(binary), '--regtest', '--offline', '--fixed-difficulty', '1',
                '--data-dir', str(root / 'chain'), '--rpc-bind-ip', '127.0.0.1', '--rpc-bind-port', str(port),
                '--p2p-bind-ip', '127.0.0.1', '--p2p-bind-port', str(free_port()), '--no-zmq',
                '--non-interactive', '--disable-dns-checkpoints', '--check-updates', 'disabled',
                '--rpc-ssl', 'disabled', '--max-concurrency', '1', '--log-file', str(root / 'daemon-native.log')],
                stdout=output, stderr=subprocess.STDOUT)
        endpoint = f'http://127.0.0.1:{port}'
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        ready = False
        for _ in range(100):
            if daemon.poll() is not None:
                raise RuntimeError('Offline fixture daemon failed')
            try:
                req = urllib.request.Request(endpoint + '/json_rpc', data=b'{"jsonrpc":"2.0","id":"test","method":"get_info"}', headers={'Content-Type':'application/json'})
                with opener.open(req, timeout=2) as response:
                    info = json.load(response)['result']
                if not info['offline']:
                    raise RuntimeError('Fixture daemon is not offline')
                ready = True
                break
            except (OSError, KeyError):
                time.sleep(.1)
        if not ready:
            raise RuntimeError('Fixture startup timed out')
        with (root / 'core-test.log').open('wb') as output:
            result = subprocess.run(['mvn', '-q', '-Dmaven.gitcommitid.nativegit=true', '-DskipTests=false',
                '-Dtest=MoneroJniAcceptanceTests,MoneroKeysTests,MoneroWalletServiceTests,MoneroActivationReaderTests,CrossChainMoneroResourceTests,MoneroApiJerseyTests', f'-Dqortium.xmr.regtestDaemon={endpoint}', 'test'],
                cwd=repo, stdout=output, stderr=subprocess.STDOUT, timeout=240)
        if result.returncode != 0:
            raise RuntimeError('Core JNI acceptance failed; inspect evidence directory')
        log = (root / 'core-test.log').read_text(errors='replace')
        fixtures = json.loads((repo / 'src/test/resources/monero/derivation-v1.json').read_text())['fixtures']
        for fixture in fixtures:
            for key in ('masterSeed', 'coinSeed', 'spend', 'view', 'address'):
                if fixture[key] in log:
                    raise RuntimeError('Synthetic wallet material leaked into Core test log')
        print('PASS Core JNI fixtures, scan, encrypted reopen, isolation, and Core log containment')
    finally:
        if daemon is not None and daemon.poll() is None:
            daemon.terminate()
            try:
                daemon.wait(timeout=15)
            except subprocess.TimeoutExpired:
                daemon.kill()
                daemon.wait(timeout=5)
        print('Evidence:', root)


if __name__ == '__main__':
    main()
