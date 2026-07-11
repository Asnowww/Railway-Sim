# syntax=docker/dockerfile:1.7
FROM python:3.12-slim@sha256:423ed6ab25b1921a477529254bfeeabf5855151dc2c3141699a1bfc852199fbf

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY power-network-service/requirements.txt /app/requirements.txt
RUN python -m pip install --no-cache-dir -r /app/requirements.txt
COPY power-network-service/app /app/app
COPY config/power_third_rail.yaml /config/power_third_rail.yaml

EXPOSE 9200
HEALTHCHECK --interval=5s --timeout=2s --retries=10 \
  CMD curl -fsS http://127.0.0.1:9200/health || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9200", "--workers", "1"]
