package com.wealth.mod4_pledge;

import com.wealth.ledger.jev.JevLedgerAdapter;
import com.wealth.shared.web.ApiEnvelope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * API REST del análisis de margin call (módulo 4). Responde con el envoltorio {@code { "data": ..., "error": null }}.
 * No guarda nada: es un cálculo sin efectos sobre la cartera ni sobre el contrato guardado en {@code /pledge}.
 */
@RestController
@RequestMapping("/api/v1/margin")
public class MarginController {

    private final MarginCallService service;
    private final JevLedgerAdapter ledger;

    public MarginController(MarginCallService service, JevLedgerAdapter ledger) {
        this.service = service;
        this.ledger = ledger;
    }

    @PostMapping("/analyze")
    public ApiEnvelope<MarginRiskAnalysisDto> analyze(@RequestBody PledgeConfigDto config) {
        String portfolioId = MarginCallService.portfolioIdOf(config);
        return ApiEnvelope.ok(service.analyze(ledger.loadPortfolio(portfolioId), config));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiEnvelope<Void>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiEnvelope.fail(e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiEnvelope<Void>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiEnvelope.fail("El cuerpo de la petición no es un JSON válido"));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiEnvelope<Void>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiEnvelope.fail(e.getMessage()));
    }
}
