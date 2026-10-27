import json
import threading
import unittest
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from http.server import ThreadingHTTPServer
from server import Store, handler


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler(Store(":memory:")))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def testPublishedRevisionsAreImmutableAndNotCached(self):
        with urlopen(self.base + "/canvas/latest") as response:
            first = json.load(response)
            self.assertEqual(response.headers["Cache-Control"], "no-store")
        original = urlopen(self.base + first["imagePath"]).read()
        with urlopen(Request(self.base + "/publish", method="POST")) as response:
            second = json.load(response)
        self.assertEqual(second["revision"], first["revision"] + 1)
        self.assertEqual(original, urlopen(self.base + first["imagePath"]).read())
        self.assertNotEqual(original, urlopen(self.base + second["imagePath"]).read())
        self.assertTrue(original.startswith(b"\x89PNG\r\n\x1a\n"))

    def testEventsDoNotInventWallpaperSuccess(self):
        event = {"stage": "image_ready", "revision": 1}
        urlopen(Request(self.base + "/events", data=json.dumps(event).encode(), method="POST")).close()
        self.assertEqual(json.load(urlopen(self.base + "/events")), [event])
        with self.assertRaises(HTTPError) as error:
            urlopen(Request(self.base + "/events", data=b'{}', method="POST"))
        self.assertEqual(error.exception.code, 400)
        error.exception.close()


if __name__ == "__main__":
    unittest.main()
