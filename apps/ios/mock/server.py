#!/usr/bin/env python3
"""Isolated delivery lab. No production database, Google, Firebase, or env needed."""
import argparse
import json
import math
from pathlib import Path
import sqlite3
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
import zlib


def render_png(revision, width=390, height=844):
    pixels = bytearray(width * height * 3)
    colors = [(253, 225, 231), (223, 239, 255), (227, 246, 220)]
    bg = colors[(revision - 1) % len(colors)]
    for y in range(height):
        for x in range(width):
            i = (y * width + x) * 3
            pixels[i:i+3] = bytes(max(0, c - y // 70) for c in bg)

    def dot(x, y, radius, color):
        for yy in range(max(0, int(y-radius)), min(height, int(y+radius+1))):
            for xx in range(max(0, int(x-radius)), min(width, int(x+radius+1))):
                if (xx-x)**2 + (yy-y)**2 <= radius**2:
                    index = (yy * width + xx) * 3
                    pixels[index:index+3] = bytes(color)

    def line(x1, y1, x2, y2, radius=4, color=(125, 33, 75)):
        steps = max(1, int(math.hypot(x2-x1, y2-y1)))
        for step in range(steps + 1):
            t = step / steps
            dot(x1 + (x2-x1)*t, y1 + (y2-y1)*t, radius, color)

    for step in range(1000):
        t = step * math.tau / 1000
        x = 195 + 8 * 16 * math.sin(t)**3
        y = 365 - 8 * (13*math.cos(t)-5*math.cos(2*t)-2*math.cos(3*t)-math.cos(4*t))
        dot(x, y, 4, (125, 33, 75))
    segments = [((0, 0), (1, 0)), ((1, 0), (1, 1)), ((1, 1), (1, 2)),
                ((0, 2), (1, 2)), ((0, 1), (0, 2)), ((0, 0), (0, 1)), ((0, 1), (1, 1))]
    digits = {"0": "012345", "1": "12", "2": "01643", "3": "01236", "4": "5612",
              "5": "05623", "6": "056432", "7": "012", "8": "0123456", "9": "012356"}
    text = str(revision)
    scale = min(32, 260 // max(1, len(text)*2))
    start = (width - (len(text)*1.6-.6)*scale)/2
    for i, digit in enumerate(text):
        for seg in digits[digit]:
            a, b = segments[int(seg)]
            line(start+i*scale*1.6+a[0]*scale, 570+a[1]*scale,
                 start+i*scale*1.6+b[0]*scale, 570+b[1]*scale)
    # Bars make revisions distinguishable even when a clock covers the top.
    for i in range(revision % 12 + 1):
        line(45+i*25, 735, 45+i*25, 755, radius=3)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    raw = b"".join(b"\0" + pixels[y*width*3:(y+1)*width*3] for y in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


class Store:
    def __init__(self, location):
        self.db = sqlite3.connect(location, check_same_thread=False)
        self.lock = threading.Lock()
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS canvases (revision INTEGER PRIMARY KEY, published_at TEXT NOT NULL, png BLOB NOT NULL);
            CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY, received_at REAL NOT NULL, payload TEXT NOT NULL);
        """)
        if not self.db.execute("SELECT 1 FROM canvases LIMIT 1").fetchone():
            self.publish()

    @staticmethod
    def manifest(row):
        return {"revision": row[0], "publishedAt": row[1], "imagePath": f"/canvas/{row[0]}.png"}

    def publish(self):
        with self.lock:
            revision = self.db.execute("SELECT COALESCE(MAX(revision),0)+1 FROM canvases").fetchone()[0]
            timestamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            self.db.execute("INSERT INTO canvases VALUES (?,?,?)", (revision, timestamp, render_png(revision)))
            self.db.commit()
            return self.manifest((revision, timestamp))

    def latest(self):
        with self.lock:
            return self.manifest(self.db.execute("SELECT revision,published_at FROM canvases ORDER BY revision DESC LIMIT 1").fetchone())

    def image(self, revision):
        with self.lock:
            row = self.db.execute("SELECT png FROM canvases WHERE revision=?", (revision,)).fetchone()
            return row[0] if row else None

    def add_event(self, event):
        with self.lock:
            self.db.execute("INSERT INTO events(received_at,payload) VALUES (?,?)", (time.time(), json.dumps(event)))
            self.db.commit()

    def events(self):
        with self.lock:
            return [json.loads(row[0]) for row in self.db.execute("SELECT payload FROM events ORDER BY id DESC LIMIT 500")]


def handler(store):
    class Handler(BaseHTTPRequestHandler):
        def respond(self, status, body, content_type="application/json"):
            if content_type == "application/json":
                body = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            route = urlparse(self.path).path
            if route == "/health":
                self.respond(200, {"status": "ok", "mode": "mock"})
            elif route == "/canvas/latest":
                self.respond(200, store.latest())
            elif route.startswith("/canvas/") and route.endswith(".png"):
                try:
                    image = store.image(int(route.split("/")[-1][:-4]))
                except ValueError:
                    image = None
                self.respond(200, image, "image/png") if image else self.respond(404, {"error": "revision not found"})
            elif route == "/events":
                self.respond(200, store.events())
            else:
                self.respond(404, {"error": "not found"})

        def do_POST(self):
            if self.path == "/publish":
                self.respond(201, store.publish())
            elif self.path == "/events":
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 32768:
                        raise ValueError()
                    event = json.loads(self.rfile.read(size))
                    if not isinstance(event, dict) or not isinstance(event.get("stage"), str):
                        raise ValueError()
                    store.add_event(event)
                    self.respond(201, {"accepted": True})
                except (ValueError, json.JSONDecodeError):
                    self.respond(400, {"error": "invalid event"})
            else:
                self.respond(404, {"error": "not found"})
    return Handler


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--database", default=":memory:")
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), handler(Store(args.database)))
    print(f"Mock delivery server: http://{args.host}:{args.port} (no production services)", flush=True)
    server.serve_forever()
