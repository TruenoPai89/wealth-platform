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
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
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
        String reference = "REB-" + plan.portfolioId() + "-" + REF_TIME.format(plan.asOf());
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
                    buy ? EntryType.BUY : EntryType.SELL, plan.asOf(), plan.baseCurrency(),
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
        JournalBatch batch = buildJournal(plan);
        // TODO(PostgreSQL): persistir 'batch' (append-only, idempotente por reference) cuando se active la BBDD.
        log.info("Journal batch {} built ({} entries, net cash movement {}); persistence pending PostgreSQL",
                batch.reference(), batch.entries().size(), batch.netCashMovement());
        return batch;
    }
}
