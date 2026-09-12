"""Fail fast while the model provider is failing, instead of piling retries onto it (N6.4)."""
import time


class CircuitOpen(Exception):
    pass


class CircuitBreaker:
    def __init__(self, failures: int = 5, open_s: float = 30.0, clock=time.monotonic):
        self.threshold, self.open_s, self.clock = failures, open_s, clock
        self.failures = 0
        self.opened_at: float | None = None
        self.trial = False

    @property
    def state(self) -> str:
        if self.opened_at is None:
            return "CLOSED"
        return "HALF_OPEN" if self.clock() - self.opened_at >= self.open_s else "OPEN"

    def before(self) -> None:
        state = self.state
        if state == "OPEN" or (state == "HALF_OPEN" and self.trial):
            raise CircuitOpen()
        if state == "HALF_OPEN":
            self.trial = True  # exactly one probe while half open

    def success(self) -> None:
        self.failures, self.opened_at, self.trial = 0, None, False

    def failure(self) -> None:
        self.failures += 1
        if self.trial or self.failures >= self.threshold:
            self.opened_at, self.trial = self.clock(), False
