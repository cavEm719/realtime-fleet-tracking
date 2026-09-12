"""Environment configuration; defaults match the requirements (N6.1–N6.3)."""
import os
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path


def _lua_default() -> str:
    repo = Path(__file__).resolve().parents[2] / "common/src/main/resources/lua/token-bucket.lua"
    return str(repo if repo.exists() else Path("/app/lua/token-bucket.lua"))


@dataclass(frozen=True)
class Settings:
    api_key: str = ""
    base_url: str = "https://api.deepseek.com"
    model: str = "deepseek-flash"
    api_target: str = "localhost:9095"
    redis_url: str = "redis://localhost:6379/0"
    timeout_s: float = 30.0
    api_timeout_s: float = 5.0
    max_tool_calls: int = 8
    max_result_bytes: int = 12_000
    max_context_bytes: int = 256_000
    max_radius_m: float = 50_000
    memory_ttl_s: int = 1800
    memory_max_turns: int = 6
    fixed_now: datetime | None = None
    evaluation_enabled: bool = False
    token_bucket_lua: str = ""

    @staticmethod
    def from_env() -> "Settings":
        env = os.environ.get
        fixed = env("COPILOT_FIXED_NOW", "")
        return Settings(
            api_key=env("DEEPSEEK_API_KEY", ""),
            base_url=env("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
            model=env("COPILOT_MODEL", "") or "deepseek-flash",
            api_target=env("API_GRPC_TARGET", "localhost:9095"),
            redis_url=env("REDIS_URL", f"redis://{env('REDIS_HOST', 'localhost')}:6379/0"),
            fixed_now=datetime.fromisoformat(fixed) if fixed else None,
            evaluation_enabled=env("COPILOT_EVALUATION_ENABLED", "false") == "true",
            token_bucket_lua=env("TOKEN_BUCKET_LUA", "") or _lua_default(),
        )
