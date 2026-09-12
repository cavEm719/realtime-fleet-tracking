# Build context: repository root (shares the proto and the token-bucket script with Java).
FROM python:3.12-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.5.11 /uv /bin/uv
WORKDIR /app
COPY copilot/pyproject.toml copilot/uv.lock copilot/.python-version copilot/gen_proto.py ./
COPY common/src/main/proto/fleet_data.proto proto/
RUN uv sync --frozen --no-install-project
COPY copilot/fleet_copilot fleet_copilot
RUN uv run python gen_proto.py && rm -rf .venv && uv sync --frozen --no-dev --no-install-project

FROM python:3.12-slim
WORKDIR /app
COPY --from=build /app/.venv .venv
COPY --from=build /app/fleet_copilot fleet_copilot
COPY common/src/main/resources/lua/token-bucket.lua lua/
ENV TOKEN_BUCKET_LUA=/app/lua/token-bucket.lua
USER 10001
EXPOSE 8080
CMD [".venv/bin/uvicorn", "fleet_copilot.app:app", "--host", "0.0.0.0", "--port", "8080"]
