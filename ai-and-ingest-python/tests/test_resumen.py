"""Tests sin API del resumen fiscal del ejercicio: la llamada a Claude se sustituye por funciones falsas."""
from fastapi.testclient import TestClient

from app.advisor_llm.resumen_engine import explicacion_plantilla, explicar_resumen
from app.advisor_llm.verificador_cifras import cifras_no_respaldadas
from app.main import app

# Forma real de la respuesta de POST /api/v1/irpf/PF-001/annual-report (ejemplo de la pantalla)
DATOS = {
    "portfolioId": "PF-001", "fiscalYear": 2026,
    "operations": [
        {"isin": "IE00B4L5Y983", "saleDate": "2026-03-16", "quantity": 300, "quantityCovered": 300,
         "salePrice": 95.00, "transmissionValue": 28500.00, "acquisitionValue": 18360.00,
         "gainLoss": 10140.00, "firstAcquisitionDate": "2022-11-10", "lastAcquisitionDate": "2022-11-10",
         "lotsUsed": 1, "twoMonthRuleViolation": False, "computableGainLoss": 10140.00, "warning": None},
    ],
    "totalTransmissionValue": 162600.00, "totalAcquisitionValue": 138390.00,
    "totalGains": 27400.00, "totalLosses": 2840.00, "deferredLosses": 350.00,
    "result": {"netBalance": 24560.00, "carryForwardUsed": 1500.00, "taxableBase": 23060.00,
               "tax": 4722.60, "averageRate": 0.2048, "remainingCarryForward": []},
}


def test_plantilla_solo_usa_cifras_del_json():
    texto = explicacion_plantilla(DATOS)
    assert "4.722,60 EUR" in texto and "regla de los 2 meses" in texto
    assert cifras_no_respaldadas(texto, DATOS) == []


def test_plantilla_con_saldo_negativo():
    datos = {**DATOS, "totalGains": 0.0, "totalLosses": 2840.0, "deferredLosses": 0.0,
             "result": {**DATOS["result"], "netBalance": -2840.0, "carryForwardUsed": 0.0,
                        "taxableBase": 0.0, "tax": 0.0}}
    texto = explicacion_plantilla(datos)
    assert "no hay cuota" in texto
    assert cifras_no_respaldadas(texto, datos) == []


def test_claude_correcto_a_la_primera():
    r = explicar_resumen(DATOS, llamar=lambda d, a: "Tu base del ahorro es 23.060,00 EUR y la cuota 4.722,60 EUR.")
    assert r["fuente"] == "claude" and r["intentos"] == 1


def test_cifra_inventada_reintenta_y_cae_a_plantilla():
    r = explicar_resumen(DATOS, llamar=lambda d, a: "Pagaras 5.000 EUR.")
    assert r["fuente"] == "plantilla" and r["intentos"] == 2


def test_api_caida_devuelve_plantilla():
    def falla(d, a):
        raise RuntimeError("sin red")
    assert explicar_resumen(DATOS, llamar=falla)["fuente"] == "plantilla"


def test_endpoint_responde_con_plantilla_si_no_hay_api(monkeypatch):
    monkeypatch.setattr("app.advisor_llm.resumen_engine._llamar_claude",
                        lambda d, a: (_ for _ in ()).throw(RuntimeError("sin red")))
    r = TestClient(app).post("/explain/irpf-annual", json=DATOS)
    assert r.status_code == 200 and r.json()["fuente"] == "plantilla"
