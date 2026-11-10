
"""Tests del módulo 4 sin API: Claude se sustituye por funciones falsas."""

import copy
from pathlib import Path

import pytest

from app.advisor_llm import pledge_extractor
from app.advisor_llm.pledge_explainer import explicar_estado
from app.advisor_llm.pledge_extractor import (
    ExtraccionFallida,
    extraer_terminos,
)
from scripts.evaluar_extraccion import comparar


# ------------------------------------------------------------
# Datos de prueba
# ------------------------------------------------------------

RAIZ = Path(__file__).resolve().parents[1]

TEXTO = (
    RAIZ.parent
    / "bank-simulator-mocks"
    / "documents-pdf"
    / "contrato_pignoracion_mock.txt"
).read_text(encoding="utf-8")


# Respuesta que devolvería un Claude perfecto
CORRECTO = {
    "ltv_maximo": 0.70,
    "cita_ltv_maximo": (
        "El saldo dispuesto no podra superar el 70 % del Valor de Garantia"
    ),
    "ltv_margin_call": 0.80,
    "cita_margin_call": (
        "Si el saldo dispuesto alcanza el 80 % del Valor de Garantia"
    ),
    "plazo_reposicion_dias_habiles": 5,
    "cita_plazo": "dispondra de 5 dias habiles",
    "haircuts": [
        {
            "clase": "EQUITY",
            "haircut": 0.30,
            "cita": (
                "Renta variable (acciones y ETF de renta variable): "
                "aforo del 70 %"
            ),
        },
        {
            "clase": "FIXED_INCOME",
            "haircut": 0.10,
            "cita": (
                "Renta fija (bonos y ETF de deuda publica de la zona euro): "
                "descuento del 10 %"
            ),
        },
        {
            "clase": "COMMODITY",
            "haircut": 0.25,
            "cita": (
                "Materias primas (ETC de oro fisico): descuento del 25 %"
            ),
        },
        {
            "clase": "CASH",
            "haircut": 0.0,
            "cita": "Efectivo en cuenta: computa por su valor integro",
        },
    ],
}


ESPERADO = {
    "ltv_maximo": 0.70,
    "ltv_margin_call": 0.80,
    "plazo_reposicion_dias_habiles": 5,
    "haircuts": {
        "EQUITY": 0.30,
        "FIXED_INCOME": 0.10,
        "COMMODITY": 0.25,
        "CASH": 0.0,
    },
}


ESTADO = {
    "portfolioId": "PF-001",
    "contractId": "PC-PF-001",
    "loanId": "LN-PF-001",
    "equityShock": 0.25,
    "marketValue": 112625.00,
    "collateralValue": 88662.50,
    "loanBalance": 65000.00,
    "ltv": 0.7331,
    "ltvMax": 0.70,
    "ltvMarginCall": 0.80,
    "zone": "LIMIT_EXCEEDED",
    "cureDays": 5,
    "collateralDropToMarginCall": 0.0836,
    "equityDropToMarginCall": 0.2031,
    "actions": {
        "repayCash": 2936.25,
        "addCollateral": 4194.64,
        "sellByClass": {
            "EQUITY": {
                "marketValueToSell": 5757.35,
                "available": 52125.00,
                "feasible": True,
            }
        },
    },
    "horizonDays": 30,
    "collateralVolatility": 0.1199,
    "marginCallProbability": 0.0101,
    "positions": [],
}


# ------------------------------------------------------------
# Configuración: caché independiente para cada test
# ------------------------------------------------------------

@pytest.fixture(autouse=True)
def cache_temporal(tmp_path, monkeypatch):
    """Cada test utiliza su propia caché temporal."""
    monkeypatch.setattr(
        pledge_extractor,
        "CACHE_DIR",
        tmp_path / "cache",
    )


# ------------------------------------------------------------
# Extracción: 9 pruebas
# ------------------------------------------------------------

def test_extraccion_correcta():
    resultado = extraer_terminos(
        TEXTO,
        llamar=lambda texto, aviso: copy.deepcopy(CORRECTO),
    )

    assert resultado["intentos"] == 1
    assert not resultado["desde_cache"]
    assert comparar(resultado["terminos"], ESPERADO) == []


