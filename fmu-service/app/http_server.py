from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from typing import Any

from .fmu_manager import FmuManager
from .input_mapper import InputMapper
from .output_mapper import OutputMapper
from .schemas import MODEL_VERSION


class FmuHttpHandler(BaseHTTPRequestHandler):
    manager = FmuManager()
    input_mapper = InputMapper()
    output_mapper = OutputMapper()

    def do_GET(self) -> None:
        if self.path == "/health":
            self._write_json({"status": "UP", "modelVersion": MODEL_VERSION})
            return
        self._write_error(404, "NOT_FOUND", "resource not found", "")

    def do_POST(self) -> None:
        if self.path not in {"/step-fleet", "/api/fleet/step"}:
            self._write_error(404, "NOT_FOUND", "resource not found", "")
            return

        trace_id = ""
        try:
            payload = self._read_json()
            trace_id = str(payload.get("traceId", payload.get("trace_id", "")))
            request = self.input_mapper.from_payload(payload)
            response = self.manager.step_fleet(request)
            self._write_json(self.output_mapper.to_payload(response))
        except (KeyError, TypeError, ValueError) as exc:
            self._write_error(400, "INVALID_REQUEST", f"invalid request: {exc}", trace_id)

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

    def _write_json(self, payload: dict[str, Any], status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _write_error(
        self,
        status: int,
        error_code: str,
        message: str,
        trace_id: str,
    ) -> None:
        self._write_json(
            {
                "status": status,
                "errorCode": error_code,
                "message": message,
                "traceId": trace_id,
            },
            status=status,
        )

    def log_message(self, format: str, *args: Any) -> None:
        return


def main() -> None:
    server = ThreadingHTTPServer(("127.0.0.1", 9000), FmuHttpHandler)
    print("FMU vehicle physics service listening on http://127.0.0.1:9000")
    server.serve_forever()


if __name__ == "__main__":
    main()
