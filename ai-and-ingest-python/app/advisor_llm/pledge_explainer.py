
"""Genera explicaciones comprensibles del estado de un préstamo pignorado."""

import json
import os

from anthropic import Anthropic


MODELO = os.environ.get("CLAUDE_MODEL", "claude-haiku-5-5")
MAX_INTENTOS = 2


PROMPT_SISTEMA = """
Eres un asistente especializado en explicar préstamos con garantía de valores.

Explica el estado del préstamo en español claro y comprensible.
Utiliza unas 4-5 frases.

REGLAS:
- Utiliza exclusivamente las cifras que aparecen en los datos recibidos.
- No inventes cifras, no estimes valores ni realices cálculos adicionales.
- Los campos ltv, ltvMax, ltvMarginCall, collateralDropToMarginCall,
  equityDropToMarginCall, equityShock y marginCallProbability son fracciones.
  Exprésalos como porcentajes. Por ejemplo, 0.7331 = 73,31 %.
- Expresa las cantidades monetarias en euros con formato español:
  2936.25 = 2.936,25 EUR.
- Si la zona es OK, el préstamo está dentro de los límites.
- Si es LIMIT_EXCEEDED, se ha superado el límite de disposición,
  pero todavía no se ha alcanzado el umbral de margin call.
- Si es MARGIN_CALL, el banco puede exigir garantías adicionales
  o proceder a vender activos según el contrato.
- Si existen acciones con importes superiores a cero, explica las
  alternativas disponibles: amortizar deuda, aportar efectivo o vender activos.
  No recomiendes una opción concreta.
- La probabilidad de margin call es una estimación basada en las
  hipótesis de volatilidad indicadas.
- No menciones nombres de campos JSON ni códigos ISIN.
- Termina indicando que la información es orientativa y no constituye
  asesoramiento financiero.
"""


_ZONAS = {
    "OK": "El préstamo se encuentra dentro de los límites establecidos.",
    "LIMIT_EXCEEDED": (
        "Se ha superado el límite de disposición del préstamo, "
        "aunque todavía no se ha alcanzado el umbral de margin call."
    ),
    "MARGIN_CALL": (
        "Se ha alcanzado el umbral de margin call. El banco puede "
        "exigir garantías adicionales o tomar medidas según el contrato."
    ),
}


def _pct(x):
    """Convierte una fracción en porcentaje con formato español."""
    if x is None:
        return None

    valor = f"{float(x) * 100:,.2f}"
    valor = valor.replace(",", "X").replace(".", ",").replace("X", ".")
    return valor + " %"


def _eur(x):
    """Convierte una cantidad en euros con formato español."""
    if x is None:
        return None

    valor = f"{float(x):,.2f}"
    valor = valor.replace(",", "X").replace(".", ",").replace("X", ".")
    return valor + " EUR"


def explicacion_plantilla(d):
    """Genera una explicación sin depender de Claude."""

    zona = d.get("zone", "UNKNOWN")
    explicacion_zona = _ZONAS.get(
        zona,
        "No se ha podido identificar la situación del préstamo."
    )

    ltv = _pct(d.get("ltv"))
    ltv_max = _pct(d.get("ltvMax"))
    ltv_margin = _pct(d.get("ltvMarginCall"))

    partes = [
        explicacion_zona,
        f"El LTV actual es {ltv}, frente a un máximo permitido de "
        f"{ltv_max} y un umbral de margin call de {ltv_margin}."
    ]

    caida = d.get("equityDropToMarginCall")

    if caida is not None and zona != "MARGIN_CALL":
        partes.append(
            f"Según el escenario estimado, una caída adicional del "
            f"{_pct(caida)} en la renta variable llevaría al umbral "
            f"de margin call."
        )

    acciones = d.get("actions") or {}

    amortizar = acciones.get("repayCash", 0) or 0
    aportar = acciones.get("addCollateral", 0) or 0

    if amortizar > 0 or aportar > 0:
        opciones = []

        if amortizar > 0:
            opciones.append(
                f"amortizar {_eur(amortizar)} de deuda"
            )

        if aportar > 0:
            opciones.append(
                f"aportar {_eur(aportar)} en efectivo como garantía"
            )

        partes.append(
            "Entre las posibles medidas se encuentran "
            + " o ".join(opciones)
            + "."
        )

    probabilidad = d.get("marginCallProbability")
    horizonte = d.get("horizonDays")

    if probabilidad is not None and horizonte is not None:
        partes.append(
            f"La probabilidad estimada de margin call durante "
            f"los próximos {horizonte} días es del {_pct(probabilidad)}, "
            f"bajo las hipótesis de volatilidad utilizadas."
        )

    partes.append(
        "Esta información es orientativa y no constituye "
        "asesoramiento financiero."
    )

    return " ".join(partes)


def _llamar_claude(datos, aviso=None):
    """Envía el estado del préstamo a Claude y devuelve su explicación."""

    cliente = Anthropic()

    mensaje = json.dumps(datos, ensure_ascii=False, indent=2)

    if aviso:
        mensaje += (
            "\n\nLa explicación anterior contenía cifras no respaldadas. "
            "Corrige estos problemas:\n" + aviso
        )

    respuesta = cliente.messages.create(
        model=MODELO,
        max_tokens=500,
        system=PROMPT_SISTEMA,
        messages=[
            {"role": "user", "content": mensaje}
        ],
    )

    return "".join(
        bloque.text
        for bloque in respuesta.content
        if bloque.type == "text"
    )


def explicar_estado(datos, llamar=None, verificador=None):
    """
    Genera una explicación y comprueba que sus cifras están respaldadas.

    'llamar' y 'verificador' pueden sustituirse por funciones simuladas
    durante las pruebas.
    """

    if llamar is None:
        llamar = _llamar_claude

    # Importación diferida: el módulo 3 puede estar todavía en otra rama.
    if verificador is None:
        try:
            from app.advisor_llm.verificador_cifras import cifras_no_respaldadas
            verificador = cifras_no_respaldadas
        except ModuleNotFoundError:
            return {
                "texto": explicacion_plantilla(datos),
                "fuente": "plantilla",
                "intentos": 0,
                "motivo": "Verificador del módulo 3 todavía no integrado."
            }

    aviso = None

    for intento in range(1, MAX_INTENTOS + 1):
        try:
            texto = llamar(datos, aviso)
        except Exception:
            break

        problemas = verificador(texto, datos)

        if not problemas:
            return {
                "texto": texto,
                "fuente": "claude",
                "intentos": intento
            }

        aviso = "; ".join(problemas)

    return {
        "texto": explicacion_plantilla(datos),
        "fuente": "plantilla",
        "intentos": MAX_INTENTOS
    }
