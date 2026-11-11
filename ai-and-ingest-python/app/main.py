
"""Servicio Python del proyecto (FastAPI). Unico punto que llama a la API de Claude.

Arranque (desde ai-and-ingest-python/):
    uvicorn app.main:app --reload --port 8000
"""

from __future__ import annotations

from pathlib import Path

from dotenv import load_dotenv

load_dotenv()  # Carga ANTHROPIC_API_KEY desde .env (que NUNCA se sube a git)

from fastapi import FastAPI, HTTPException  # noqa: E402
from fastapi.middleware.cors import CORSMiddleware  # noqa: E402
from pydantic import BaseModel  # noqa: E402

from app.advisor_llm.claude_engine import explicar_venta  # noqa: E402
from app.advisor_llm.pledge_explainer import explicar_estado  # noqa: E402
from app.advisor_llm.pledge_extractor import (  # noqa: E402
    ExtraccionFallida,
    extraer_terminos,
)

CONTRATO_MOCK = (
    Path(__file__).resolve().parents[2]
    / "bank-simulator-mocks"
    / "documents-pdf"
    / "contrato_pignoracion_mock.txt"
)

app = FastAPI(title="wealth-ai-service")

app.add_middleware(
    CORSMiddleware,
    allow_origin_regex=r"http://(localhost|127\.0\.0\.1)(:\d+)?",
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)


class ContratoTexto(BaseModel):
    texto: str


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


# ----------------------------------------------------------------
# Modulo 3: IRPF
# ----------------------------------------------------------------

@app.post("/explain/irpf-sale")
def explain_irpf_sale(datos: dict) -> dict:
    """Recibe el JSON de POST /api/v1/irpf/{id}/simulate-sale
    (backend Java) y lo explica.
    """
    return explicar_venta(datos)


# ----------------------------------------------------------------
# Modulo 4: Pignoracion / LTV
# ----------------------------------------------------------------

@app.get("/contracts/pledge-sample")
def pledge_sample() -> dict:
    """Devuelve el contrato mock para el boton 'Cargar contrato de ejemplo'."""

    if not CONTRATO_MOCK.exists():
        raise HTTPException(
            status_code=404,
            detail=f"No existe el contrato de ejemplo: {CONTRATO_MOCK}",
        )

    return {"texto": CONTRATO_MOCK.read_text(encoding="utf-8")}


@app.post("/extract/pledge-contract")
def extract_pledge_contract(body: ContratoTexto) -> dict:
    """Extrae los terminos estructurados del contrato mediante Claude."""

    try:
        return extraer_terminos(body.texto)
    except ExtraccionFallida as e:
        raise HTTPException(
            status_code=422,
            detail={"problemas": e.problemas},
        )


@app.post("/explain/pledge-status")
def explain_pledge_status(datos: dict) -> dict:
    """Recibe el estado LTV del backend Java y genera una explicacion."""

    return explicar_estado(datos)
