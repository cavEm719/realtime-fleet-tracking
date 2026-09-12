"""Model calls through the official OpenAI SDK pointed at DeepSeek's compatible endpoint."""
from dataclasses import dataclass
from typing import Protocol


@dataclass(frozen=True)
class Completion:
    message: dict  # assistant message: role, content, optional tool_calls
    input_tokens: int
    output_tokens: int
    usage_unknown: bool


class TransientModelError(Exception):
    """Rate limiting, a 5xx response, a timeout or a dropped connection: worth one retry."""


class ModelError(Exception):
    """A non-retryable provider failure."""


class Model(Protocol):
    async def complete(self, messages: list[dict], tools: list[dict], timeout_s: float) -> Completion: ...


class DeepSeekModel:
    def __init__(self, api_key: str, base_url: str, model: str):
        import openai

        self._openai = openai
        # Retries are done by the caller within the request deadline.
        self.client = openai.AsyncOpenAI(api_key=api_key, base_url=base_url, max_retries=0)
        self.model = model

    async def complete(self, messages, tools, timeout_s):
        errors = self._openai
        try:
            response = await self.client.chat.completions.create(
                model=self.model, messages=messages, tools=tools, temperature=0, timeout=timeout_s
            )
        except (errors.RateLimitError, errors.InternalServerError,
                errors.APITimeoutError, errors.APIConnectionError) as error:
            raise TransientModelError(type(error).__name__) from error
        except errors.OpenAIError as error:
            raise ModelError(type(error).__name__) from error
        choice = response.choices[0].message
        message = {"role": "assistant", "content": choice.content or ""}
        if choice.tool_calls:
            message["tool_calls"] = [
                {"id": c.id, "type": "function",
                 "function": {"name": c.function.name, "arguments": c.function.arguments}}
                for c in choice.tool_calls
            ]
        usage = response.usage
        return Completion(
            message,
            usage.prompt_tokens if usage else 0,
            usage.completion_tokens if usage else 0,
            usage is None,
        )
