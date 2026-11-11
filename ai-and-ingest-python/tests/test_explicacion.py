"""Tests sin API: se sustituye la llamada a Claude por funciones falsas."""
from app.advisor_llm.claude_engine import explicacion_plantilla, explicar_venta
from app.advisor_llm.verificador_cifras import cifras_en_texto, cifras_no_respaldadas

# Forma real de la respuesta de POST /api/v1/irpf/PF-001/simulate-sale
DATOS = {
    "fiscalYear": 2026, "isin": "IE00B3XXRP09", "quantity": 100, "salePrice": 50.0,
    "saleGainLoss": -2000.00, "affectedLots": 1, "twoMonthRuleViolation": False,
    "computableSaleResult": -2000.00,
    "before": {"netBalance": 6000.00, "carryForwardUsed": 0.00, "taxableBase": 6000.00,
               "tax": 1140.00, "averageRate": 0.1900, "remainingCarryForward": []},
    "after": {"netBalance": 4000.00, "carryForwardUsed": 0.00, "taxableBase": 4000.00,
              "tax": 760.00, "averageRate": 0.1900, "remainingCarryForward": []},
    "taxDelta": -380.00, "warning": None,
}


def test_lee_formato_espanol():
    assert cifras_en_texto("de 1.140,00 EUR a 760 EUR, un 19 %") == [1140.0, 760.0, 19.0]


def test_ignora_digitos_del_isin():
    assert cifras_en_texto("vender IE00B3XXRP09 hoy") == []


def test_texto_correcto_pasa():
    texto = "Tu cuota bajaria de 1.140,00 EUR a 760,00 EUR, un ahorro de 380 EUR al 19 %."
    assert cifras_no_respaldadas(texto, DATOS) == []


def test_cifra_inventada_se_detecta():
    assert cifras_no_respaldadas("Te ahorrarias 420 EUR.", DATOS) == [420.0]


def test_claude_correcto_a_la_primera():
    r = explicar_venta(DATOS, llamar=lambda d, a: "Pasarias de 1.140,00 EUR a 760,00 EUR.")
    assert r["fuente"] == "claude" and r["intentos"] == 1


def test_reintenta_si_inventa_y_luego_acierta():
    respuestas = iter(["Ahorras 999 EUR.", "Ahorras 380,00 EUR."])
    r = explicar_venta(DATOS, llamar=lambda d, a: next(respuestas))
    assert r["fuente"] == "claude" and r["intentos"] == 2


def test_plantilla_si_inventa_dos_veces():
    r = explicar_venta(DATOS, llamar=lambda d, a: "Ahorras 999 EUR.")
    assert r["fuente"] == "plantilla"
    assert "380,00 EUR" in r["texto"]


def test_plantilla_si_la_api_falla():
    def rota(d, a):
        raise RuntimeError("sin saldo")
    assert explicar_venta(DATOS, llamar=rota)["fuente"] == "plantilla"


def test_plantilla_pasa_su_propia_verificacion():
    assert cifras_no_respaldadas(explicacion_plantilla(DATOS), DATOS) == []


def test_plantilla_regla_dos_meses():
    d = dict(DATOS, twoMonthRuleViolation=True, taxDelta=0.0)
    assert "2 meses" in explicacion_plantilla(d)