def test_segunda_vez_sale_de_la_cache_sin_llamar():
    extraer_terminos(
        TEXTO,
        llamar=lambda texto, aviso: copy.deepcopy(CORRECTO),
    )

    def no_llamar(texto, aviso):
        raise AssertionError("No debería llamar a Claude")

    resultado = extraer_terminos(TEXTO, llamar=no_llamar)

    assert resultado["desde_cache"] is True


def test_cita_inventada_provoca_reintento():
    malo = copy.deepcopy(CORRECTO)
    malo["haircuts"][2]["cita"] = (
        "El oro tiene un descuento del 25 %"
    )

    respuestas = iter([
        malo,
        copy.deepcopy(CORRECTO),
    ])

    avisos = []

    def claude_falso(texto, aviso):
        avisos.append(aviso)
        return next(respuestas)

    resultado = extraer_terminos(
        TEXTO,
        llamar=claude_falso,
    )

    assert resultado["intentos"] == 2
    assert avisos[0] is None
    assert "COMMODITY" in avisos[1]


def test_dos_fallos_lanzan_error():
    malo = copy.deepcopy(CORRECTO)
    malo["cita_plazo"] = "dispondra de 10 dias habiles"

    with pytest.raises(ExtraccionFallida) as error:
        extraer_terminos(
            TEXTO,
            llamar=lambda texto, aviso: copy.deepcopy(malo),
        )

    assert any(
        "cita_plazo" in problema
        for problema in error.value.problemas
    )


def test_porcentaje_sin_convertir_se_rechaza():
    malo = copy.deepcopy(CORRECTO)
    malo["ltv_maximo"] = 70

    with pytest.raises(ExtraccionFallida):
        extraer_terminos(
            TEXTO,
            llamar=lambda texto, aviso: copy.deepcopy(malo),
        )


def test_margin_call_por_debajo_del_limite_se_rechaza():
    malo = copy.deepcopy(CORRECTO)
    malo["ltv_maximo"] = 0.80
    malo["ltv_margin_call"] = 0.70
    malo["cita_ltv_maximo"] = None
    malo["cita_margin_call"] = None

    with pytest.raises(ExtraccionFallida):
        extraer_terminos(
            TEXTO,
            llamar=lambda texto, aviso: copy.deepcopy(malo),
        )


def test_clase_repetida_se_rechaza():
    malo = copy.deepcopy(CORRECTO)
    malo["haircuts"].append(
        copy.deepcopy(malo["haircuts"][0])
    )

    with pytest.raises(ExtraccionFallida):
        extraer_terminos(
            TEXTO,
            llamar=lambda texto, aviso: copy.deepcopy(malo),
        )


def test_confundir_aforo_con_descuento_lo_detecta_la_evaluacion():
    # La cita es literal, pero el haircut es incorrecto.
    malo = copy.deepcopy(CORRECTO)
    malo["haircuts"][0]["haircut"] = 0.70

    resultado = extraer_terminos(
        TEXTO,
        llamar=lambda texto, aviso: copy.deepcopy(malo),
    )

    errores = comparar(resultado["terminos"], ESPERADO)

    assert any("EQUITY" in error for error in errores)


def test_contrato_vacio():
    with pytest.raises(ExtraccionFallida):
        extraer_terminos(
            " ",
            llamar=lambda texto, aviso: copy.deepcopy(CORRECTO),
        )


# ------------------------------------------------------------
# Explicación: 2 pruebas independientes del módulo 3
# ------------------------------------------------------------

def test_explicacion_claude_correcta():
    texto = (
        "Tu LTV es del 73,31 %, por encima del limite del 70 % "
        "pero lejos del 80 %. "
        "Podrias amortizar 2.936,25 EUR. "
        "Probabilidad estimada en 30 dias: 1,01 %."
    )

    def verificador_falso(texto, datos):
        return []

    resultado = explicar_estado(
        ESTADO,
        llamar=lambda datos, aviso: texto,
        verificador=verificador_falso,
    )

    assert resultado["fuente"] == "claude"


def test_explicacion_con_cifra_inventada_acaba_en_plantilla():
    def verificador_falso(texto, datos):
        if "3.000 EUR" in texto:
            return ["La cifra 3.000 EUR no está respaldada."]
        return []

    resultado = explicar_estado(
        ESTADO,
        llamar=lambda datos, aviso: "Deberias amortizar 3.000 EUR.",
        verificador=verificador_falso,
    )

    assert resultado["fuente"] == "plantilla"
    assert resultado["intentos"] == 2
