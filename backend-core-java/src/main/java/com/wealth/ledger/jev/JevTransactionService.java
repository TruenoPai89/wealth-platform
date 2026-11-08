package com.wealth.ledger.jev;

import com.wealth.ledger.jev.JournalEntryModel.EntryType;
import com.wealth.ledger.jev.JournalEntryModel.JournalBatch;
import com.wealth.ledger.jev.JournalEntryModel.JournalEntry;
import com.wealth.ledger.jev.JournalEntryModel.JournalLine;
import com.wealth.ledger.jev.JournalEntryModel.LineSide;
import com.wealth.mod1_rebalancer.RebalancePlan;
import com.wealth.shared.domain.RebalanceOrder;
import com.wealth.shared.domain.RebalanceOrder.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Convierte un {@link RebalancePlan} en asientos de partida doble (un asiento por orden BUY/SELL):
 * <pre>
 *   BUY : DEBIT  ASSETS:CUSTODY:&lt;instrumento&gt; (importe, participaciones)   CREDIT ASSETS:CASH:&lt;moneda&gt; (importe)
 *   SELL: DEBIT  ASSETS:CASH:&lt;moneda&gt; (importe)                           CREDIT ASSETS:CUSTODY:&lt;instrumento&gt; (importe, participaciones)
 * </pre>
 * Los importes salen de las ordenes del plan (ya a 2 decimales), por lo que cada asiento cuadra exactamente.
 * El journal es append-only: nunca se actualiza ni se borra un asiento.
 * <p>
 * TODO(PostgreSQL): {@link #record(RebalancePlan)} solo construye el lote y lo traza en el log. Al activar PostgreSQL
 * persistir aqui el lote (INSERT-only, una transaccion por lote, idempotente por {@code JournalBatch.reference})
 * mediante una migracion Flyway nueva (las V1..V3 actuales estan vacias).
 */
@Service
public class JevTransactionService {

    private static final Logger log = LoggerFactory.getLogger(JevTransactionService.class);
    private static final DateTimeFormatter REF_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    /** Construye los asientos sin efectos secundarios. Referencia determinista: mismo plan -> mismos ids. */
    public JournalBatch buildJournal(RebalancePlan plan) {
        return buildJournal(plan, "REB-" + plan.portfolioId() + "-" + REF_TIME.format(plan.asOf()), plan.asOf());
    }

    /**
     * Igual que {@link #buildJournal(RebalancePlan)} pero con referencia y fecha contable propias, para la ejecucion
     * real de un plan (cada ejecucion necesita una referencia unica; la del plan depende solo de la fecha del snapshot).
     */
    public JournalBatch buildJournal(RebalancePlan plan, String reference, Instant bookedAt) {
        String cashAccount = "ASSETS:CASH:" + plan.baseCurrency();

        List<JournalEntry> entries = new ArrayList<>();
        BigDecimal netCash = BigDecimal.ZERO;
        int seq = 1;
        for (RebalanceOrder order : plan.orders()) {
            String custody = "ASSETS:CUSTODY:" + order.instrumentId();
            boolean buy = order.side() == Side.BUY;

            JournalLine custodyLine = new JournalLine(custody, buy ? LineSide.DEBIT : LineSide.CREDIT,
                    order.amount(), order.quantity());
            JournalLine cashLine = new JournalLine(cashAccount, buy ? LineSide.CREDIT : LineSide.DEBIT,
                    order.amount(), null);

            entries.add(new JournalEntry(
                    reference + "-" + String.format("%03d", seq++), reference,
                    buy ? EntryType.BUY : EntryType.SELL, bookedAt, plan.baseCurrency(),
                    buy ? List.of(custodyLine, cashLine) : List.of(cashLine, custodyLine)));
            netCash = buy ? netCash.subtract(order.amount()) : netCash.add(order.amount());
        }

        entries.forEach(e -> {
            if (!e.isBalanced()) {
                throw new IllegalStateException("Unbalanced journal entry " + e.entryId());
            }
        });
        return new JournalBatch(reference, plan.portfolioId(), plan.baseCurrency(), entries, netCash);
    }

    /** Genera y registra el lote de asientos del plan. De momento NO persiste (ver TODO de la clase). */
    public JournalBatch record(RebalancePlan plan) {
        return log(buildJournal(plan));
    }

    /** Genera y registra el lote de una ejecucion con referencia y fecha propias. Tampoco persiste todavia. */
    public JournalBatch record(RebalancePlan plan, String reference, Instant bookedAt) {
        return log(buildJournal(plan, reference, bookedAt));
    }

    /**
     * Asientos de una aportacion de efectivo seguida de las compras que la invierten. El primer asiento
     * (CONTRIBUTION) ingresa el efectivo contra la cuenta de aportaciones del cliente:
     * <pre>
     *   DEBIT ASSETS:CASH:&lt;moneda&gt; (importe)    CREDIT EQUITY:CLIENT_CONTRIBUTIONS (importe)
     * </pre>
     * y a continuacion un asiento BUY por orden, igual que en un rebalanceo. netCashMovement = aportacion - compras.
     */
    public JournalBatch buildContributionJournal(String portfolioId, String currency, BigDecimal contribution,
                                                 List<RebalanceOrder> buyOrders, String reference, Instant bookedAt) {
        String cashAccount = "ASSETS:CASH:" + currency;
        List<JournalEntry> entries = new ArrayList<>();
        int seq = 1;
        entries.add(new JournalEntry(reference + "-" + String.format("%03d", seq++), reference,
                EntryType.CONTRIBUTION, bookedAt, currency, List.of(
                new JournalLine(cashAccount, LineSide.DEBIT, contribution, null),
                new JournalLine("EQUITY:CLIENT_CONTRIBUTIONS", LineSide.CREDIT, contribution, null))));
        BigDecimal netCash = contribution;
        for (RebalanceOrder order : buyOrders) {
            if (order.side() != Side.BUY) {
                throw new IllegalArgumentException("A contribution only buys; got " + order.side() + " " + order.instrumentId());
            }
            entries.add(new JournalEntry(reference + "-" + String.format("%03d", seq++), reference,
                    EntryType.BUY, bookedAt, currency, List.of(
                    new JournalLine("ASSETS:CUSTODY:" + order.instrumentId(), LineSide.DEBIT, order.amount(), order.quantity()),
                    new JournalLine(cashAccount, LineSide.CREDIT, order.amount(), null))));
            netCash = netCash.subtract(order.amount());
        }
        entries.forEach(e -> {
            if (!e.isBalanced()) {
                throw new IllegalStateException("Unbalanced journal entry " + e.entryId());
            }
        });
        return new JournalBatch(reference, portfolioId, currency, entries, netCash);
    }

    /** Registra (log; sin persistir todavia) un lote ya construido, p. ej. el de una aportacion. */
    public JournalBatch record(JournalBatch batch) {
        return log(batch);
    }

    private JournalBatch log(JournalBatch batch) {
        // TODO(PostgreSQL): persistir 'batch' (append-only, idempotente por reference) cuando se active la BBDD.
        log.info("Journal batch {} built ({} entries, net cash movement {}); persistence pending PostgreSQL",
                batch.reference(), batch.entries().size(), batch.netCashMovement());
        return batch;
    }
}
