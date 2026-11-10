
"""Esquema de los términos que Claude extrae de un contrato de pignoración.

Dos representaciones del mismo esquema:
- TerminosPignoracion (Pydantic): valida en Python lo que devuelve Claude.
- HERRAMIENTA (JSON Schema): se envía a Claude como herramienta para
  forzar esa forma.

Las clases de activo son las mismas que en el enum AssetClass del backend Java.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, Field, model_validator


class ClaseActivo(str, Enum):
    EQUITY = "EQUITY"              # Renta variable
    FIXED_INCOME = "FIXED_INCOME"  # Renta fija
    COMMODITY = "COMMODITY"        # Materias primas
    CASH = "CASH"                  # Efectivo


class Haircut(BaseModel):
    clase: ClaseActivo
    haircut: float = Field(ge=0, le=1)
    cita: str


class TerminosPignoracion(BaseModel):
    ltv_maximo: float | None = Field(default=None, gt=0, lt=1)
    ltv_margin_call: float | None = Field(default=None, gt=0, lt=1)
    plazo_reposicion_dias_habiles: int | None = Field(default=None, ge=0)
    haircuts: list[Haircut] = Field(default_factory=list)

    cita_ltv_maximo: str | None = None
    cita_margin_call: str | None = None
    cita_plazo: str | None = None

    @model_validator(mode="after")
    def _clases_unicas(self):
        clases = [h.clase for h in self.haircuts]

        if len(clases) != len(set(clases)):
            raise ValueError("hay clases de activo repetidas en haircuts")

        return self

    def haircuts_por_clase(self) -> dict[str, float]:
        return {h.clase.value: h.haircut for h in self.haircuts}


_TEXTO_O_NULL = {"type": ["string", "null"]}

_FRACCION_O_NULL = {
    "type": ["number", "null"],
    "minimum": 0,
    "maximum": 1,
}


HERRAMIENTA = {
    "name": "registrar_terminos_pignoracion",
    "description": (
        "Registra los términos de un contrato de préstamo con garantía de valores. "
        "Cada valor debe ir acompañado de la cita literal del contrato que lo respalda."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "ltv_maximo": {
                **_FRACCION_O_NULL,
                "description": "Límite de disposición sobre el valor de garantía (70 % -> 0.70).",
            },
            "cita_ltv_maximo": _TEXTO_O_NULL,
            "ltv_margin_call": {
                **_FRACCION_O_NULL,
                "description": "Umbral que dispara el aviso de margen (80 % -> 0.80).",
            },
            "cita_margin_call": _TEXTO_O_NULL,
            "plazo_reposicion_dias_habiles": {
                "type": ["integer", "null"],
                "minimum": 0,
            },
            "cita_plazo": _TEXTO_O_NULL,
            "haircuts": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "clase": {
                            "type": "string",
                            "enum": [c.value for c in ClaseActivo],
                        },
                        "haircut": {
                            "type": "number",
                            "minimum": 0,
                            "maximum": 1,
                        },
                        "cita": {"type": "string"},
                    },
                    "required": ["clase", "haircut", "cita"],
                },
            },
        },
        "required": [
            "ltv_maximo",
            "ltv_margin_call",
            "plazo_reposicion_dias_habiles",
            "haircuts",
        ],
    },
}
