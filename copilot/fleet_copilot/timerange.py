"""Operator time windows are converted in code; the model never computes epoch values."""
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

MAX_RANGE_MS = 366 * 86_400_000


def iso(ms: int) -> str:
    """Same text as Java's Instant.toString for millisecond values."""
    moment = datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    text = moment.strftime("%Y-%m-%dT%H:%M:%S")
    return text + (f".{ms % 1000:03d}" if ms % 1000 else "") + "Z"


def _ms(moment: datetime) -> int:
    return int(moment.timestamp() * 1000)


@dataclass(frozen=True)
class TimeRange:
    start: int
    end: int

    @property
    def from_utc(self) -> str:
        return iso(self.start)

    @property
    def to_utc(self) -> str:
        return iso(self.end)


def resolve(window: str | None, from_utc: str | None, to_utc: str | None, now: datetime) -> TimeRange:
    if window is not None:
        if from_utc is not None or to_utc is not None:
            raise ValueError("Supply window OR UTC instants")
        midnight = now.astimezone(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
        match window:
            case "yesterday":
                start, end = _ms(midnight - timedelta(days=1)), _ms(midnight) - 1
            case "today":
                start, end = _ms(midnight), _ms(now)
            case "thisWeek":
                start, end = _ms(midnight - timedelta(days=midnight.weekday())), _ms(now)
            case "last24h":
                start, end = _ms(now - timedelta(days=1)), _ms(now) - 1
            case "last7days":
                start, end = _ms(now - timedelta(days=7)), _ms(now) - 1
            case _:
                raise ValueError("Unknown UTC window")
    else:
        if from_utc is None or to_utc is None:
            raise ValueError("Both UTC instants are required")
        try:
            first, last = datetime.fromisoformat(from_utc), datetime.fromisoformat(to_utc)
        except (TypeError, ValueError) as error:
            raise ValueError("Use ISO-8601 UTC instants") from error
        if first.tzinfo is None or last.tzinfo is None:
            raise ValueError("Use ISO-8601 UTC instants")
        start, end = _ms(first), _ms(last)
    if start < 0 or end < start or end - start > MAX_RANGE_MS:
        raise ValueError("Time range must be within 366 days")
    return TimeRange(start, end)
