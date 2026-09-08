from dataclasses import asdict, dataclass, field
from typing import Any, Literal, Protocol

from hs_matching.catalog import Catalog

Status = Literal['ok', 'needs_info', 'abstained', 'error']


@dataclass
class Candidate:
    code: str
    rank: int
    description: str
    score: float | None = None
    score_type: str = 'none'
    explanation: str | None = None
    references: list[str] = field(default_factory=list)


@dataclass
class Prediction:
    status: Status
    candidates: list[Candidate] = field(default_factory=list)
    missing_information: list[str] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)
    error: dict[str, Any] | None = None

    def to_dict(self):
        return asdict(self)


@dataclass(frozen=True)
class PredictionContext:
    catalog: Catalog
    edition: str = '2022'
    language: str = 'en'


class Approach(Protocol):
    def predict(self, query: str, top_k: int, context: PredictionContext) -> Prediction: ...
