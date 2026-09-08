from dataclasses import asdict, dataclass, field
import math
from typing import Protocol


@dataclass(frozen=True)
class EmbeddingConfig:
    model: str = 'text-embedding-3-small'
    dimensions: int | None = None

    def __post_init__(self):
        if not isinstance(self.model, str) or not self.model.strip():
            raise ValueError('Modèle de vectorisation requis')
        if self.dimensions is not None and (type(self.dimensions) is not int or self.dimensions < 1):
            raise ValueError('dimensions doit être un entier positif')

    def to_dict(self):
        return asdict(self)


@dataclass
class EmbeddingBatch:
    """Vecteurs dans le même ordre que les textes fournis."""
    vectors: list[list[float]]
    model: str
    usage: dict = field(default_factory=dict)


class Vectorizer(Protocol):
    provider: str
    config: EmbeddingConfig

    def embed(self, texts: list[str]) -> EmbeddingBatch: ...


def validate_vectors(vectors, count, dimensions=None):
    if not isinstance(vectors, list) or len(vectors) != count or not vectors:
        raise ValueError('Nombre de vecteurs incohérent')
    width = dimensions
    for vector in vectors:
        if not isinstance(vector, list) or not vector:
            raise ValueError('Vecteur vide ou invalide')
        if width is None:
            width = len(vector)
        if len(vector) != width:
            raise ValueError('Dimensions des vecteurs incohérentes')
        if any(type(x) not in (int, float) or not math.isfinite(x) for x in vector):
            raise ValueError('Les vecteurs doivent contenir des nombres finis')
        norm = math.hypot(*vector)
        if not math.isfinite(norm) or norm == 0:
            raise ValueError('Norme de vecteur nulle ou non finie')
    return width
