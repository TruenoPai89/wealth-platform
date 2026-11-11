
"""Fiabilidad de la extracción: llama a Claude N veces (SIN caché)
y cuenta cuántas extracciones coinciden con los valores esperados.

Uso desde ai-and-ingest-python/:

    python -m scripts.evaluar_extraccion
    python -m scripts.evaluar_extraccion 5
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

from app.advisor_llm.pledge_extractor import (  # noqa: E402
    ExtraccionFallida,
    extraer_terminos,
)


# Directorio raíz de ai-and-ingest-python/
RAIZ = Path(__file__).resolve().parents[1]

# Contrato de prueba situado en el repositorio principal
CONTRATO = (
    RAIZ.parent
    / "bank-simulator-mocks"
    / "documents-pdf"
    / "contrato_pignoracion_mock.txt"
)

# Valores correctos con los que comparar
ESPERADO = RAIZ / "tests" / "fixtures" / "contrato_esperado.json"


def comparar(terminos: dict, esperado: dict) -> list[str]:
    """Compara los términos extraídos con los valores esperados."""

    errores = []

    # Comprobar los parámetros generales
    for campo in (
        "ltv_maximo",
        "ltv_margin_call",
        "plazo_reposicion_dias_habiles",
    ):
        if terminos.get(campo) != esperado[campo]:
            errores.append(
                f"{campo}: esperado {esperado[campo]}, "
                f"obtenido {terminos.get(campo)}"
            )

    # Convertir la lista de haircuts en un diccionario
    obtenidos = {
        h["clase"]: h["haircut"]
        for h in terminos["haircuts"]
    }

    # Comprobar que cada clase tiene el haircut correcto
    for clase, valor in esperado["haircuts"].items():

        if clase not in obtenidos:
            errores.append(f"Falta el haircut de {clase}")

        elif abs(obtenidos[clase] - valor) > 1e-9:
            errores.append(
                f"Haircut de {clase}: esperado {valor}, "
                f"obtenido {obtenidos[clase]}"
            )

    # Detectar clases que no deberían aparecer
    for clase in obtenidos.keys() - esperado["haircuts"].keys():
        errores.append(f"Haircut no esperado: {clase}")

    return errores


def main(n: int) -> int:
    """Ejecuta n extracciones y calcula la tasa de aciertos."""

    texto = CONTRATO.read_text(encoding="utf-8")
    esperado = json.loads(ESPERADO.read_text(encoding="utf-8"))

    aciertos = 0

    for i in range(1, n + 1):

        try:
            # Sin caché: cada ejecución llama realmente a Claude
            resultado = extraer_terminos(
                texto,
                usar_cache=False,
            )

            errores = comparar(resultado["terminos"], esperado)

        except ExtraccionFallida as e:
            errores = [
                "Extracción rechazada: " + "; ".join(e.problemas)
            ]

        except Exception as e:
            errores = [
                f"Error: {type(e).__name__}: {e}"
            ]

        if errores:
            print(f"Ejecución {i}: FALLO")

            for error in errores:
                print(f" - {error}")

        else:
            aciertos += 1
            print(
                f"Ejecución {i}: OK "
                f"({resultado['intentos']} intento/s)"
            )

    print(f"\n{aciertos}/{n} ejecuciones correctas")

    return 0 if aciertos == n else 1


if __name__ == "__main__":
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    sys.exit(main(n))
