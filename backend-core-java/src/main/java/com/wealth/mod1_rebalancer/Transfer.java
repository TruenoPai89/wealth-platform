package com.wealth.mod1_rebalancer;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Movimiento financiero generado al ejecutar un rebalanceo (una fila del historial de "Transferencias").
 * <ul>
 *   <li>BUY / SELL: una por orden. {@code amount} es el importe de la orden y {@code quantity} las participaciones.</li>
 *   <li>CASH_IN / CASH_OUT: ajuste NETO de efectivo de la ejecucion (ventas - compras), sin participaciones.
 *       Es el resumen del efecto en la cuenta de caja, no un movimiento adicional de dinero.</li>
 * </ul>
 * {@code amount} siempre es positivo (magnitud, 2 decimales); el sentido lo da {@code type}.
 * {@code journalRef} enlaza con el asiento de partida doble (id del asiento en BUY/SELL, referencia del lote en caja).
 * {@code status} se calcula al leer: EN PROCESO hasta {@code settlesAt} (liquidacion simulada) y COMPLETADO despues.
 */
public record Transfer(
        String id,
        String executionId,
        String portfolioId,
        Instant createdAt,
        Instant settlesAt,
        Type type,
        String instrumentId,
        String name,
        BigDecimal quantity,
        BigDecimal amount,
        String currency,
        String journalRef,
        Status status) {

    /** CONTRIBUTION: aportacion de efectivo del cliente (p. ej. a una agrupacion), distinta del ajuste neto CASH_IN/OUT. */
    public enum Type { BUY, SELL, CASH_IN, CASH_OUT, CONTRIBUTION }

    public enum Status { IN_PROGRESS, COMPLETED }

    /** Copia con el estado que corresponde en el instante {@code now}. */
    public Transfer withStatusAt(Instant now) {
        return new Transfer(id, executionId, portfolioId, createdAt, settlesAt, type, instrumentId, name,
                quantity, amount, currency, journalRef,
                now.isBefore(settlesAt) ? Status.IN_PROGRESS : Status.COMPLETED);
    }
}
