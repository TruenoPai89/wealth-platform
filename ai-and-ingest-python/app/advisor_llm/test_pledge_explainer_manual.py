
from app.advisor_llm.pledge_explainer import explicar_estado


datos = {
    "zone": "LIMIT_EXCEEDED",
    "ltv": 0.7331,
    "ltvMax": 0.70,
    "ltvMarginCall": 0.80,
    "equityDropToMarginCall": 0.0836,
    "actions": {
        "repayCash": 2936.25,
        "addCollateral": 4194.64
    },
    "marginCallProbability": 0.0101,
    "horizonDays": 30
}


# PRUEBA 1: Claude responde correctamente.

def claude_correcto(datos, aviso):
    return "El LTV actual es del 73,31 %."


def verificador_correcto(texto, datos):
    return []


resultado = explicar_estado(
    datos,
    llamar=claude_correcto,
    verificador=verificador_correcto
)

print("\nPRUEBA 1: RESPUESTA CORRECTA")
print(resultado)


# PRUEBA 2: Claude se equivoca una vez y corrige su respuesta.

contador = {"llamadas": 0}


def claude_reintento(datos, aviso):
    contador["llamadas"] += 1

    if contador["llamadas"] == 1:
        return "El LTV actual es del 99 %."

    print("\nAviso enviado a Claude:", aviso)
    return "El LTV actual es del 73,31 %."


def verificador_reintento(texto, datos):
    if "99 %" in texto:
        return ["El porcentaje 99 % no está respaldado."]
    return []


resultado = explicar_estado(
    datos,
    llamar=claude_reintento,
    verificador=verificador_reintento
)

print("\nPRUEBA 2: REINTENTO")
print(resultado)


# PRUEBA 3: Claude falla los dos intentos.

def claude_falla(datos, aviso):
    return "El LTV actual es del 99 %."


def verificador_falla(texto, datos):
    return ["El porcentaje 99 % no está respaldado."]


resultado = explicar_estado(
    datos,
    llamar=claude_falla,
    verificador=verificador_falla
)

print("\nPRUEBA 3: PLANTILLA DE RESPALDO")
print(resultado)
