from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from typing import Any

from .fmu_manager import FmuManager
from .input_mapper import InputMapper
from .output_mapper import OutputMapper


class FmuHttpHandler(BaseHTTPRequestHandler):
    manager = FmuManager()
    input_mapper = InputMapper()
    output_mapper = OutputMapper()

    def do_GET(self) -> None:
        if self.path == "/health":
            self._write_json({"status": "UP"})
            return
        self.send_error(404, "Not found")

    def do_POST(self) -> None:
        if self.path not in {"/step-fleet", "/api/fleet/step"}:
            self.send_error(404, "Not found")
            return

        try:
            payload = self._read_json()
            request = self.input_mapper.from_payload(payload)
            response = self.manager.step_fleet(request)
            self._write_json(self.output_mapper.to_payload(response))
        except (KeyError, TypeError, ValueError) as exc:
            self.send_error(400, f"Invalid request: {exc}")

    def _read_json(self) -> dict[str, Any]:
        content_length = int(self.headers.get("Content-Length", "0"))
        if content_length > 0:
            raw_body = self.rfile.read(content_length)
        elif self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            raw_body = self._read_chunked_body()
        else:
            raw_body = b""
        return json.loads(raw_body.decode("utf-8"))

    def _read_chunked_body(self) -> bytes:
        chunks: list[bytes] = []
        while True:
            size_line = self.rfile.readline().strip()
            if not size_line:
                continue
            chunk_size = int(size_line.split(b";", 1)[0], 16)
            if chunk_size == 0:
                self.rfile.readline()
                break
            chunks.append(self.rfile.read(chunk_size))
            self.rfile.readline()
        return b"".join(chunks)

    def _write_json(self, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:
        return


def main() -> None:
    server = ThreadingHTTPServer(("127.0.0.1", 9000), FmuHttpHandler)
    print("FMU vehicle physics service listening on http://127.0.0.1:9000")
    server.serve_forever()


if __name__ == "__main__":
    main()
