package com.wealth.shared.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Posicion valorada de una cartera. El efectivo es una posicion mas:
 * instrumentId "CASH:&lt;moneda&gt;", precio 1, cantidad = importe y coste base = importe (P&amp;L siempre 0).
 * Cantidades fraccionarias soportadas (BigDecimal).
 * <p>
 * costBasis = coste de adquisicion TOTAL de la posicion en moneda base (no unitario).
 */
public record AssetPosition(
        String instrumentId,
        String isin,
        String name,
        AssetClass assetClass,
        BigDecimal quantity,
        BigDecimal price,
        String currency,
        BigDecimal costBasis) {

    public static final String CASH_PREFIX = "CASH:";

    public AssetPosition {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(assetClass, "assetClass");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(costBasis, "costBasis");
        if (quantity.signum() < 0) {
            throw new IllegalArgumentException("Negative quantity not supported (no short positions): " + instrumentId);
        }
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("Price must be > 0: " + instrumentId);
        }
        if (costBasis.signum() < 0) {
            throw new IllegalArgumentException("Cost basis must be >= 0: " + instrumentId);
        }
    }

    public static AssetPosition cash(String currency, BigDecimal amount) {
        return new AssetPosition(CASH_PREFIX + currency, null, "Cash " + currency, AssetClass.CASH,
                amount, BigDecimal.ONE, currency, amount);
    }

    public boolean isCash() {
        return assetClass == AssetClass.CASH;
    }

    public BigDecimal marketValue() {
        return quantity.multiply(price);
    }
}
