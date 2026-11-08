package com.wealth.mod1_rebalancer;

import java.time.Instant;
import java.util.List;

/**
 * Resultado de ejecutar un rebalanceo (respuesta de POST /api/v1/portfolios/{id}/rebalance/execute).
 * {@code journalReference} es la referencia del lote de asientos de partida doble generado.
 */
public record RebalanceExecution(
        String executionId,
        String portfolioId,
        Instant executedAt,
        String journalReference,
        List<Transfer> transfers,
        RebalancePlan.CashFlowSummary cashFlow) {
}
