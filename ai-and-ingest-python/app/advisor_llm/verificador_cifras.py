"""Comprueba que cada cifra de un texto de Claude sale del JSON del motor.

Es la pieza que convierte "cero alucinaciones" en algo demostrable: si Claude escribe una cifra
que el motor no ha calculado, la explicacion se rechaza.
"""
from __future__ import annotations

import re

# ISIN (2 letras + 9 alfanumericos + 1 digito): sus digitos no son cifras financieras.
_ISIN = re.compile(r"\b[A-Z]{2}[A-Z0-9]{9}\d\b")
# Numero en formato espanol (1.140,50) o simple (1140,5 / 1140.5 / 19).
_NUM = re.compile(r"\d{1,3}(?:\.\d{3})+(?:,\d+)?|\d+(?:[.,]\d+)?")
# Constantes que pueden aparecer sin estar en el JSON (p. ej. "regla de los 2 meses").
_CONSTANTES = {2.0}


def _a_float(token: str) -> float:
    if re.fullmatch(r"\d{1,3}(?:\.\d{3})+(?:,\d+)?", token):  # 1.140 o 1.140,50
        return float(token.replace(".", "").replace(",", "."))
    return float(token.replace(",", "."))


def cifras_en_texto(texto: str) -> list[float]:
    return [_a_float(t) for t in _NUM.findall(_ISIN.sub(" ", texto))]


def cifras_permitidas(datos) -> set[float]:
    """Todos los numeros del JSON y su valor absoluto; las fracciones (|v| <= 1) tambien en
    porcentaje (0.19 -> 19). Solo las fracciones: si no, un 30 (dias) daria por buena la cifra 3.000."""
    salida: set[float] = set()

    def _anadir(v: float):
        salida.update({v, abs(v)})
        if abs(v) <= 1:
            salida.add(abs(v) * 100)

    def recorrer(x):
        if isinstance(x, bool) or x is None:
            return
        if isinstance(x, (int, float)):
            v = float(x)
            _anadir(v)
        elif isinstance(x, str):
            try:
                v = float(x)
                _anadir(v)
            except ValueError:
                pass
        elif isinstance(x, dict):
            for valor in x.values():
                recorrer(valor)
        elif isinstance(x, (list, tuple)):
            for valor in x:
                recorrer(valor)

    recorrer(datos)
    return salida | _CONSTANTES


def _coincide(c: float, permitidas: set[float]) -> bool:
    # Tolerancia de redondeo: 1.140 por 1.140,00, 73,3 % por 73,31 %. Relativa muy pequena (0,1 %)
    # para que en importes grandes no se acepten cifras distintas.
    return any(abs(c - o) <= max(0.51, 0.001 * abs(o)) for o in permitidas)


def cifras_no_respaldadas(texto: str, datos) -> list[float]:
    """Cifras del texto que no aparecen en los datos. Lista vacia = texto verificado."""
    permitidas = cifras_permitidas(datos)
    return [c for c in cifras_en_texto(texto) if not _coincide(c, permitidas)]
