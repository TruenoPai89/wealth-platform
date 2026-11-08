package com.wealth.shared.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Foto valorada de una cartera en moneda base (instrumentos + efectivo). */
public record Portfolio(
        String portfolioId,
        String clientId,
        String baseCurrency,
        Instant asOf,
        List<AssetPosition> positions) {

    public Portfolio {
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(baseCurrency, "baseCurrency");
        Objects.requireNonNull(asOf, "asOf");
        positions = List.copyOf(positions);
    }

    public BigDecimal totalValue() {
        return positions.stream()
                .map(AssetPosition::marketValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
