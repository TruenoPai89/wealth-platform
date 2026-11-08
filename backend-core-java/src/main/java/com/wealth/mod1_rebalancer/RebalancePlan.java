package com.wealth.mod1_rebalancer;

import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.RebalanceOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Resultado del rebalanceo y contrato JSON de salida del modulo 1
 * (contracts/mocks-contracts/rebalancer_base_mod1.json, consumido por el frontend).
 * <p>
 * Convenciones: importes con 2 decimales, participaciones con 4 (efectivo: 2), pesos en tanto por uno con 6.
 * Los porcentajes de P&amp;L van como porcentaje absoluto con 2 decimales (10.64 = +10,64 %).
 * deltaWeight / deltaValue = objetivo - actual (positivo = comprar, negativo = vender).
 * <p>
 * {@code positions} es la cartera ACTUAL; {@code projectedPositions} es la cartera tras ejecutar {@code orders}
 * (las ordenes son una propuesta: no se han ejecutado).
 */
public record RebalancePlan(
        String schemaVersion,
        String module,
        String portfolioId,
        String clientId,
        String baseCurrency,
        Instant asOf,
        BigDecimal totalValue,
        Constraints constraints,
        List<PositionRow> positions,
        List<RebalanceOrder> orders,
        CashFlowSummary cashFlow,
        Performance performance,
        List<ProjectedPosition> projectedPositions,
        Performance projectedPerformance) {

    public static final String SCHEMA_VERSION = "1.1";
    public static final String MODULE = "MOD1_REBALANCER";

    /** Restricciones aplicadas. Base: sin friccion fiscal ni activos bloqueados (mod2 / mod4 las rellenaran). */
    public record Constraints(boolean taxFrictionApplied, Map<String, BigDecimal> lockedQuantities) {
    }

    public record PositionRow(
            String instrumentId,
            String isin,
            String name,
            AssetClass assetClass,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal marketValue,
            BigDecimal costBasis,
            BigDecimal unrealizedPnLAmount,
            BigDecimal unrealizedPnLPercent,
            BigDecimal currentWeight,
            BigDecimal targetWeight,
            BigDecimal deltaWeight,
            BigDecimal targetValue,
            BigDecimal deltaValue,
            RebalanceOrder order,
            BigDecimal postTradeQuantity,
            BigDecimal postTradeValue,
            BigDecimal postTradeWeight) {
    }

    /**
     * Posicion tras ejecutar las ordenes. El coste base se proyecta por COSTE MEDIO: una compra suma su importe y
     * una venta retira la parte proporcional del coste. (La asignacion por lotes FIFO es del modulo 2.)
     */
    public record ProjectedPosition(
            String instrumentId,
            String isin,
            String name,
            AssetClass assetClass,
            BigDecimal quantity,
            BigDecimal price,
            BigDecimal marketValue,
            BigDecimal weight,
            BigDecimal targetWeight,
            BigDecimal deltaWeight,
            BigDecimal costBasis,
            BigDecimal unrealizedPnLAmount,
            BigDecimal unrealizedPnLPercent) {
    }

    /**
     * Rendimiento agregado de una cartera. totalCost incluye el efectivo a su valor nominal, de modo que
     * totalValue - totalCost = suma del P&amp;L de las posiciones. En la proyeccion, las plusvalias de lo vendido
     * ya no cuentan como no realizadas.
     */
    public record Performance(
            BigDecimal totalValue,
            BigDecimal totalCost,
            BigDecimal unrealizedPnLAmount,
            BigDecimal unrealizedPnLPercent) {
    }

    /** netCashFlow = ventas - compras (positivo = el rebalanceo libera caja hacia la posicion de efectivo). */
    public record CashFlowSummary(
            BigDecimal cashBefore,
            BigDecimal buyTotal,
            BigDecimal sellTotal,
            BigDecimal netCashFlow,
            BigDecimal cashAfter,
            BigDecimal targetCash) {
    }
}
