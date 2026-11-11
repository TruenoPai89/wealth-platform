"""Explicacion en lenguaje natural del resultado del Modulo 3, generada con Claude.

Reglas:
  - Claude NO calcula: recibe el JSON del backend Java y solo lo explica.
  - Cada cifra del texto se verifica contra ese JSON (verificador_cifras.py).
  - Si la API falla o la explicacion no pasa la verificacion dos veces, se devuelve un texto de
    plantilla hecho en Python: la demo nunca se queda sin respuesta.
"""
from __future__ import annotations

import json
import os

from app.advisor_llm.verificador_cifras import cifras_no_respaldadas

MODELO = os.environ.get("CLAUDE_MODEL", "claude-haiku-5-5")
MAX_INTENTOS = 2

PROMPT_SISTEMA = """\
Eres el asistente fiscal de una plataforma de inversion. Explicas a un cliente particular,
en espanol claro y en 3 o 4 frases, el efecto en su IRPF de una venta que esta valorando.

Reglas estrictas:
- Usa SOLO cifras que aparezcan en el JSON. No calcules, no redondees a otras cifras, no estimes.
- Escribe los importes en euros con formato espanol (1.140,00 EUR).
- taxDelta negativo significa que la venta le AHORRA impuestos; positivo, que le cuesta.
- Si twoMonthRuleViolation es true, explica que la perdida no computa este ano por la regla
  de los 2 meses.
- No recomiendes comprar ni vender: describe el efecto. Termina recordando que es una simulacion
  orientativa, no asesoramiento fiscal.
- No menciones el codigo ISIN ni nombres de campos del JSON.
"""


def _eur(x) -> str:
    v = float(x)
    entero, dec = f"{abs(v):,.2f}".split(".")
    return f"{'-' if v < 0 else ''}{entero.replace(',', '.')},{dec} EUR"


def explicacion_plantilla(datos: dict) -> str:
    """Texto determinista de respaldo (sin IA)."""
    delta = float(datos["taxDelta"])
    antes = datos["before"]["tax"]
    despues = datos["after"]["tax"]
    if datos.get("twoMonthRuleViolation"):
        nucleo = ("La venta generaria una perdida, pero no computa este ejercicio por la regla "
                  "de los 2 meses, asi que tu cuota no cambia")
    elif delta < 0:
        nucleo = f"Con esta venta tu cuota pasaria de {_eur(antes)} a {_eur(despues)}: te ahorrarias {_eur(-delta)}"
    elif delta > 0:
        nucleo = f"Con esta venta tu cuota pasaria de {_eur(antes)} a {_eur(despues)}: pagarias {_eur(delta)} mas"
    else:
        nucleo = f"Esta venta no cambia tu cuota, que se queda en {_eur(antes)}"
    return f"{nucleo}. Es una simulacion orientativa, no asesoramiento fiscal."


def _llamar_claude(datos: dict, aviso: str | None) -> str:
    import anthropic  # import aqui: los tests de la plantilla no necesitan el SDK

    client = anthropic.Anthropic()  # lee ANTHROPIC_API_KEY del entorno
    contenido = "Resultado del motor fiscal (JSON):\n" + json.dumps(datos, ensure_ascii=False)
    if aviso:
        contenido += "\n\n" + aviso
    respuesta = client.messages.create(
        model=MODELO,
        max_tokens=2000,  # CAMBIO 1: 400 se agotaba en razonamiento interno y no dejaba texto
        system=PROMPT_SISTEMA,
        messages=[{"role": "user", "content": contenido}],
    )
    return "".join(b.text for b in respuesta.content if b.type == "text").strip()


def explicar_venta(datos: dict, llamar=_llamar_claude) -> dict:
    """Devuelve {"texto", "fuente": "claude" | "plantilla", "intentos"}."""
    aviso = None
    for intento in range(1, MAX_INTENTOS + 1):
        try:
            texto = llamar(datos, aviso)
        except Exception as e:  # API caida, clave mala, sin saldo...
            print(f"[claude_engine] error de API: {type(e).__name__}: {e}")
            break
        if not texto:  # CAMBIO 2: un texto vacio no es una explicacion valida
            aviso = "Tu respuesta anterior estaba vacia. Escribe la explicacion en 3 o 4 frases."
            continue
        malas = cifras_no_respaldadas(texto, datos)
        if not malas:
            return {"texto": texto, "fuente": "claude", "intentos": intento}
        aviso = (f"Tu respuesta anterior contenia cifras que no estan en el JSON: {malas}. "
                 "Reescribela usando solo cifras del JSON.")
    return {"texto": explicacion_plantilla(datos), "fuente": "plantilla", "intentos": intento}