package com.wealth.ledger.jev;

import com.wealth.ledger.jev.JournalEntryModel.EntryType;
import com.wealth.ledger.jev.JournalEntryModel.JournalBatch;
import com.wealth.ledger.jev.JournalEntryModel.JournalEntry;
import com.wealth.ledger.jev.JournalEntryModel.LineSide;
import com.wealth.mod1_rebalancer.RebalanceOrchestrator;
import com.wealth.mod1_rebalancer.RebalancePlan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JevTransactionServiceTest {

    @Autowired
    private RebalanceOrchestrator orchestrator;

    @Autowired
    private JevTransactionService journal;

    @Test
    void mapsEveryOrderToABalancedDoubleEntry() {
        RebalancePlan plan = orchestrator.rebalance("PF-001");

        JournalBatch batch = journal.buildJournal(plan);

        assertThat(batch.reference()).isEqualTo("REB-PF-001-20261108T090000Z");
        assertThat(batch.entries()).hasSize(plan.orders().size()).allMatch(JournalEntry::isBalanced);
        assertThat(batch.entries()).extracting(JournalEntry::type)
                .containsExactly(EntryType.SELL, EntryType.SELL, EntryType.BUY, EntryType.BUY);
        assertThat(batch.entries()).extracting(JournalEntry::entryId).doesNotHaveDuplicates();
    }

    @Test
    void buyDebitsCustodyAndCreditsCash() {
        JournalBatch batch = journal.buildJournal(orchestrator.rebalance("PF-001"));

        JournalEntry buy = batch.entries().stream()
                .filter(e -> e.type() == EntryType.BUY
                        && e.lines().get(0).account().equals("ASSETS:CUSTODY:IE00B4L5Y983"))
                .findFirst().orElseThrow();
        assertThat(buy.lines().get(0).side()).isEqualTo(LineSide.DEBIT);
        assertThat(buy.lines().get(0).amount()).isEqualByComparingTo("7500.00");
        assertThat(buy.lines().get(0).quantity()).isEqualByComparingTo("78.9474");
        assertThat(buy.lines().get(1).account()).isEqualTo("ASSETS:CASH:EUR");
        assertThat(buy.lines().get(1).side()).isEqualTo(LineSide.CREDIT);
        assertThat(buy.lines().get(1).quantity()).isNull();
    }

    @Test
    void sellDebitsCashAndCreditsCustody() {
        JournalBatch batch = journal.buildJournal(orchestrator.rebalance("PF-001"));

        JournalEntry sell = batch.entries().stream()
                .filter(e -> e.type() == EntryType.SELL
                        && e.lines().get(1).account().equals("ASSETS:CUSTODY:IE00B579F325"))
                .findFirst().orElseThrow();
        assertThat(sell.lines().get(0).account()).isEqualTo("ASSETS:CASH:EUR");
        assertThat(sell.lines().get(0).side()).isEqualTo(LineSide.DEBIT);
        assertThat(sell.lines().get(0).amount()).isEqualByComparingTo("8500.00");
        assertThat(sell.lines().get(1).side()).isEqualTo(LineSide.CREDIT);
    }

    @Test
    void netCashMovementMatchesPlanCashFlow() {
        RebalancePlan plan = orchestrator.rebalance("PF-001");

        JournalBatch batch = journal.record(plan);

        assertThat(batch.netCashMovement()).isEqualByComparingTo(plan.cashFlow().netCashFlow());
        assertThat(batch.netCashMovement()).isEqualByComparingTo(new BigDecimal("-3000.00"));
    }

    @Test
    void samePlanProducesSameJournalIds() {
        RebalancePlan plan = orchestrator.rebalance("PF-001");

        assertThat(journal.buildJournal(plan)).isEqualTo(journal.buildJournal(plan));
    }
}
