package com.wealth.mod1_rebalancer;

import com.wealth.mod1_rebalancer.RebalancePlan.Performance;
import com.wealth.mod1_rebalancer.RebalancePlan.ProjectedPosition;
import com.wealth.shared.domain.RebalanceOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Propuesta de invertir una aportacion de efectivo en una agrupacion (cash-inflow rebalancing). Solo COMPRAS:
 * nunca se vende. Importes con 2 decimales, participaciones con 4, pesos en tanto por uno con 6.
 * <ul>
 *   <li>{@code funds}: reparto dentro de la agrupacion (peso en la cesta antes/despues de comprar).</li>
 *   <li>{@code projectedPositions}: la cartera completa tras invertir, con el peso frente a la meta de la cartera.</li>
 *   <li>{@code unallocated}: aportacion menos lo invertido (centimos por el redondeo a 4 decimales de las
 *       participaciones); se queda en efectivo.</li>
 * </ul>
 */
public record ContributionPlan(
        String portfolioId,
        String basketId,
        String basketName,
        String baseCurrency,
        Instant asOf,
        BigDecimal amount,
        BigDecimal invested,
        BigDecimal unallocated,
        List<FundRow> funds,
        List<RebalanceOrder> orders,
        BigDecimal cashBefore,
        BigDecimal cashAfter,
        List<ProjectedPosition> projectedPositions,
        Performance performance,
        Performance projectedPerformance) {

    /** Un fondo de la agrupacion. basketWeight = peso objetivo en la cesta; *InBasket = peso real dentro de la cesta. */
    public record FundRow(
            String instrumentId,
            String name,
            BigDecimal basketWeight,
            BigDecimal currentValue,
            BigDecimal currentWeightInBasket,
            BigDecimal buyAmount,
            BigDecimal buyQuantity,
            BigDecimal valueAfter,
            BigDecimal weightInBasketAfter) {
    }
}
