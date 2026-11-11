"""Explica el análisis de margin call (POST /api/v1/margin/analyze del backend Java).

Mismo patrón que pledge_explainer: el motor Java calcula, Claude solo redacta y el verificador de cifras
rechaza cualquier número que no salga del JSON. Si Claude falla o inventa dos veces, se usa una plantilla.
"""

import json
import os

from anthropic import Anthropic

from app.advisor_llm.pledge_explainer import _eur, _pct

MODELO = os.environ.get("CLAUDE_MODEL", "claude-haiku-5-5")
MAX_INTENTOS = 2

PROMPT_SISTEMA = """
Eres un asistente que explica el riesgo de margin call de un crédito con garantía de fondos.

Recibes el análisis ya calculado. Redacta en español claro unas 6-8 frases.

REGLAS:
- Usa exclusivamente cifras que aparecen en los datos. No inventes, estimes ni calcules nada nuevo.
- Los campos ltv, marginCallLtv, ltvHeadroom, distanceToMarginCall, collateralVolatility, probability,
  drop, weight y riskContribution son fracciones: exprésalos como porcentaje (0.5644 = 56,44 %).
- Importes en euros con formato español: 2936.25 = 2.936,25 EUR.
- securityLevel: LOW = nivel bajo de seguridad, MEDIUM = medio, HIGH = alto.
- Menciona el nivel de seguridad, el LTV frente al umbral, la distancia al margin call, las
  probabilidades a 30, 90 y 365 días y el resultado de los escenarios de estrés.
- Resume las recomendaciones tal como vienen (campo message); no añadas otras.
- Las volatilidades y correlaciones son supuestos ilustrativos, no datos históricos: dilo.
- No menciones nombres de campos JSON ni códigos ISIN.
- Termina indicando que es información orientativa y no asesoramiento financiero.
"""

_NIVEL = {"LOW": "bajo", "MEDIUM": "medio", "HIGH": "alto"}


def _prob(analisis, dias):
    for p in analisis.get("marginCallProbability") or []:
        if p.get("horizonDays") == dias:
            return p.get("probability")
    return None


def explicacion_plantilla(datos):
    """Explicación determinista sin Claude; solo usa cifras del análisis."""
    m = datos.get("metrics") or {}
    nivel = _NIVEL.get(datos.get("securityLevel"), "desconocido")

    partes = [
        f"El nivel de seguridad frente al margin call es {nivel}.",
        f"El LTV es {_pct(m.get('ltv'))} frente a un umbral de margin call del {_pct(m.get('marginCallLtv'))}; "
        f"la garantía computable es {_eur(m.get('collateralValue'))} y la mínima requerida "
        f"{_eur(m.get('requiredCollateral'))}.",
    ]

    distancia = m.get("distanceToMarginCall")
    if distancia is not None and m.get("zone") != "MARGIN_CALL":
        partes.append(
            f"La garantía puede caer un {_pct(distancia)} antes de alcanzar el margin call."
        )
    elif m.get("zone") == "MARGIN_CALL":
        partes.append("El LTV ya ha alcanzado el umbral de margin call.")

    probs = [(d, _prob(datos, d)) for d in (30, 90, 365)]
    if all(p is not None for _, p in probs):
        partes.append(
            "La probabilidad estimada de margin call es "
            + ", ".join(f"{_pct(p)} a {d} días" for d, p in probs) + "."
        )

    escenarios = datos.get("stressTest") or []
    if escenarios:
        frases = []
        for e in escenarios:
            estado = "provocaría margin call" if e.get("zone") == "MARGIN_CALL" else "se mantendría dentro del umbral"
            frases.append(f"una caída del {_pct(e.get('drop'))} dejaría el LTV en {_pct(e.get('ltv'))} y {estado}")
        partes.append("En el test de estrés, " + "; ".join(frases) + ".")

    for r in datos.get("recommendations") or []:
        if r.get("message"):
            partes.append(r["message"])

    partes.append(
        "Las volatilidades y correlaciones son supuestos ilustrativos, no datos históricos. "
        "Esta información es orientativa y no constituye asesoramiento financiero."
    )
    return " ".join(partes)


def _llamar_claude(datos, aviso=None):
    mensaje = json.dumps(datos, ensure_ascii=False, indent=2)
    if aviso:
        mensaje += (
            "\n\nLa explicación anterior contenía cifras no respaldadas. "
            "Corrige estos problemas:\n" + aviso
        )
    respuesta = Anthropic().messages.create(
        model=MODELO,
        max_tokens=700,
        system=PROMPT_SISTEMA,
        messages=[{"role": "user", "content": mensaje}],
    )
    return "".join(b.text for b in respuesta.content if b.type == "text")


def explicar_margen(datos, llamar=None, verificador=None):
    """Devuelve {texto, fuente, intentos}. 'llamar' y 'verificador' se sustituyen en los tests."""
    if llamar is None:
        llamar = _llamar_claude
    if verificador is None:
        from app.advisor_llm.verificador_cifras import cifras_no_respaldadas
        verificador = cifras_no_respaldadas

    aviso = None
    for intento in range(1, MAX_INTENTOS + 1):
        try:
            texto = llamar(datos, aviso)
        except Exception:
            break

        # Un texto vacío no tiene cifras inventadas y el verificador lo daría por bueno: se trata como fallo.
        if not texto or not texto.strip():
            aviso = "La respuesta estaba vacía: redacta la explicación completa."
            continue

        problemas = verificador(texto, datos)
        if not problemas:
            return {"texto": texto, "fuente": "claude", "intentos": intento}
        aviso = "; ".join(str(p) for p in problemas)

    return {"texto": explicacion_plantilla(datos), "fuente": "plantilla", "intentos": MAX_INTENTOS}
