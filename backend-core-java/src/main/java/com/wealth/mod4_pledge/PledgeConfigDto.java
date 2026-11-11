package com.wealth.mod4_pledge;

import com.wealth.shared.domain.AssetClass;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Configuración de pignoración que el cliente define a mano en pantalla (sin depender de un contrato en PDF).
 * Todas las proporciones son FRACCIONES (0.045 = 4,5 %), como en el resto de la API.
 * <p>
 * La validación vive en {@link MarginCallService}: así los errores llegan al cliente como 400 con un mensaje claro
 * y no como un fallo de deserialización.
 *
 * @param portfolioId   cartera; si es null o vacío se usa PF-001 (la del simulador).
 * @param collateral    fondos aportados como garantía y el valor de MERCADO aportado de cada uno (EUR).
 * @param loanAmount    importe del crédito dispuesto (EUR), &gt;= 0.
 * @param interestRate  tipo de interés anual (0.045 = 4,5 %), en [0, 1).
 * @param termYears     plazo en años (1 a 40).
 * @param marginCallLtv LTV a partir del cual el banco lanza el margin call (0.70 = 70 %), en (0, 1).
 * @param haircuts      descuento por clase de activo (0.30 = cuenta el 70 %). Opcional: si falta se usan los del
 *                      contrato de ejemplo (renta variable 30 %, renta fija 10 %, materias primas 25 %). Una
 *                      clase ausente de un mapa informado se trata como NO admitida (descuento 100 %).
 */
public record PledgeConfigDto(
        String portfolioId,
        List<CollateralItem> collateral,
        BigDecimal loanAmount,
        BigDecimal interestRate,
        Integer termYears,
        BigDecimal marginCallLtv,
        Map<AssetClass, BigDecimal> haircuts) {

    /**
     * @param instrumentId fondo de la cartera (ISIN / instrumentId).
     * @param amount       valor de mercado aportado como garantía (EUR); no puede superar el de la posición.
     */
    public record CollateralItem(String instrumentId, BigDecimal amount) {}
}
