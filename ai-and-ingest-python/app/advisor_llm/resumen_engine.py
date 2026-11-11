"""Explicacion en lenguaje natural del resumen fiscal del ejercicio (Modulo 3), con Claude.

Mismas reglas que la explicacion de una venta (claude_engine.py):
  - Claude NO calcula: recibe el JSON de POST /api/v1/irpf/{id}/annual-report y lo explica.
  - Cada cifra del texto se verifica contra ese JSON (verificador_cifras.py).
  - Si la API falla o la explicacion no pasa la verificacion dos veces, texto de plantilla.
"""
from __future__ import annotations

import json
import os

from app.advisor_llm.claude_engine import _eur
from app.advisor_llm.verificador_cifras import cifras_no_respaldadas

MODELO = os.environ.get("CLAUDE_MODEL", "claude-haiku-5-5")
MAX_INTENTOS = 2

PROMPT_SISTEMA = """\
Eres el asistente fiscal de una plataforma de inversion. Explicas a un cliente particular,
en espanol claro y en 3 o 4 frases, el resumen de las ventas de fondos y ETFs de su ejercicio
para la declaracion de la renta.

Reglas estrictas:
- Usa SOLO importes que aparezcan en el JSON. No calcules, no sumes, no redondees a otras cifras.
- Escribe los importes en euros con formato espanol (1.140,00 EUR).
- No escribas fechas, numeros de operaciones, plazos en anos ni porcentajes.
- Explica: ganancias y perdidas del ano, si se ha compensado algo de anos anteriores
  (result.carryForwardUsed), la base del ahorro (result.taxableBase) y la cuota (result.tax).
- Si deferredLosses es mayor que 0, explica que esa perdida no computa este ano por la regla
  de los 2 meses.
- Si result.netBalance es negativo, explica que no hay cuota y que esa perdida queda pendiente
  para compensar en ejercicios siguientes.
- No recomiendes comprar ni vender. Termina recordando que es un borrador orientativo de la parte
  de inversiones, no la declaracion completa ni asesoramiento fiscal.
- No menciones codigos ISIN ni nombres de campos del JSON.
"""


def explicacion_plantilla(datos: dict) -> str:
    """Texto determinista de respaldo (sin IA)."""
    r = datos["result"]
    partes = [f"Este ano tus ventas suman {_eur(datos['totalGains'])} de ganancias "
              f"y {_eur(datos['totalLosses'])} de perdidas"]
    if float(datos.get("deferredLosses") or 0) > 0:
        partes.append(f"{_eur(datos['deferredLosses'])} de perdidas no computan por la regla de los 2 meses")
    if float(r["netBalance"]) < 0:
        partes.append(f"el saldo neto es de {_eur(r['netBalance'])}, asi que no hay cuota y esa perdida "
                      "queda pendiente para ejercicios siguientes")
    else:
        if float(r["carryForwardUsed"]) > 0:
            partes.append(f"se compensan {_eur(r['carryForwardUsed'])} de anos anteriores")
        partes.append(f"la base del ahorro queda en {_eur(r['taxableBase'])} y la cuota en {_eur(r['tax'])}")
    return ("; ".join(partes) + ". Es un borrador orientativo de la parte de inversiones, "
            "no la declaracion completa ni asesoramiento fiscal.")


def _llamar_claude(datos: dict, aviso: str | None) -> str:
    import anthropic  # import aqui: los tests de la plantilla no necesitan el SDK

    client = anthropic.Anthropic()  # lee ANTHROPIC_API_KEY del entorno
    contenido = "Resumen del motor fiscal (JSON):\n" + json.dumps(datos, ensure_ascii=False)
    if aviso:
        contenido += "\n\n" + aviso
    respuesta = client.messages.create(
        model=MODELO,
        max_tokens=2000,  # Haiku razona antes de escribir: con menos se queda sin texto
        system=PROMPT_SISTEMA,
        messages=[{"role": "user", "content": contenido}],
    )
    return "".join(b.text for b in respuesta.content if b.type == "text").strip()


def explicar_resumen(datos: dict, llamar=None) -> dict:
    """Devuelve {"texto", "fuente": "claude" | "plantilla", "intentos"}.

    llamar se resuelve al llamar (no al definir) para que los tests puedan sustituirlo.
    """
    llamar = llamar or _llamar_claude
    aviso = None
    intento = 0
    for intento in range(1, MAX_INTENTOS + 1):
        try:
            texto = llamar(datos, aviso)
        except Exception as e:  # API caida, clave mala, sin saldo...
            print(f"[resumen_engine] error de API: {type(e).__name__}: {e}")
            break
        if not texto:
            aviso = "Tu respuesta anterior estaba vacia. Escribe la explicacion en 3 o 4 frases."
            continue
        malas = cifras_no_respaldadas(texto, datos)
        if not malas:
            return {"texto": texto, "fuente": "claude", "intentos": intento}
        aviso = (f"Tu respuesta anterior contenia cifras que no estan en el JSON: {malas}. "
                 "Reescribela usando solo importes del JSON y sin fechas.")
    return {"texto": explicacion_plantilla(datos), "fuente": "plantilla", "intentos": intento}
