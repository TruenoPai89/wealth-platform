package com.wealth.mod1_rebalancer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.ledger.jev.JevLedgerAdapter;
import com.wealth.shared.domain.Portfolio;
import com.wealth.shared.spi.RebalanceConstraintProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Orquesta el modulo 1: estado actual (OpenJEV) + pesos objetivo + restricciones -> plan de rebalanceo.
 * El calculo es puramente matematico y vive en {@link CashFlowRebalancer}.
 */
@Service
public class RebalanceOrchestrator {

    private final JevLedgerAdapter ledger;
    private final TargetAllocationService targets;
    private final RebalanceConstraintProvider constraints;
    private final CashFlowRebalancer rebalancer;
    private final ObjectMapper objectMapper;

    public RebalanceOrchestrator(JevLedgerAdapter ledger, TargetAllocationService targets,
                                 RebalanceConstraintProvider constraints, CashFlowRebalancer rebalancer,
                                 ObjectMapper objectMapper) {
        this.ledger = ledger;
        this.targets = targets;
        this.constraints = constraints;
        this.rebalancer = rebalancer;
        this.objectMapper = objectMapper;
    }

    public RebalancePlan rebalance(String portfolioId) {
        Portfolio portfolio = ledger.loadPortfolio(portfolioId);
        Map<String, BigDecimal> targetWeights = targets.targetsFor(portfolioId);
        Map<String, BigDecimal> locked = constraints.lockedQuantities(portfolio);
        return rebalancer.rebalance(portfolio, targetWeights, locked);
    }

    /** JSON del contrato (pretty-printed, terminado en salto de linea). */
    public String toContractJson(RebalancePlan plan) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan) + System.lineSeparator();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize rebalance plan", e);
        }
    }

    public void exportContract(RebalancePlan plan, Path target) {
        try {
            Files.writeString(target, toContractJson(plan));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write contract " + target.toAbsolutePath(), e);
        }
    }
}
