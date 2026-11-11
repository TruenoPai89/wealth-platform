package com.wealth.mod3_irpf;

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
 * API REST del resumen fiscal del ejercicio (Modulo 3), base para el Modelo 100.
 * <p>
 * POST /api/v1/irpf/{portfolioId}/annual-report -> ventas del ano, ganancias y perdidas,
 * compensacion, base del ahorro y cuota.
 * <p>
 * Es un borrador orientativo de la parte de ganancias patrimoniales por venta de fondos y ETFs;
 * no es la declaracion completa. La exportacion a CSV la hace el frontend con esta misma respuesta.
 */
@RestController
@RequestMapping("/api/v1/irpf")
public class Modelo100ExportController {

    private final AnnualTaxReportService service;

    public Modelo100ExportController(AnnualTaxReportService service) {
        this.service = service;
    }

    public record SaleDto(String isin, LocalDate saleDate, BigDecimal quantity, BigDecimal salePrice) {}

    /** fiscalYear nulo = ano en curso; carryForward nulo = sin saldos de anos anteriores. */
    public record AnnualReportRequest(Integer fiscalYear, List<SaleDto> sales,
                                      List<IrpfController.CarryForwardDto> carryForward) {}

    @PostMapping("/{portfolioId}/annual-report")
    public AnnualTaxReportService.Report annualReport(@PathVariable String portfolioId,
                                                      @RequestBody AnnualReportRequest req) {
        if (req == null) throw new IllegalArgumentException("Falta el cuerpo de la peticion");
        int year = req.fiscalYear() != null ? req.fiscalYear() : LocalDate.now().getYear();
        List<AnnualTaxReportService.SaleLine> sales = req.sales() == null ? List.of()
                : req.sales().stream()
                    .map(s -> s == null ? null
                            : new AnnualTaxReportService.SaleLine(s.isin(), s.saleDate(), s.quantity(), s.salePrice()))
                    .toList();
        List<LossCarryForwardBucket> buckets = req.carryForward() == null ? List.of()
                : req.carryForward().stream()
                    .map(c -> new LossCarryForwardBucket(c.originYear(), c.amount()))
                    .toList();
        return service.build(portfolioId, year, sales, buckets);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
