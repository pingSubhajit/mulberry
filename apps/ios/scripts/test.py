#!/usr/bin/env python3
"""Run core + HTTP tests on an isolated server. Never pollute the manual lab."""
import os
from pathlib import Path
import subprocess
import sys
import threading
from http.server import ThreadingHTTPServer

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / 'mock'))
from server import Store, handler

subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(root / 'mock'), '-v'], check=True)
server = ThreadingHTTPServer(('127.0.0.1', 0), handler(Store(':memory:')))
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    env = {**os.environ, 'MULBERRY_TEST_SERVER': f'http://127.0.0.1:{server.server_port}'}
    env.setdefault('DEVELOPER_DIR', '/Applications/Xcode.app/Contents/Developer')
    subprocess.run(['swift', 'test', '--package-path', str(root)], env=env, check=True)
finally:
    server.shutdown()
    server.server_close()
    thread.join()
