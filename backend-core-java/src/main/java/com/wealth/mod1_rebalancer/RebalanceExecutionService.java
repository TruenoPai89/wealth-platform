package com.wealth.mod1_rebalancer;

import com.wealth.ledger.jev.JevLedgerAdapter;
import com.wealth.ledger.jev.JevTransactionService;
import com.wealth.ledger.jev.JournalEntryModel.JournalBatch;
import com.wealth.mod1_rebalancer.Transfer.Status;
import com.wealth.mod1_rebalancer.Transfer.Type;
import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import com.wealth.shared.domain.RebalanceOrder;
import com.wealth.shared.domain.RebalanceOrder.Side;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ejecuta (acepta) el plan de rebalanceo vigente de una cartera:
 * <ol>
 *   <li>Recalcula el plan en el servidor (nunca se fia de lo que el cliente viera en pantalla).</li>
 *   <li>Genera los asientos de partida doble (JevTransactionService, Regla 4C).</li>
 *   <li>Registra una transferencia por orden (compra/venta) y una de ajuste neto de efectivo.</li>
 *   <li>Publica el estado resultante de la cartera: las siguientes lecturas ya reflejan la ejecucion.</li>
 * </ol>
 * Todo se construye antes de aplicar nada, de modo que un fallo intermedio no deja estado a medias.
 * No toca RebalanceOrchestrator: la ejecucion se apoya en el plan que este calcula.
 */
@Service
public class RebalanceExecutionService {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    /** No hay ordenes que ejecutar: la cartera ya esta en su meta (se traduce a 409). */
    public static class NothingToExecuteException extends RuntimeException {
        public NothingToExecuteException(String message) {
            super(message);
        }
    }

    private final RebalanceOrchestrator orchestrator;
    private final JevTransactionService journal;
    private final JevLedgerAdapter ledger;
    private final TransferService transfers;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();

    public RebalanceExecutionService(RebalanceOrchestrator orchestrator, JevTransactionService journal,
                                     JevLedgerAdapter ledger, TransferService transfers, Clock clock) {
        this.orchestrator = orchestrator;
        this.journal = journal;
        this.ledger = ledger;
        this.transfers = transfers;
        this.clock = clock;
    }

    /** Serializado: dos ejecuciones simultaneas sobre el mismo estado duplicarian los movimientos. */
    public synchronized RebalanceExecution execute(String portfolioId) {
        RebalancePlan plan = orchestrator.rebalance(portfolioId);
        if (plan.orders().isEmpty()) {
            throw new NothingToExecuteException(
                    "Nothing to execute: portfolio " + portfolioId + " is already at its target allocation");
        }
        Instant now = clock.instant();
        String executionId = "EXE-" + portfolioId + "-" + ID_TIME.format(now) + "-" + sequence.incrementAndGet();

        JournalBatch batch = journal.buildJournal(plan, executionId, now);
        List<Transfer> movements = toTransfers(plan, batch, executionId, now);
        Portfolio after = projectedPortfolio(plan);

        // Todo construido sin error: ahora se aplica.
        journal.record(plan, executionId, now);
        transfers.recordAll(movements);
        ledger.applyPostedState(after);

        return new RebalanceExecution(executionId, portfolioId, now, batch.reference(),
                movements.stream().map(t -> t.withStatusAt(now)).toList(), plan.cashFlow());
    }

    /** Historial de la cartera (valida que exista: 404 si no). */
    public List<Transfer> history(String portfolioId) {
        ledger.loadPortfolio(portfolioId);
        return transfers.history(portfolioId);
    }

    /** Una transferencia por orden (mismo orden que los asientos: ventas primero) y el ajuste neto de caja. */
    private List<Transfer> toTransfers(RebalancePlan plan, JournalBatch batch, String executionId, Instant now) {
        Instant settlesAt = now.plus(transfers.settlementDelay());
        List<Transfer> result = new ArrayList<>();
        int n = 1;
        for (int i = 0; i < plan.orders().size(); i++) {
            RebalanceOrder o = plan.orders().get(i);
            result.add(new Transfer(executionId + "-T" + String.format("%02d", n++), executionId, plan.portfolioId(),
                    now, settlesAt, o.side() == Side.BUY ? Type.BUY : Type.SELL, o.instrumentId(), o.name(),
                    o.quantity(), o.amount(), o.currency(), batch.entries().get(i).entryId(), Status.IN_PROGRESS));
        }
        BigDecimal net = plan.cashFlow().netCashFlow();
        if (net.signum() != 0) {
            String cashId = AssetPosition.CASH_PREFIX + plan.baseCurrency();
            result.add(new Transfer(executionId + "-T" + String.format("%02d", n), executionId, plan.portfolioId(),
                    now, settlesAt, net.signum() > 0 ? Type.CASH_IN : Type.CASH_OUT, cashId,
                    "Efectivo (" + plan.baseCurrency() + ")", null, net.abs(), plan.baseCurrency(),
                    batch.reference(), Status.IN_PROGRESS));
        }
        return result;
    }

    /** Cartera tal y como queda tras las ordenes (la proyeccion del plan, con coste medio). */
    private static Portfolio projectedPortfolio(RebalancePlan plan) {
        String currency = plan.baseCurrency();
        List<AssetPosition> positions = plan.projectedPositions().stream()
                .map(p -> p.assetClass() == AssetClass.CASH
                        ? AssetPosition.cash(currency, p.quantity())
                        : new AssetPosition(p.instrumentId(), p.isin(), p.name(), p.assetClass(), p.quantity(),
                                p.price(), currency, p.costBasis()))
                .toList();
        return new Portfolio(plan.portfolioId(), plan.clientId(), currency, plan.asOf(), positions);
    }
}
