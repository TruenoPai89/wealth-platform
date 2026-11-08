package com.wealth.shared.domain;

import java.math.BigDecimal;

/** Orden de compra/venta propuesta por el rebalanceador. quantity &gt; 0 siempre; amount = quantity * price. */
public record RebalanceOrder(
        String instrumentId,
        String name,
        Side side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal amount,
        String currency) {

    public enum Side { BUY, SELL }
}
