
"""Extracción de términos de un contrato de pignoración con Claude (tool use).

Defensas:
1. Pydantic valida tipos y rangos.
2. Cada cita debe aparecer literalmente en el contrato.
3. El umbral de margin call debe ser superior al LTV máximo.

Si algo falla, se reintenta una vez indicando a Claude qué debe corregir.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

from pydantic import ValidationError

from app.advisor_llm.verificador_cifras import cifras_en_texto

from app.advisor_llm.pledge_terms import (
    HERRAMIENTA,
    TerminosPignoracion,
)


MODELO = os.environ.get("CLAUDE_MODEL", "claude-haiku-5-5")
MAX_INTENTOS = 2
VERSION_PROMPT = "v1"

CACHE_DIR = Path(
    os.environ.get("EXTRACTION_CACHE_DIR", ".cache_extracciones")
)


PROMPT_SISTEMA = """\
Eres un extractor de términos contractuales. Devuelve solo lo que el texto
afirma, llamando a la herramienta registrar_terminos_pignoracion.

- Si un dato no aparece, usa null. No supongas ni calcules.
- Expresa los porcentajes como fracciones (70 % -> 0.70).
- Si el contrato da un "aforo" (la parte que cuenta), el haircut es 1 - aforo.
- Si da un "descuento", el haircut es ese descuento.
- Si un activo computa "por su valor íntegro", su haircut es 0.
- Clases de activo permitidas: EQUITY (renta variable),
  FIXED_INCOME (renta fija), COMMODITY (materias primas),
  CASH (efectivo). No incluyas otras clases.
- En cada cita copia LITERALMENTE el fragmento del contrato que respalda
  el valor, sin cambiar ninguna palabra.
