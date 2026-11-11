package com.wealth.mod5_telephony;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resumen ULTRACOMPACTO de la cartera para el agente de voz (unos 900 bytes): lo que necesita para conversar sin
 * hacer mas consultas. Importes en moneda base, redondeados (cash con centimos); porcentajes con 1 decimal.
 * <p>
 * {@code riskLevel} NO es el perfil de idoneidad (MiFID) del cliente: es un indicador DERIVADO de la composicion de la
 * cartera (peso de renta variable + materias primas, en {@code riskAssetsPct}). El agente no debe presentarlo como el
 * perfil regulatorio del cliente.
 */
public record AgentContext(
        String userId,
        String portfolioId,
        String currency,
        String asOf,
        BigDecimal total,
        BigDecimal cash,
        BigDecimal cashPct,
        String riskLevel,
        BigDecimal riskAssetsPct,
        List<PositionLine> positions,
        List<BasketLine> baskets) {

    /** Fondo de la cartera (sin efectivo, de mayor a menor valor). */
    public record PositionLine(String name, BigDecimal pct, BigDecimal eur) {
    }

    /** Agrupacion existente: la composicion va en una sola cadena para ahorrar tokens. */
    public record BasketLine(String id, String name, String mix) {
    }
}
