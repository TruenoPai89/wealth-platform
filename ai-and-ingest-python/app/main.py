"""Servicio Python del proyecto (FastAPI). Unico punto que llama a la API de Claude.

Arranque (desde ai-and-ingest-python/):
    uvicorn app.main:app --reload --port 8000
"""
from __future__ import annotations

from dotenv import load_dotenv

load_dotenv()  # carga ANTHROPIC_API_KEY desde .env (que NUNCA se sube a git)

from fastapi import FastAPI  # noqa: E402
from fastapi.middleware.cors import CORSMiddleware  # noqa: E402

from app.advisor_llm.claude_engine import explicar_venta  # noqa: E402

app = FastAPI(title="wealth-ai-service")

app.add_middleware(
    CORSMiddleware,
    allow_origin_regex=r"http://(localhost|127\.0\.0\.1)(:\d+)?",
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/explain/irpf-sale")
def explain_irpf_sale(datos: dict) -> dict:
    """Recibe el JSON de POST /api/v1/irpf/{id}/simulate-sale (backend Java) y lo explica."""
    return explicar_venta(datos)
