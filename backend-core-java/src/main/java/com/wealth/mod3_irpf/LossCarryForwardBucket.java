package com.wealth.mod3_irpf;
import java.math.BigDecimal;
/**
 * Saldo negativo de ganancias y perdidas patrimoniales pendiente de 
compensar.
 * <p>
 * Se puede compensar con el saldo positivo de los cuatro ejercicios 
siguientes al de origen
 * (verificar el plazo vigente en la normativa antes de la demo).
 * No modela la compensacion cruzada con rendimientos del capital mobiliario 
(con limite porcentual).
 *
 * @param originYear ejercicio en el que se genero el saldo negativo.
 * @param remaining  importe pendiente (>= 0).
 */
public record LossCarryForwardBucket(int originYear, BigDecimal remaining) {
    public static final int YEARS_ALLOWED = 4;
    public LossCarryForwardBucket {
        if (remaining == null || remaining.signum() < 0) {
            throw new IllegalArgumentException("remaining must be >= 0");
        }
    }
    /** Usable en el ejercicio dado: del siguiente al de origen hasta cuatro 
despues. */
    public boolean isUsableIn(int fiscalYear) {
        return fiscalYear > originYear && fiscalYear <= originYear + 
YEARS_ALLOWED;
    }
    public LossCarryForwardBucket withRemaining(BigDecimal newRemaining) {
        return new LossCarryForwardBucket(originYear, newRemaining);
    }
}