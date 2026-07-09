from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from typing import Any

from .manager import PowerNetworkModel


class PowerNetworkHttpHandler(BaseHTTPRequestHandler):
    model = PowerNetworkModel()

    def do_GET(self) -> None:
        if self.path == "/health":
            self._write_json({"status": "UP"})
            return
        if self.path == "/power-network/state":
            self._write_json(self.model.snapshot())
            return
        if self.path == "/power-network/events":
            self._write_json(self.model.event_list())
            return
        if self.path == "/power-network/topology":
            self._write_json(self.model.topology())
            return
        self.send_error(404, "Not found")

    def do_POST(self) -> None:
        if self.path == "/power-network/bootstrap":
            payload = self._read_json()
            self._write_json(self.model.bootstrap(payload))
            return
        if self.path == "/power-network/operations":
            payload = self._read_json()
            self._write_json(self.model.operate(payload))
            return
        if self.path == "/power-network/state/query":
            payload = self._read_json()
            self._write_json(self.model.query_state(payload))
            return
        self.send_error(404, "Not found")

    def _read_json(self) -> dict[str, Any]:
        content_length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(content_length) if content_length > 0 else b"{}"
        return json.loads(raw_body.decode("utf-8"))

    def _write_json(self, payload: Any) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:
        return


def main() -> None:
    server = ThreadingHTTPServer(("127.0.0.1", 9200), PowerNetworkHttpHandler)
    print("External power network service listening on http://127.0.0.1:9200")
    server.serve_forever()


if __name__ == "__main__":
    main()
