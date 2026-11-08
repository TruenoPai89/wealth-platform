package com.wealth.mod2_taxoptimizer;

import com.wealth.shared.spi.TaxEvaluatorPort.FrictionCost;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Controlador REST del Módulo 2 (Optimizador Fiscal).
 * <p>
 * Expone los siguientes endpoints:
 * <ul>
 *   <li>{@code GET  /api/v1/tax/{portfolioId}/lots/{isin}}      — lotes FIFO abiertos de un instrumento.</li>
 *   <li>{@code GET  /api/v1/tax/{portfolioId}/estimate}         — estimación de coste fiscal de una venta.</li>
 *   <li>{@code GET  /api/v1/tax/{portfolioId}/analyze/{isin}}   — análisis FIFO completo con detalle de lotes.</li>
 *   <li>{@code POST /api/v1/tax/{portfolioId}/lots}             — carga manual de un lote fiscal.</li>
 * </ul>
 * <p>
 * El frontend ({@code TaxHarvestingDrawer.tsx}, {@code TwoMonthWarningBadge.tsx}) consume estos endpoints
 * para mostrar el impacto fiscal antes de confirmar el plan de rebalanceo.
 */
@RestController
@RequestMapping("/api/v1/tax")
public class TaxOptimizerController {

    private final TaxEvaluatorAdapter taxEvaluator;
    private final TaxLotRepository repository;

    public TaxOptimizerController(TaxEvaluatorAdapter taxEvaluator, TaxLotRepository repository) {
        this.taxEvaluator = taxEvaluator;
        this.repository = repository;
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/tax/{portfolioId}/lots/{isin}
    // -------------------------------------------------------------------------

    /**
     * Devuelve los lotes FIFO abiertos (con saldo disponible) de un instrumento.
     * Útil para el panel de Tax Harvesting.
     */
    @GetMapping("/{portfolioId}/lots/{isin}")
    public List<TaxLotSummary> getOpenLots(@PathVariable String portfolioId,
                                           @PathVariable String isin) {
        return repository.findOpenLotsFifo(portfolioId, isin).stream()
                .map(TaxLotSummary::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/tax/{portfolioId}/estimate?isin=&qty=&price=
    // -------------------------------------------------------------------------

    /**
     * Estima el coste de fricción fiscal de vender {@code qty} unidades de {@code isin}
     * al precio {@code price}. No modifica el estado de los lotes.
     *
     * @param portfolioId identificador de la cartera.
     * @param isin        ISIN del instrumento.
     * @param qty         cantidad a vender.
     * @param price       precio de venta unitario.
     * @param saleDate    fecha de la venta (por defecto hoy).
     * @return coste fiscal estimado.
     */
    @GetMapping("/{portfolioId}/estimate")
    public FrictionCost estimateFriction(
            @PathVariable String portfolioId,
            @RequestParam String isin,
            @RequestParam BigDecimal qty,
            @RequestParam BigDecimal price,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate saleDate) {

        LocalDate effectiveSaleDate = saleDate != null ? saleDate : LocalDate.now();
        TaxEvaluatorAdapter.TaxAnalysisResult result =
                taxEvaluator.analyzeFullDetail(portfolioId, isin, qty, price, effectiveSaleDate);
        return result.toFrictionCost();
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/tax/{portfolioId}/analyze/{isin}?qty=&price=&saleDate=
    // -------------------------------------------------------------------------

    /**
     * Análisis FIFO completo: detalla lote a lote el impacto de la venta y el resultado
     * de la regla de los 2 meses.
     */
    @GetMapping("/{portfolioId}/analyze/{isin}")
    public TaxEvaluatorAdapter.TaxAnalysisResult analyzeFullDetail(
            @PathVariable String portfolioId,
            @PathVariable String isin,
            @RequestParam BigDecimal qty,
            @RequestParam BigDecimal price,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate saleDate) {

        LocalDate effectiveSaleDate = saleDate != null ? saleDate : LocalDate.now();
        return taxEvaluator.analyzeFullDetail(portfolioId, isin, qty, price, effectiveSaleDate);
    }

    // -------------------------------------------------------------------------
    // POST /api/v1/tax/{portfolioId}/lots
    // -------------------------------------------------------------------------

    /**
     * Carga manual de un lote fiscal. Usado para seed de datos en entornos de desarrollo
     * y para registrar compras no capturadas automáticamente.
     */
    @PostMapping("/{portfolioId}/lots")
    public TaxLotSummary addLot(@PathVariable String portfolioId,
                                @RequestBody TaxLotRequest request) {
        TaxLotEntity lot = new TaxLotEntity(
                portfolioId,
                request.assetIsin(),
                request.purchaseDate(),
                request.purchasePrice(),
                request.quantity(),
                request.currency() != null ? request.currency() : "EUR",
                request.brokerRef());
        return TaxLotSummary.from(repository.save(lot));
    }

    // -------------------------------------------------------------------------
    // Tipos auxiliares (DTO)
    // -------------------------------------------------------------------------

    /** Vista resumida de un lote fiscal para los endpoints de lectura. */
    public record TaxLotSummary(
            Long id,
            String assetIsin,
            LocalDate purchaseDate,
            BigDecimal purchasePrice,
            BigDecimal quantity,
            BigDecimal availableQuantity,
            String currency) {

        static TaxLotSummary from(TaxLotEntity lot) {
            return new TaxLotSummary(lot.getId(), lot.getAssetIsin(), lot.getPurchaseDate(),
                    lot.getPurchasePrice(), lot.getQuantity(), lot.getAvailableQuantity(),
                    lot.getCurrency());
        }
    }

    /** Petición de creación de un lote fiscal. */
    public record TaxLotRequest(
            String assetIsin,
            LocalDate purchaseDate,
            BigDecimal purchasePrice,
            BigDecimal quantity,
            String currency,
            String brokerRef) {}

    // -------------------------------------------------------------------------
    // Manejo de errores
    // -------------------------------------------------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
