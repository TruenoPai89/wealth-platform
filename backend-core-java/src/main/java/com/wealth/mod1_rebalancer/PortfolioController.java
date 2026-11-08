package com.wealth.mod1_rebalancer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * API del modulo 1.
 * <ul>
 *   <li>GET  /{id}/rebalance: plan de rebalanceo (mismo JSON que contracts/mocks-contracts/rebalancer_base_mod1.json).</li>
 *   <li>POST /{id}/rebalance/execute: acepta el plan; genera asientos y transferencias y actualiza la cartera.</li>
 *   <li>GET  /{id}/transfers: historial de movimientos (compras, ventas, ajuste de efectivo).</li>
 *   <li>GET  /{id}/targets, PUT /{id}/targets: meta de balanceo del cliente.</li>
 * </ul>
 * CORS: la configuracion GLOBAL de shared/web/CorsConfig ya cubre /api/** (GET, POST, PUT, OPTIONS) para los origenes de
 * wealth.cors.allowed-origins. La anotacion de clase fija ademas http://localhost:3000 de forma explicita; el
 * preflight se resuelve contra la union de ambas, asi que no se abre ningun origen adicional.
 */
@RestController
@RequestMapping("/api/v1/portfolios")
@CrossOrigin(origins = "http://localhost:3000",
        methods = {org.springframework.web.bind.annotation.RequestMethod.GET,
                org.springframework.web.bind.annotation.RequestMethod.POST,
                org.springframework.web.bind.annotation.RequestMethod.PUT,
                org.springframework.web.bind.annotation.RequestMethod.OPTIONS})
public class PortfolioController {

    private final RebalanceOrchestrator orchestrator;
    private final TargetAllocationService targets;
    private final RebalanceExecutionService execution;

    public PortfolioController(RebalanceOrchestrator orchestrator, TargetAllocationService targets,
                               RebalanceExecutionService execution) {
        this.orchestrator = orchestrator;
        this.targets = targets;
        this.execution = execution;
    }

    @GetMapping("/{portfolioId}/rebalance")
    public RebalancePlan rebalance(@PathVariable String portfolioId) {
        return orchestrator.rebalance(portfolioId);
    }

    @PostMapping("/{portfolioId}/rebalance/execute")
    public RebalanceExecution execute(@PathVariable String portfolioId) {
        return execution.execute(portfolioId);
    }

    @GetMapping("/{portfolioId}/transfers")
    public List<Transfer> transfers(@PathVariable String portfolioId) {
        return execution.history(portfolioId);
    }

    @GetMapping("/{portfolioId}/targets")
    public Map<String, BigDecimal> targets(@PathVariable String portfolioId) {
        return targets.targetsFor(portfolioId);
    }

    @PutMapping("/{portfolioId}/targets")
    public Map<String, BigDecimal> updateTargets(@PathVariable String portfolioId,
                                                 @RequestBody Map<String, BigDecimal> newTargets) {
        return targets.update(portfolioId, newTargets);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RebalanceExecutionService.NothingToExecuteException.class)
    public ResponseEntity<Map<String, String>> nothingToExecute(RebalanceExecutionService.NothingToExecuteException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
