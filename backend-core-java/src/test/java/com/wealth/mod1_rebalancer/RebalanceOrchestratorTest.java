package com.wealth.mod1_rebalancer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.shared.domain.AssetClass;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integracion JEV (mock JSON) -> pesos objetivo (application.yml) -> CashFlowRebalancer -> contrato JSON.
 * Maven ejecuta los tests con cwd = backend-core-java/, de ahi las rutas relativas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RebalanceOrchestratorTest {

    /** Contrato de referencia (Regla 4A): SOLO LECTURA para los tests. Cambiarlo requiere consenso de equipo. */
    private static final Path CONTRACT = Path.of("../contracts/mocks-contracts/rebalancer_base_mod1.json");
    /** Salida generada por el test: unico sitio donde se escribe (target/ esta en .gitignore). */
    private static final Path GENERATED = Path.of("target/contract-output/rebalancer_base_mod1.json");

    @Autowired
    private RebalanceOrchestrator orchestrator;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void rebalancesPortfolioReadFromJevSnapshot() {
        RebalancePlan plan = orchestrator.rebalance("PF-001");

        assertThat(plan.portfolioId()).isEqualTo("PF-001");
        assertThat(plan.totalValue()).isEqualByComparingTo("130000.00");
        assertThat(plan.positions()).hasSize(5); // 4 instrumentos + efectivo como peso mas
        assertThat(plan.orders()).hasSize(4);
        assertThat(plan.constraints().taxFrictionApplied()).isFalse();
        assertThat(plan.constraints().lockedQuantities()).isEmpty();
    }

    @Test
    void unknownPortfolioIsRejected() {
        assertThatThrownBy(() -> orchestrator.rebalance("PF-999")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void exportsContractJsonToTargetAndMatchesReferenceContract() throws IOException {
        RebalancePlan plan = orchestrator.rebalance("PF-001");
        Files.createDirectories(GENERATED.getParent());

        orchestrator.exportContract(plan, GENERATED);

        String generated = Files.readString(GENERATED);
        JsonNode json = objectMapper.readTree(generated);
        assertThat(json.get("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(json.get("module").asText()).isEqualTo("MOD1_REBALANCER");
        assertThat(json.get("asOf").asText()).isEqualTo("2026-11-08T09:00:00Z");
        assertThat(json.get("positions")).hasSize(5);
        assertThat(json.get("orders")).hasSize(4);
        // BigDecimal serializado en notacion plana y con la escala definida (no 1.0E+5, no double)
        assertThat(json.get("totalValue").decimalValue()).isEqualByComparingTo("130000.00");
        assertThat(generated).contains("\"totalValue\" : 130000.00");
        assertThat(generated).doesNotContain("E+").doesNotContain("E-");

        // El contrato de /contracts se lee, nunca se escribe: la salida generada debe coincidir con el.
        JsonNode reference = objectMapper.readTree(Files.readString(CONTRACT));
        assertThat(json).isEqualTo(reference);
    }

    @Test
    void cashPostTradeQuantityHasExactlyTwoDecimals() {
        RebalancePlan plan = orchestrator.rebalance("PF-001");

        RebalancePlan.PositionRow cash = plan.positions().stream()
                .filter(r -> r.assetClass() == AssetClass.CASH).findFirst().orElseThrow();
        assertThat(cash.postTradeQuantity()).isEqualTo(new BigDecimal("6500.00"));
        assertThat(cash.postTradeQuantity().scale()).isEqualTo(2);
        assertThat(plan.cashFlow().cashAfter()).isEqualTo(new BigDecimal("6500.00"));
    }
}
