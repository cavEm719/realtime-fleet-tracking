"""HTTP entry point: POST /copilot/chat keeps the contract the evaluation harness uses."""
from contextlib import asynccontextmanager

import grpc
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, StrictInt, field_validator
from redis.asyncio import Redis

from .memory import ConversationStore
from .model import DeepSeekModel
from .ratelimit import QuestionLimiter
from .service import Copilot, HttpError
from .settings import Settings
from .tools import Tools


class ChatRequest(BaseModel):
    tenantId: StrictInt = Field(gt=0)
    userId: str = Field(min_length=1, max_length=80)
    conversationId: str | None = Field(default=None, min_length=1, max_length=80)
    message: str = Field(min_length=1, max_length=4000)

    @field_validator("userId", "message")
    @classmethod
    def not_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("must not be blank")
        return value


def create_app(settings: Settings | None = None, copilot: Copilot | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if copilot is not None:
            app.state.copilot = copilot
            yield
            return
        config = settings or Settings.from_env()
        redis = Redis.from_url(config.redis_url)
        channel = grpc.aio.insecure_channel(config.api_target)
        model = DeepSeekModel(config.api_key, config.base_url, config.model) if config.api_key else None
        app.state.copilot = Copilot(
            config, model, Tools(channel, config.api_timeout_s, config.max_radius_m),
            ConversationStore(redis, config.memory_ttl_s, config.memory_max_turns),
            QuestionLimiter(redis, config.token_bucket_lua),
        )
        yield
        await channel.close()
        await redis.aclose()

    app = FastAPI(title="Fleet copilot", lifespan=lifespan)

    @app.exception_handler(HttpError)
    async def http_error(request: Request, error: HttpError):
        return JSONResponse({"error": error.message, **error.extra}, error.status, error.headers)

    @app.exception_handler(RequestValidationError)
    async def invalid(request: Request, error: RequestValidationError):
        return JSONResponse({"error": "Invalid request"}, 400)

    @app.post("/copilot/chat")
    async def chat(body: ChatRequest, request: Request):
        return await request.app.state.copilot.chat(body.tenantId, body.conversationId, body.message)

    @app.get("/actuator/health")
    async def health():
        return {"status": "UP"}

    return app


app = create_app()
