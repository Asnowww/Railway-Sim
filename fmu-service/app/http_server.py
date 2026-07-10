"""Compatibility entry point; the production server is FastAPI on Uvicorn."""

import os

import uvicorn


def main() -> None:
    host = os.environ.get("FMU_SERVICE_HOST", "127.0.0.1")
    port = int(os.environ.get("FMU_SERVICE_PORT", "9000"))
    uvicorn.run("app.main:app", host=host, port=port, workers=1)


if __name__ == "__main__":
    main()
