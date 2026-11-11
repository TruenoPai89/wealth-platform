
"""Tests del módulo 4 sin API: Claude se sustituye por funciones falsas."""

import copy
from pathlib import Path

import pytest

from app.advisor_llm import pledge_extractor
from app.advisor_llm.pledge_explainer import (
    explicacion_plantilla,
    explicar_estado,
)
from app.advisor_llm.pledge_extractor import (
    ExtraccionFallida,
    extraer_terminos,
)
from scripts.evaluar_extraccion import comparar
from app.advisor_llm.verificador_cifras import cifras_no_respaldadas


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


def test_confundir_aforo_con_descuento_lo_rechaza_el_extractor():
    # La cita es literal, pero el haircut (0,70) no sale de "aforo del 70 %" (seria 0,30).
    malo = copy.deepcopy(CORRECTO)
    malo["haircuts"][0]["haircut"] = 0.70

    with pytest.raises(ExtraccionFallida) as e:
        extraer_terminos(TEXTO, llamar=lambda texto, aviso: copy.deepcopy(malo))

    assert any("EQUITY" in p for p in e.value.problemas)


def test_valor_que_no_coincide_con_su_cita_se_rechaza():
    # Cita literal del 70 %, pero ltv_maximo = 0,80.
    malo = copy.deepcopy(CORRECTO)
    malo["ltv_maximo"] = 0.80

    with pytest.raises(ExtraccionFallida) as e:
        extraer_terminos(TEXTO, llamar=lambda texto, aviso: copy.deepcopy(malo))

    assert any("ltv_maximo" in p for p in e.value.problemas)



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

def test_plantilla_verificada():
    """Comprueba que la plantilla no contiene cifras sin respaldo."""

    texto = explicacion_plantilla(ESTADO)

    assert cifras_no_respaldadas(texto, ESTADO) == []
    assert "2.936,25 EUR" in texto
    assert "73,31 %" in texto


def test_cita_con_numero_en_letra_no_se_rechaza():
    # Un contrato que escribe "cinco dias" y "setenta por ciento" no debe dar un falso rechazo.
    variante = copy.deepcopy(CORRECTO)
    variante["cita_plazo"] = "dispondra de cinco dias habiles"
    variante["cita_ltv_maximo"] = "El saldo dispuesto no podra superar el setenta por ciento"
    texto = TEXTO.replace("5 dias habiles", "cinco dias habiles").replace(
        "el 70 % del\nValor de Garantia", "el setenta por ciento del\nValor de Garantia")
    resultado = extraer_terminos(texto, llamar=lambda t, a: copy.deepcopy(variante),
                                 usar_cache=False)
    assert resultado["terminos"]["plazo_reposicion_dias_habiles"] == 5


def test_numero_de_clausula_no_cuenta_como_importe():
    # La cita incluye "Clausula 4." y el valor es setenta por ciento: no debe rechazarse por el 4.
    variante = copy.deepcopy(CORRECTO)
    variante["cita_ltv_maximo"] = (
        "Clausula 4. Limite. El saldo dispuesto no podra superar el setenta por ciento del "
        "Valor de Garantia."
    )
    texto = TEXTO.replace(
        "no podra superar el 70 % del", "no podra superar el setenta por ciento del")
    resultado = extraer_terminos(texto, llamar=lambda t, a: copy.deepcopy(variante),
                                 usar_cache=False)
    assert resultado["terminos"]["ltv_maximo"] == 0.70


def test_numero_de_clausula_no_valida_un_plazo_falso():
    # "Clausula 5." no debe hacer pasar por bueno un plazo de 5 cuando el contrato dice 3.
    malo = copy.deepcopy(CORRECTO)
    malo["plazo_reposicion_dias_habiles"] = 5
    malo["cita_plazo"] = "Clausula 5. Margen. dispondra de tres dias habiles"
    texto = TEXTO.replace("5 dias habiles", "tres dias habiles")
    with pytest.raises(ExtraccionFallida):
        extraer_terminos(texto, llamar=lambda t, a: copy.deepcopy(malo), usar_cache=False)
