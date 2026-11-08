package com.wealth.mod1_rebalancer;

import com.wealth.mod1_rebalancer.Transfer.Status;
import com.wealth.mod1_rebalancer.Transfer.Type;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El estado En proceso / Completado se calcula al leer, con un reloj controlado (sin esperas reales). */
class TransferStatusTest {

    private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");

    private static Transfer transfer(String id, Instant createdAt, Type type) {
        return new Transfer(id, "EXE-1", "PF-001", createdAt, createdAt.plusSeconds(20), type, "IE00B4L5Y983",
                "iShares", new BigDecimal("1.0000"), new BigDecimal("100.00"), "EUR", "J-1", Status.IN_PROGRESS);
    }

    private static TransferService serviceAt(Instant now) {
        return new TransferService(Clock.fixed(now, ZoneOffset.UTC), 20);
    }

    @Test
    void isInProgressBeforeTheSettlementTime() {
        TransferService service = serviceAt(T0.plusSeconds(19));
        service.recordAll(List.of(transfer("A", T0, Type.BUY)));

        assertThat(service.history("PF-001")).extracting(Transfer::status).containsExactly(Status.IN_PROGRESS);
    }

    @Test
    void isCompletedFromTheSettlementTime() {
        TransferService service = serviceAt(T0.plusSeconds(20));
        service.recordAll(List.of(transfer("A", T0, Type.BUY)));

        assertThat(service.history("PF-001")).extracting(Transfer::status).containsExactly(Status.COMPLETED);
    }

    @Test
    void newestFirstAndKeepsOrderWithinAnExecution() {
        TransferService service = serviceAt(T0.plusSeconds(60));
        service.recordAll(List.of(transfer("OLD-1", T0, Type.SELL), transfer("OLD-2", T0, Type.BUY)));
        service.recordAll(List.of(transfer("NEW-1", T0.plusSeconds(30), Type.SELL),
                transfer("NEW-2", T0.plusSeconds(30), Type.BUY)));

        assertThat(service.history("PF-001")).extracting(Transfer::id)
                .containsExactly("NEW-1", "NEW-2", "OLD-1", "OLD-2");
    }

    @Test
    void filtersByPortfolio() {
        TransferService service = serviceAt(T0);
        service.recordAll(List.of(transfer("A", T0, Type.BUY)));

        assertThat(service.history("PF-OTHER")).isEmpty();
    }

    @Test
    void oldMovementsCompleteWhileNewOnesAreStillInProgress() {
        TransferService service = serviceAt(T0.plusSeconds(25));
        service.recordAll(List.of(transfer("OLD", T0, Type.BUY), transfer("NEW", T0.plusSeconds(10), Type.BUY)));

        assertThat(service.history("PF-001")).extracting(Transfer::id, Transfer::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("NEW", Status.IN_PROGRESS),
                        org.assertj.core.groups.Tuple.tuple("OLD", Status.COMPLETED));
    }
}
