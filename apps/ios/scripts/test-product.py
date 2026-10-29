#!/usr/bin/env python3
"""Run the shared ProductCore tests against an isolated backend with an in-memory database."""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time

root = Path(__file__).resolve().parents[3]
with tempfile.TemporaryDirectory(prefix='mulberry-contract-') as directory:
    ready = Path(directory) / 'ready.json'
    with (Path(directory) / 'server.log').open('w+') as log:
        server = subprocess.Popen(
            ['pnpm', '--dir', str(root / 'apps/backend'), 'exec', 'tsx', 'test/ios-contract-server.ts', str(ready)],
            cwd=root, stdout=log, stderr=log, start_new_session=True,
        )
        try:
            deadline = time.monotonic() + 30
            while not ready.exists():
                if server.poll() is not None or time.monotonic() >= deadline:
                    log.seek(0)
                    raise RuntimeError('Isolated contract server did not start:\n' + log.read())
                time.sleep(0.1)
            env = dict(os.environ)
            env['MULBERRY_CONTRACT_SERVER'] = json.loads(ready.read_text())['url']
            env.setdefault('DEVELOPER_DIR', '/Applications/Xcode.app/Contents/Developer')
            subprocess.run(['swift', 'test', '--package-path', str(root / 'packages/apple'), '--filter', 'ProductCoreTests'], env=env, check=True)
        finally:
            try:
                os.killpg(server.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(server.pid, signal.SIGKILL)
                server.wait()
