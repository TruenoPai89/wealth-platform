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


def _tokens(texto: str) -> list[tuple[float, int]]:
    """(valor, decimales escritos) de cada cifra del texto, sin los ISIN."""
    salida = []
    for t in _NUM.findall(_ISIN.sub(" ", texto)):
        decimales = len(t.split(",")[1]) if "," in t else (
            len(t.split(".")[1]) if "." in t and not re.fullmatch(r"\d{1,3}(?:\.\d{3})+", t) else 0)
        salida.append((_a_float(t), decimales))
    return salida


def cifras_en_texto(texto: str) -> list[float]:
    return [v for v, _ in _tokens(texto)]


# Numeros escritos con letra: no los ve el detector de digitos ("cinco mil euros"). Se rechazan
# todos salvo uno/una (articulos) y dos (la regla de los 2 meses).
_PALABRAS_NUM = re.compile(
    r"\b(tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|trece|catorce|quince|"
    r"dieciseis|diecisiete|dieciocho|diecinueve|veinte|veinti\w+|treinta|cuarenta|cincuenta|"
    r"sesenta|setenta|ochenta|noventa|cien|ciento|cientos|doscientos|trescientos|"
    r"cuatrocientos|quinientos|seiscientos|setecientos|ochocientos|novecientos|mil|"
    r"millon|millones)\b", re.IGNORECASE)


def _sin_tildes(s: str) -> str:
    return s.translate(str.maketrans("áéíóúÁÉÍÓÚ", "aeiouAEIOU"))


def palabras_numericas(texto: str) -> list[str]:
    return _PALABRAS_NUM.findall(_sin_tildes(texto))


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


def _coincide(c: float, decimales: int, permitidas: set[float]) -> bool:
    # Tolerancia = media unidad del ultimo decimal ESCRITO: "1.140" vale por 1.140,00 o 1.140,40,
    # "73,3" por 73,31, pero "19,4" ya no vale por 19 (antes, 0,51 fijo lo aceptaba).
    tol = 0.5 * 10 ** (-decimales) + 1e-6
    return any(abs(c - o) <= tol for o in permitidas)


def cifras_no_respaldadas(texto: str, datos) -> list:
    """Cifras (y numeros en letra) del texto que no salen de los datos. Vacia = texto verificado."""
    permitidas = cifras_permitidas(datos)
    malas: list = [v for v, d in _tokens(texto) if not _coincide(v, d, permitidas)]
    return malas + palabras_numericas(texto)
