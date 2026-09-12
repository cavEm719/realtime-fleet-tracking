"""Per-company question quota shared by all copilot instances (F4.2, N6.2)."""
from pathlib import Path


class QuestionLimiter:
    def __init__(self, redis, lua_path: str):
        # Same token-bucket script and key as the API's Java limiter.
        self._script = redis.register_script(Path(lua_path).read_text())

    async def acquire(self, tenant: int, per_minute: int) -> tuple[bool, int]:
        allowed, retry_ms = await self._script(
            keys=[f"tenant:{tenant}:rate:llm"], args=[per_minute, 1, 60_000]
        )
        return int(allowed) == 1, int(retry_ms)
