package com.wealth.mod1_rebalancer;

import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.RebalanceOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Resultado del rebalanceo y contrato JSON de salida del modulo 1
 * (contracts/mocks-contracts/rebalancer_base_mod1.json, consumido por PositionsTableWithSlots.tsx).
 * Convenciones: importes con 2 decimales, cantidades con 6, pesos en tanto por uno con 6.
 * deltaWeight / deltaValue = objetivo - actual (positivo = comprar, negativo = vender).
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
        CashFlowSummary cashFlow) {

    public static final String SCHEMA_VERSION = "1.0";
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
