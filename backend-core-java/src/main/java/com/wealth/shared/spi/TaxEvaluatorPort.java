package com.wealth.shared.spi;

import com.wealth.shared.domain.RebalanceOrder;

import java.math.BigDecimal;

/**
 * Puerto SPI (Service Provider Interface) del Módulo 2 (Optimizador Fiscal).
 * <p>
 * Permite al Módulo 1 (Rebalanceador) consultar el coste fiscal estimado de ejecutar
 * una orden de venta <strong>sin acoplarse a la implementación FIFO concreta</strong>.
 * <p>
 * Contrato:
 * <ul>
 *   <li>El método recibe una {@link RebalanceOrder} de tipo SELL y devuelve el peaje fiscal
 *       estimado en la moneda de la orden (siempre {@link BigDecimal}, nunca negativo).</li>
 *   <li>Si la venta genera una minusvalía sujeta a la regla de los 2 meses (AEAT),
 *       el resultado incluirá una advertencia a través de {@link FrictionCost#twoMonthRuleViolation()}.</li>
 *   <li>Las órdenes de tipo BUY devuelven {@link FrictionCost#ZERO}.</li>
 * </ul>
 *
 * Implementado por: {@code com.wealth.mod2_taxoptimizer.TaxEvaluatorAdapter}
 */
public interface TaxEvaluatorPort {

    /**
     * Estima el peaje fiscal de ejecutar la orden indicada.
     *
     * @param order orden propuesta por el rebalanceador (BUY o SELL).
     * @return coste fiscal estimado; nunca {@code null}.
     */
    FrictionCost estimateFrictionCost(RebalanceOrder order);

    /**
     * Resultado del análisis fiscal de una orden.
     *
     * @param taxableGainLoss          Plusvalía (+) o minusvalía (-) estimada en la moneda de la orden.
     *                                 Cero si no hay lotes históricos o la orden es BUY.
     * @param estimatedTaxCost         Impuesto estimado a pagar (tramos IRPF ahorro español: 19%-28%).
     *                                 Siempre ≥ 0; es 0 cuando hay minusvalía o la orden es BUY.
     * @param affectedLots             Número de lotes FIFO que se verían consumidos total o parcialmente.
     * @param twoMonthRuleViolation    {@code true} si la minusvalía quedaría anulada por la regla
     *                                 anti-aplicación de la AEAT (compra de valores homogéneos en
     *                                 ±60 días respecto a la fecha de venta).
     * @param warning                  Mensaje descriptivo cuando hay violación de la regla de los 2 meses.
     *                                 {@code null} cuando no aplica.
     */
    record FrictionCost(
            BigDecimal taxableGainLoss,
            BigDecimal estimatedTaxCost,
            int affectedLots,
            boolean twoMonthRuleViolation,
            String warning) {

        /** Resultado neutro: sin fricción fiscal (usado para órdenes BUY o sin historial). */
        public static final FrictionCost ZERO = new FrictionCost(
                BigDecimal.ZERO, BigDecimal.ZERO, 0, false, null);

        public FrictionCost {
            if (taxableGainLoss == null) throw new IllegalArgumentException("taxableGainLoss must not be null");
            if (estimatedTaxCost == null) throw new IllegalArgumentException("estimatedTaxCost must not be null");
            if (estimatedTaxCost.signum() < 0)
                throw new IllegalArgumentException("estimatedTaxCost must be >= 0, got: " + estimatedTaxCost);
            if (affectedLots < 0) throw new IllegalArgumentException("affectedLots must be >= 0");
        }

        /** Indica si la orden genera fricción fiscal relevante (plusvalía con impuesto > 0). */
        public boolean hasTaxFriction() {
            return estimatedTaxCost.signum() > 0;
        }
    }
}
