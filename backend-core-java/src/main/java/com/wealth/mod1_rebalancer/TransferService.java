package com.wealth.mod1_rebalancer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Historial de transferencias en MEMORIA (se pierde al reiniciar). El estado (En proceso / Completado) no se guarda:
 * se calcula al leer comparando la hora actual con la de liquidacion simulada.
 * TODO(PostgreSQL): persistir en una tabla append-only (migracion Flyway nueva) al activar la BBDD.
 */
@Service
public class TransferService {

    private final Clock clock;
    private final Duration settlementDelay;
    private final List<Transfer> store = new CopyOnWriteArrayList<>();

    public TransferService(Clock clock,
                           @Value("${wealth.transfers.settlement-delay-seconds:20}") long settlementDelaySeconds) {
        this.clock = clock;
        this.settlementDelay = Duration.ofSeconds(settlementDelaySeconds);
    }

    /** Retardo de liquidacion simulado: instante de liquidacion = instante de ejecucion + este retardo. */
    public Duration settlementDelay() {
        return settlementDelay;
    }

    public void recordAll(List<Transfer> transfers) {
        store.addAll(transfers);
    }

    /** Movimientos de la cartera, el mas reciente primero (dentro de una ejecucion se conserva su orden). */
    public List<Transfer> history(String portfolioId) {
        Instant now = clock.instant();
        return store.stream()
                .filter(t -> t.portfolioId().equals(portfolioId))
                .map(t -> t.withStatusAt(now))
                .sorted(Comparator.comparing(Transfer::createdAt).reversed()) // sorted() es estable
                .toList();
    }

    /** Vacia el historial (tests y demos). */
    void clear() {
        store.clear();
    }
}
