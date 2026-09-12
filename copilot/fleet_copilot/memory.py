"""Conversation history in Redis, so a follow-up can be answered by any copilot instance (F5.8)."""
import asyncio
import json
import secrets
import time

_RELEASE = """
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
return 0
"""


class ConversationBusy(Exception):
    """Another request in the same conversation did not finish before this one's deadline."""


class ConversationStore:
    def __init__(self, redis, ttl_s: int, max_turns: int):
        self.redis = redis
        self.ttl_ms = ttl_s * 1000
        self.max_turns = max_turns
        self._release = redis.register_script(_RELEASE)

    @staticmethod
    def _key(tenant: int, conversation: str) -> str:
        # The tenant is part of the key: a conversation ID never reaches another company's history.
        return f"copilot:conversation:{tenant}:{conversation}"

    async def turns(self, tenant: int, conversation: str) -> list[list[dict]]:
        stored = await self.redis.get(self._key(tenant, conversation))
        return json.loads(stored) if stored else []

    async def append(self, tenant: int, conversation: str, turn: list[dict]) -> None:
        turns = (await self.turns(tenant, conversation)) + [turn]
        await self.redis.set(
            self._key(tenant, conversation), json.dumps(turns[-self.max_turns:]), px=self.ttl_ms
        )

    async def lock(self, tenant: int, conversation: str, deadline: float) -> str:
        """Serialize turns of one conversation across instances; wait at most until the deadline."""
        key, token = self._key(tenant, conversation) + ":lock", secrets.token_hex(16)
        while True:
            remaining_ms = int((deadline - time.monotonic()) * 1000)
            if remaining_ms <= 0:
                raise ConversationBusy()
            if await self.redis.set(key, token, nx=True, px=remaining_ms + 1000):
                return token
            await asyncio.sleep(0.05)

    async def unlock(self, tenant: int, conversation: str, token: str) -> None:
        await self._release(keys=[self._key(tenant, conversation) + ":lock"], args=[token])
