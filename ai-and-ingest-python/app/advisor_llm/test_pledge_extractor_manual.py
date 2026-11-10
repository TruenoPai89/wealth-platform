
from app.advisor_llm.pledge_extractor import (
    extraer_terminos,
    ExtraccionFallida,
)

contrato = (
    "El límite de disposición será del 70 %. "
    "El umbral de aviso de margen se establece en el 80 %. "
    "Las acciones tendrán un aforo del 70 %."
)

datos_correctos = {
    "ltv_maximo": 0.70,
    "cita_ltv_maximo": "El límite de disposición será del 70 %.",
    "ltv_margin_call": 0.80,
    "cita_margin_call": (
        "El umbral de aviso de margen se establece en el 80 %."
    ),
    "plazo_reposicion_dias_habiles": None,
    "cita_plazo": None,
    "haircuts": [
        {
            "clase": "EQUITY",
            "haircut": 0.30,
            "cita": "Las acciones tendrán un aforo del 70 %.",
        }
    ],
}


# Simula una respuesta incorrecta la primera vez
# y una respuesta correcta la segunda.
contador = {"intentos": 0}


def llamada_simulada(texto, aviso):
    contador["intentos"] += 1

    if contador["intentos"] == 1:
        datos = datos_correctos.copy()
        datos["cita_ltv_maximo"] = "Cita inventada por Claude"
        return datos

    print("\nAviso recibido por Claude:")
    print(aviso)

    return datos_correctos


resultado = extraer_terminos(
    contrato,
    llamar=llamada_simulada,
    usar_cache=False,
)

print("\nResultado final:")
print(resultado)
print("\nLlamadas simuladas:", contador["intentos"])

# Comprobar el funcionamiento de la caché

primera = extraer_terminos(
    contrato,
    llamar=llamada_simulada,
    usar_cache=True,
)

segunda = extraer_terminos(
    contrato,
    llamar=llamada_simulada,
    usar_cache=True,
)

print("\nPrimera extracción desde caché:", primera["desde_cache"])
print("Segunda extracción desde caché:", segunda["desde_cache"])