"""Compatibility entry point for the FastAPI power-network simulator."""

import uvicorn

from .main import app


def main() -> None:
    uvicorn.run(app, host="127.0.0.1", port=9200)


if __name__ == "__main__":
    main()