"""


class ExtraccionFallida(Exception):
    """Error cuando no se consigue una extracción válida."""

    def __init__(self, problemas: list[str]):
        super().__init__("; ".join(problemas))
        self.problemas = problemas


def _norm(s: str) -> str:
    """Normaliza espacios y saltos de línea."""
    return " ".join(s.split())


def _numero_en_cita(valor: float, cita: str) -> bool:
    return any(abs(valor - n) < 0.01 for n in cifras_en_texto(cita))


def _problema_haircut(h) -> str | None:
    """El haircut debe salir de la cita: 'aforo del 70 %' -> 0,30; 'descuento del 10 %' -> 0,10;
    'valor integro' -> 0."""
    cita = _norm(h.cita).lower().replace("í", "i")
    numeros = [n / 100 for n in cifras_en_texto(h.cita)]
    if "integro" in cita:
        validos = [0.0]
    elif "aforo" in cita:
        validos = [round(1 - n, 4) for n in numeros]
    elif "descuento" in cita:
        validos = numeros
    else:
        return None  # sin palabra clave no se puede decidir
    if any(abs(h.haircut - v) < 0.0001 for v in validos):
        return None
    return (f"haircut de {h.clase.value}={h.haircut} no sale de su cita "
            f"(esperado {sorted(set(validos))}): {h.cita!r}")


def problemas_de(
    texto: str,
    t: TerminosPignoracion,
) -> list[str]:
    """Comprueba las citas y la coherencia de los términos extraídos."""

    base = _norm(texto)
    problemas = []

    # Comprobar las citas de los términos generales.
    for nombre in (
        "cita_ltv_maximo",
        "cita_margin_call",
        "cita_plazo",
    ):
        cita = getattr(t, nombre)

        if cita and _norm(cita) not in base:
            problemas.append(
                f"{nombre} no aparece literalmente en el contrato: {cita!r}"
            )

    # Comprobar las citas de los haircuts.
    for h in t.haircuts:
        if _norm(h.cita) not in base:
            problemas.append(
                f"La cita de {h.clase.value} no aparece literalmente: "
                f"{h.cita!r}"
            )

    # Comprobar que cada VALOR coincide con el numero de SU cita (la cita puede ser literal y el
    # valor estar mal: p. ej. cita "70 %" con ltv_maximo = 0.80).
    for nombre, valor, cita, factor in (
        ("ltv_maximo", t.ltv_maximo, t.cita_ltv_maximo, 100),
        ("ltv_margin_call", t.ltv_margin_call, t.cita_margin_call, 100),
        ("plazo_reposicion_dias_habiles", t.plazo_reposicion_dias_habiles, t.cita_plazo, 1),
    ):
        if valor is not None and cita and not _numero_en_cita(valor * factor, cita):
            problemas.append(
                f"{nombre}={valor} no coincide con ningun numero de su cita: {cita!r}"
            )

    for h in t.haircuts:
        problema = _problema_haircut(h)
        if problema:
            problemas.append(problema)

    # Comprobar la coherencia entre los dos umbrales.
    if (
        t.ltv_maximo is not None
        and t.ltv_margin_call is not None
        and t.ltv_margin_call <= t.ltv_maximo
    ):
        problemas.append(
            "ltv_margin_call debe ser mayor que ltv_maximo"
        )

    return problemas


def _llamar_claude(
    texto: str,
    aviso: str | None,
) -> dict:
    """Envía el contrato a Claude y obtiene los datos mediante tool use."""

    import anthropic

    client = anthropic.Anthropic()

    contenido = "Contrato:\n\n" + texto

    if aviso:
        contenido += "\n\n" + aviso

    respuesta = client.messages.create(
        model=MODELO,
        max_tokens=1500,
        system=PROMPT_SISTEMA,
        tools=[HERRAMIENTA],
        tool_choice={
            "type": "tool",
            "name": HERRAMIENTA["name"],
        },
        messages=[
            {
                "role": "user",
                "content": contenido,
            }
        ],
    )

    # Buscar el bloque en el que Claude ha utilizado la herramienta.
    for bloque in respuesta.content:
        if bloque.type == "tool_use":
            return bloque.input

    raise RuntimeError(
        "Claude no devolvió una llamada a la herramienta"
    )


def _ruta_cache(texto: str) -> Path:
    """Genera una ruta de caché única para este modelo, prompt y contrato."""

    clave = hashlib.sha256(
        f"{MODELO}|{VERSION_PROMPT}|{texto}".encode("utf8")
    ).hexdigest()[:24]

    return CACHE_DIR / f"{clave}.json"


def extraer_terminos(
    texto: str,
    llamar=_llamar_claude,
    usar_cache: bool = True,
) -> dict:
    """Extrae términos y devuelve el resultado, intentos y origen.

    Lanza ExtraccionFallida si no consigue una extracción válida.
    """

    if not texto or not texto.strip():
        raise ExtraccionFallida(["el contrato está vacío"])

    ruta = _ruta_cache(texto)

    # Si ya procesamos este contrato, reutilizamos el resultado.
    if usar_cache and ruta.exists():
        return {
            "terminos": json.loads(ruta.read_text(encoding="utf-8")),
            "intentos": 0,
            "desde_cache": True,
        }

    aviso = None
    problemas: list[str] = []

    for intento in range(1, MAX_INTENTOS + 1):

        # Llamar a Claude (o a una función simulada en los tests).
        crudo = llamar(texto, aviso)

        try:
            t = TerminosPignoracion.model_validate(crudo)
            problemas = problemas_de(texto, t)

        except ValidationError as e:
            problemas = [
                f"formato inválido: {err['loc']}: {err['msg']}"
                for err in e.errors()
            ]

        # Si todo es correcto, guardar y devolver.
        if not problemas:
            datos = t.model_dump(mode="json")

            if usar_cache:
                CACHE_DIR.mkdir(parents=True, exist_ok=True)
                ruta.write_text(
                    json.dumps(datos, ensure_ascii=False, indent=2),
                    encoding="utf-8",
                )

            return {
                "terminos": datos,
                "intentos": intento,
                "desde_cache": False,
            }

        # Si falla, preparar un aviso para el siguiente intento.
        aviso = (
            "Tu extracción anterior tenía estos problemas: "
            + "; ".join(problemas)
            + ". Corrígelos y vuelve a llamar a la herramienta."
        )

    raise ExtraccionFallida(problemas)
