package com.wealth.mod1_rebalancer;

import com.wealth.ledger.jev.JevLedgerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ejecutar un rebalanceo: transferencias, asientos y estado de la cartera. El contexto se comparte: se limpia el estado. */
@SpringBootTest
@AutoConfigureMockMvc
class RebalanceExecutionTest {

    private static final String BASE = "/api/v1/portfolios/PF-001";
    private static final String FRONTEND = "http://localhost:3000";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TransferService transferService;
    @Autowired
    private JevLedgerAdapter ledger;
    @Autowired
    private TargetAllocationService targets;

    @BeforeEach
    @AfterEach
    void cleanState() {
        transferService.clear();
        ledger.resetPostedState("PF-001");
        targets.reset("PF-001");
    }

    @Test
    void historyIsEmptyBeforeAnyExecution() throws Exception {
        mvc.perform(get(BASE + "/transfers")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void executeRecordsOneTransferPerOrderPlusNetCash() throws Exception {
        mvc.perform(post(BASE + "/rebalance/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(containsString("EXE-PF-001-")))
                .andExpect(jsonPath("$.journalReference").value(containsString("EXE-PF-001-")))
                // 4 ordenes + 1 ajuste neto de efectivo
                .andExpect(jsonPath("$.transfers", hasSize(5)))
                .andExpect(jsonPath("$.transfers[?(@.type=='SELL')]", hasSize(2)))
                .andExpect(jsonPath("$.transfers[?(@.type=='BUY')]", hasSize(2)))
                .andExpect(jsonPath("$.transfers[?(@.type=='CASH_OUT')].amount").value(3000.00))
                .andExpect(jsonPath("$.transfers[?(@.type=='SELL' && @.instrumentId=='IE00B3XXRP09')].amount").value(5500.00))
                .andExpect(jsonPath("$.transfers[?(@.type=='BUY' && @.instrumentId=='IE00B4L5Y983')].quantity").value(78.9474))
                .andExpect(jsonPath("$.transfers[?(@.type=='SELL')].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("IN_PROGRESS"))));
    }

    @Test
    void everyTransferPointsToItsJournalEntry() throws Exception {
        mvc.perform(post(BASE + "/rebalance/execute"))
                .andExpect(status().isOk())
                // Un filtro JsonPath devuelve una lista: se comprueba que contiene el id del asiento (ventas primero => -001)
                .andExpect(jsonPath("$.transfers[?(@.type=='SELL' && @.instrumentId=='IE00B3XXRP09')].journalRef",
                        org.hamcrest.Matchers.hasItem(containsString("EXE-PF-001-"))))
                .andExpect(jsonPath("$.transfers[?(@.type=='SELL' && @.instrumentId=='IE00B3XXRP09')].journalRef",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.endsWith("-001"))))
                // El ajuste de caja apunta al lote, no a un asiento concreto
                .andExpect(jsonPath("$.transfers[?(@.type=='CASH_OUT')].journalRef",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.is(
                                // la referencia del lote es el executionId
                                org.hamcrest.Matchers.startsWith("EXE-PF-001-")))));
    }

    @Test
    void historyListsTheExecutedMovementsAfterwards() throws Exception {
        mvc.perform(post(BASE + "/rebalance/execute")).andExpect(status().isOk());

        mvc.perform(get(BASE + "/transfers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].portfolioId").value("PF-001"))
                .andExpect(jsonPath("$[0].createdAt").exists());
    }

    @Test
    void portfolioReflectsTheExecutionAndNothingIsLeftToRebalance() throws Exception {
        mvc.perform(post(BASE + "/rebalance/execute")).andExpect(status().isOk());

        // Tras ejecutar, las posiciones ya estan en su meta: peso actual = objetivo y sin ordenes pendientes
        mvc.perform(get(BASE + "/rebalance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions[?(@.instrumentId=='IE00B4L5Y983')].currentWeight").value(0.35))
                .andExpect(jsonPath("$.positions[?(@.instrumentId=='CASH:EUR')].marketValue").value(6500.00))
                .andExpect(jsonPath("$.orders", hasSize(0)));
    }

    @Test
    void executingTwiceIsRejectedBecauseThereIsNothingLeftToDo() throws Exception {
        mvc.perform(post(BASE + "/rebalance/execute")).andExpect(status().isOk());

        mvc.perform(post(BASE + "/rebalance/execute"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("already at its target")));
        mvc.perform(get(BASE + "/transfers")).andExpect(jsonPath("$", hasSize(5))); // sin duplicados
    }

    @Test
    void anExecutionAfterChangingTheTargetAddsMoreMovements() throws Exception {
        mvc.perform(post(BASE + "/rebalance/execute")).andExpect(status().isOk());
        targets.update("PF-001", java.util.Map.of("IE00B4L5Y983", new java.math.BigDecimal("0.40"),
                "IE00B3XXRP09", new java.math.BigDecimal("0.15"), "IE00B4WXJJ64", new java.math.BigDecimal("0.30"),
                "IE00B579F325", new java.math.BigDecimal("0.10"), "CASH:EUR", new java.math.BigDecimal("0.05")));

        mvc.perform(post(BASE + "/rebalance/execute")).andExpect(status().isOk());

        // 5 de la primera ejecucion + 3 de la segunda: venta S&P, compra MSCI y un ajuste de caja de 0,01 EUR
        // (residuo de redondear a 4 decimales las participaciones de la primera ejecucion: la caja baja de verdad).
        mvc.perform(get(BASE + "/transfers"))
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[?(@.type=='CASH_OUT' && @.amount==0.01)]", hasSize(1)));
    }

    @Test
    void unknownPortfolioIs404ForExecuteAndHistory() throws Exception {
        mvc.perform(post("/api/v1/portfolios/PF-999/rebalance/execute")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/portfolios/PF-999/transfers")).andExpect(status().isNotFound());
    }

    @Test
    void corsAllowsGetAndPostFromTheFrontend() throws Exception {
        mvc.perform(options(BASE + "/rebalance/execute")
                        .header("Origin", FRONTEND).header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
        mvc.perform(get(BASE + "/transfers").header("Origin", FRONTEND))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));
        mvc.perform(post(BASE + "/rebalance/execute").header("Origin", FRONTEND))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND));
    }

    @Test
    void otherOriginsAreStillRejected() throws Exception {
        mvc.perform(post(BASE + "/rebalance/execute").header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
    }
}
