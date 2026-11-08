package com.wealth.mod1_rebalancer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/** Expone el plan de rebalanceo (mismo JSON que contracts/mocks-contracts/rebalancer_base_mod1.json). */
@RestController
@RequestMapping("/api/v1/portfolios")
public class PortfolioController {

    private final RebalanceOrchestrator orchestrator;

    public PortfolioController(RebalanceOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/{portfolioId}/rebalance")
    public RebalancePlan rebalance(@PathVariable String portfolioId) {
        return orchestrator.rebalance(portfolioId);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
