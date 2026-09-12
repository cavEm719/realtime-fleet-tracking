"""Per-request state: company, deadline, tool budget and what the tools found."""
import time
from dataclasses import dataclass, field
from datetime import datetime

from .transparency import Basis


class DeadlineExceeded(Exception):
    pass


class ToolLimit(Exception):
    pass


@dataclass
class Turn:
    tenant_id: int  # chosen by the server from the request, never by the model
    deadline: float  # time.monotonic() value
    max_tool_calls: int
    now: datetime
    question: str
    calls: int = 0
    clarification: str | None = None
    api_unavailable: bool = False
    replica_delayed: bool = False
    basis: list[Basis] = field(default_factory=list)

    def remaining(self) -> float:
        left = self.deadline - time.monotonic()
        if left <= 0:
            raise DeadlineExceeded()
        return left

    def claim(self) -> None:
        self.remaining()
        self.calls += 1
        if self.calls > self.max_tool_calls:
            raise ToolLimit()

    @property
    def data_unavailable(self) -> bool:
        return self.api_unavailable or self.replica_delayed
