package com.wealth.mod4_pledge;

import com.wealth.ledger.jev.JevLedgerAdapter;
import com.wealth.shared.domain.AssetClass;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * API REST del Módulo 4 (pignoración / LTV).
 */
@RestController
@RequestMapping("/api/v1/pledge")
public class PledgeController {

    private final PledgeContractStore store;
    private final LtvMonitorService monitor;
    private final JevLedgerAdapter ledger;

    public PledgeController(PledgeContractStore store, LtvMonitorService monitor, JevLedgerAdapter ledger) {
        this.store = store;
        this.monitor = monitor;
        this.ledger = ledger;
    }

    public record ContractRequest(
            String contractId,
            String loanId,
            BigDecimal loanBalance,
            BigDecimal ltvMax,
            BigDecimal ltvMarginCall,
            Integer cureDays,
            Map<AssetClass, BigDecimal> haircuts
    ) {}

    @PostMapping("/{portfolioId}/contract")
    public PledgeContractEntity saveContract(@PathVariable String portfolioId, @RequestBody ContractRequest req) {
        return store.save(new PledgeContractEntity(
                portfolioId,
                req.contractId() != null ? req.contractId() : "PC-" + portfolioId,
                req.loanId() != null ? req.loanId() : "LN-" + portfolioId,
                req.loanBalance(),
                req.ltvMax(),
                req.ltvMarginCall(),
                req.cureDays(),
                req.haircuts()
        ));
    }

    @GetMapping("/{portfolioId}/contract")
    public PledgeContractEntity getContract(@PathVariable String portfolioId) {
        return store.find(portfolioId).orElseThrow(() ->
                new NoSuchElementException("No hay contrato de pignoracion guardado para " + portfolioId));
    }

    @GetMapping("/{portfolioId}/status")
    public LtvMonitorService.PledgeStatus status(
            @PathVariable String portfolioId,
            @RequestParam(defaultValue = "0") BigDecimal equityShock,
            @RequestParam(defaultValue = "30") int horizonDays) {

        return monitor.status(
                ledger.loadPortfolio(portfolioId),
                getContract(portfolioId),
                equityShock,
                horizonDays
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Object> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}