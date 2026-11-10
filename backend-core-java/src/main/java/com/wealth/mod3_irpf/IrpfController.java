package com.wealth.mod3_irpf;

import com.wealth.mod2_taxoptimizer.TaxEvaluatorAdapter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * API REST del Modulo 3 (simulador IRPF).
 * <p>
 * POST /api/v1/irpf/{portfolioId}/simulate       -> cuota del ejercicio con la situacion dada
 * POST /api/v1/irpf/{portfolioId}/simulate-sale  -> efecto fiscal de una venta hipotetica
 * <p>
 * La plusvalia/minusvalia de la venta la calcula el Modulo 2 (FIFO + regla de los 2 meses).
 * Este controlador NO usa su impuesto estimado: la cuota sale siempre de {@link SavingsTaxScale}.
 */
@RestController
@RequestMapping("/api/v1/irpf")
public class IrpfController {

    private final IrpfSimulationService service;
    private final TaxEvaluatorAdapter taxEvaluator;

    public IrpfController(IrpfSimulationService service, TaxEvaluatorAdapter taxEvaluator) {
        this.service = service;
        this.taxEvaluator = taxEvaluator;
    }

    // ---------------------------------------------------------------- DTOs

    public record CarryForwardDto(int originYear, BigDecimal amount) {}

    /** Situacion fiscal del ejercicio. Campos nulos = 0 / ejercicio actual / sin saldos. */
    public record SituationRequest(Integer fiscalYear, BigDecimal realizedGains,
                                   BigDecimal computableLosses, List<CarryForwardDto> carryForward) {}

    public record SaleRequest(SituationRequest situation, String isin, BigDecimal quantity,
                              BigDecimal salePrice, LocalDate saleDate) {}

    /**
     * @param saleGainLoss         resultado de la venta segun FIFO (+ plusvalia, - minusvalia).
     * @param twoMonthRuleViolation la minusvalia queda diferida por la regla de los 2 meses.
     * @param computableSaleResult  lo que entra en la base este ejercicio (0 si la perdida no computa).
     * @param taxDelta              cuota con la venta menos cuota sin ella (negativo = ahorro).
     */
    public record SaleResponse(int fiscalYear, String isin, BigDecimal quantity, BigDecimal salePrice,
                               BigDecimal saleGainLoss, int affectedLots, boolean twoMonthRuleViolation,
                               BigDecimal computableSaleResult,
                               IrpfSimulationService.Result before, IrpfSimulationService.Result after,
                               BigDecimal taxDelta, String warning) {}

    // ----------------------------------------------------------- Endpoints

    @PostMapping("/{portfolioId}/simulate")
    public IrpfSimulationService.Result simulate(@PathVariable String portfolioId,
                                                 @RequestBody SituationRequest req) {
        return service.simulate(toInput(req));
    }

    @PostMapping("/{portfolioId}/simulate-sale")
    public SaleResponse simulateSale(@PathVariable String portfolioId, @RequestBody SaleRequest req) {
        if (req.isin() == null || req.isin().isBlank()) throw new IllegalArgumentException("isin es obligatorio");
        if (req.quantity() == null || req.quantity().signum() <= 0)
            throw new IllegalArgumentException("quantity debe ser > 0");
        if (req.salePrice() == null || req.salePrice().signum() <= 0)
            throw new IllegalArgumentException("salePrice debe ser > 0");

        LocalDate saleDate = req.saleDate() != null ? req.saleDate() : LocalDate.now();
        IrpfSimulationService.Input input = toInput(req.situation());

        // Modulo 2: FIFO y regla de los 2 meses (solo usamos el resultado, no su impuesto).
        TaxEvaluatorAdapter.TaxAnalysisResult analysis =
                taxEvaluator.analyzeFullDetail(portfolioId, req.isin(), req.quantity(), req.salePrice(), saleDate);
        BigDecimal gainLoss = analysis.fifo().totalGainLoss();
        boolean deferred = gainLoss.signum() < 0 && analysis.twoMonth().violated();
        BigDecimal computable = deferred ? BigDecimal.ZERO : gainLoss;

        IrpfSimulationService.SaleSimulation sim = service.simulateSale(input, computable);

        String warning = null;
        if (deferred) {
            warning = "La perdida no computa este ejercicio por la regla de los 2 meses.";
        } else if (!analysis.fifo().isFullyCovered()) {
            warning = "No hay lotes suficientes para cubrir toda la cantidad; solo se calcula la parte cubierta.";
        }

        return new SaleResponse(input.fiscalYear(), req.isin(), req.quantity(), req.salePrice(),
                gainLoss, analysis.fifo().affectedLots().size(), deferred, computable,
                sim.before(), sim.after(), sim.taxDelta(), warning);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    // ------------------------------------------------------------- Helpers

    private static IrpfSimulationService.Input toInput(SituationRequest req) {
        int year = req != null && req.fiscalYear() != null ? req.fiscalYear() : LocalDate.now().getYear();
        BigDecimal gains = req != null && req.realizedGains() != null ? req.realizedGains() : BigDecimal.ZERO;
        BigDecimal losses = req != null && req.computableLosses() != null ? req.computableLosses() : BigDecimal.ZERO;
        List<LossCarryForwardBucket> buckets = req == null || req.carryForward() == null ? List.of()
                : req.carryForward().stream()
                    .map(c -> new LossCarryForwardBucket(c.originYear(), c.amount()))
                    .toList();
        return new IrpfSimulationService.Input(year, gains, losses, buckets);
    }
}