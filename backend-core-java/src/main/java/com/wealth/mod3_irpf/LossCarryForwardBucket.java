package com.wealth.mod3_irpf;

import java.math.BigDecimal;

/**
 * Saldo negativo de un ejercicio anterior, pendiente de compensar.
 * Se puede compensar en los 4 ejercicios siguientes al de origen (art. 49 LIRPF).
 */
public record LossCarryForwardBucket(int originYear, BigDecimal remaining) {

    /** Ejercicios siguientes al de origen en los que el saldo puede usarse. */
    public static final int YEARS_ALLOWED = 4;

    public boolean isUsableIn(int fiscalYear) {
        return fiscalYear > originYear && fiscalYear <= originYear + YEARS_ALLOWED;
    }

    public LossCarryForwardBucket withRemaining(BigDecimal newRemaining) {
        return new LossCarryForwardBucket(originYear, newRemaining);
    }
}