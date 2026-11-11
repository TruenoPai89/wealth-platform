"""Tests del explicador de margin call sin API: Claude se sustituye por funciones falsas."""

from app.advisor_llm.margin_explainer import explicacion_plantilla, explicar_margen
from app.advisor_llm.verificador_cifras import cifras_no_respaldadas

# Forma real de `data` en POST /api/v1/margin/analyze (caso MSCI 38.000 + bonos 29.500, crédito 30.000).
DATOS = {
    "portfolioId": "PF-001",
    "metrics": {
        "loanAmount": 30000.00, "pledgedMarketValue": 67500.00, "collateralValue": 53150.00,
        "requiredCollateral": 42857.14, "collateralSurplus": 10292.86, "ltv": 0.5644,
        "marginCallLtv": 0.7000, "ltvHeadroom": 0.1356, "distanceToMarginCall": 0.1937,
        "zone": "OK", "collateralVolatility": 0.0825,
        "firstYearInterest": 1200.00, "totalInterestOverTerm": 6499.59,
    },
    "marginCallProbability": [
        {"horizonDays": 30, "probability": 0.0000, "projectedLoan": 30003.22},
        {"horizonDays": 90, "probability": 0.0000, "projectedLoan": 30009.66},
        {"horizonDays": 365, "probability": 0.0359, "projectedLoan": 31200.00},
    ],
    "stressTest": [
        {"drop": 0.10, "collateralValue": 47835.00, "ltv": 0.6272, "zone": "OK",
         "topUpNeeded": 0.00, "repayNeeded": 0.00},
        {"drop": 0.20, "collateralValue": 42520.00, "ltv": 0.7056, "zone": "MARGIN_CALL",
         "topUpNeeded": 337.14, "repayNeeded": 236.00},
        {"drop": 0.30, "collateralValue": 37205.00, "ltv": 0.8064, "zone": "MARGIN_CALL",
         "topUpNeeded": 5652.14, "repayNeeded": 3956.50},
    ],
    "securityLevel": "MEDIUM",
    "recommendations": [
        {"code": "RESTORE_BUFFER", "figures": {"addCollateral": 8074.49},
         "message": "Para resistir una caída del 30 % habría que sumar 8.074,49 EUR de garantía computable."},
    ],
}


def test_plantilla_solo_usa_cifras_del_analisis():
    texto = explicacion_plantilla(DATOS)
    assert cifras_no_respaldadas(texto, DATOS) == []


def test_plantilla_incluye_nivel_ltv_probabilidades_y_estres():
    texto = explicacion_plantilla(DATOS)
    assert "nivel de seguridad" in texto and "medio" in texto
    assert "56,44 %" in texto and "70,00 %" in texto
    assert "3,59 % a 365 días" in texto
    assert "provocaría margin call" in texto
    assert "8.074,49 EUR" in texto
    assert "no constituye asesoramiento financiero" in texto


def test_plantilla_avisa_si_ya_hay_margin_call():
    datos = {**DATOS, "metrics": {**DATOS["metrics"], "zone": "MARGIN_CALL", "ltv": 0.8}}
    assert "ya ha alcanzado el umbral" in explicacion_plantilla(datos)


def test_claude_correcto_a_la_primera():
    r = explicar_margen(DATOS, llamar=lambda d, a: "El LTV es 56,44 % frente a un umbral del 70 %.")
    assert r["fuente"] == "claude" and r["intentos"] == 1


def test_reintenta_si_inventa_y_luego_acierta():
    respuestas = iter(["El LTV es 61 %.", "El LTV es 56,44 %."])
    r = explicar_margen(DATOS, llamar=lambda d, a: next(respuestas))
    assert r["fuente"] == "claude" and r["intentos"] == 2


def test_el_aviso_del_reintento_cita_la_cifra_inventada():
    avisos = []

    def llamar(d, a):
        avisos.append(a)
        return "El LTV es 61 %." if a is None else "El LTV es 56,44 %."

    explicar_margen(DATOS, llamar=llamar)
    assert avisos[0] is None and "61" in avisos[1]


def test_plantilla_si_inventa_dos_veces():
    r = explicar_margen(DATOS, llamar=lambda d, a: "Perderías 99.999 EUR.")
    assert r["fuente"] == "plantilla"
    assert "56,44 %" in r["texto"]


def test_respuesta_vacia_no_se_da_por_buena():
    r = explicar_margen(DATOS, llamar=lambda d, a: "")
    assert r["fuente"] == "plantilla"
    assert r["texto"].strip()


def test_respuesta_solo_espacios_reintenta_y_luego_acierta():
    respuestas = iter(["  \n ", "El LTV es 56,44 %."])
    r = explicar_margen(DATOS, llamar=lambda d, a: next(respuestas))
    assert r["fuente"] == "claude" and r["intentos"] == 2


def test_plantilla_si_claude_falla():
    def llamar(d, a):
        raise RuntimeError("sin clave")

    r = explicar_margen(DATOS, llamar=llamar)
    assert r["fuente"] == "plantilla"
