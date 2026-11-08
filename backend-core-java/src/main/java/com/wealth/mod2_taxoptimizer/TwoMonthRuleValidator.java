package com.wealth.mod2_taxoptimizer;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Validador de la regla anti-aplicación de minusvalías de la AEAT (art. 33.5 LIRPF).
 * <p>
 * <strong>Regla de los 2 meses (60 días):</strong> si un contribuyente vende acciones o
 * participaciones con pérdida y, en los <em>dos meses anteriores o posteriores</em> a
 * esa venta, ha comprado (o compra) valores <em>homogéneos</em> (mismo ISIN), la minusvalía
 * queda diferida hasta la venta definitiva de los valores re-adquiridos. La AEAT no
 * la permite compensar en el ejercicio en que se genera.
 * <p>
 * <strong>Ventana temporal:</strong> la norma aplica ±60 días naturales respecto a la
 * fecha de la venta con pérdida.
 * <p>
 * <strong>Importantes matices no modelados aquí (fuera del scope del Módulo 2):</strong>
 * <ul>
 *   <li>La regla se aplica también a Instituciones de Inversión Colectiva (fondos / ETFs)
 *       si cotizan en mercado secundario organizado (lo normal para ETFs en España).</li>
 *   <li>Los valores deben ser "homogéneos" (misma clase: mismo ISIN para fondos/ETFs).</li>
 *   <li>El diferimiento se aplica proporcionalmente si la re-compra es parcial.</li>
 * </ul>
 * <p>
 * Este validador trabaja solo con lotes del {@link TaxLotRepository} (historial conocido).
 * No conoce las órdenes futuras; esas deben inferirse del plan de rebalanceo del Módulo 1.
 */
@Component
public class TwoMonthRuleValidator {

    /** Ventana temporal de la norma: 60 días antes o después de la venta. */
    static final int WINDOW_DAYS = 60;

    private final TaxLotRepository repository;

    public TwoMonthRuleValidator(TaxLotRepository repository) {
        this.repository = repository;
    }

    // =========================================================================
    // API pública
    // =========================================================================

    /**
     * Resultado de la validación de la regla de los 2 meses.
     *
     * @param violated          {@code true} si se detecta una compra homogénea en la ventana ±60 días.
     * @param conflictingDates  fechas de las compras conflictivas detectadas en el historial.
     * @param warning           mensaje descriptivo para mostrar al usuario (nunca {@code null}).
     */
    public record ValidationResult(boolean violated, List<LocalDate> conflictingDates, String warning) {

        /** Resultado limpio: no hay violación. */
        public static ValidationResult ok() {
            return new ValidationResult(false, List.of(),
                    "Operación sin conflicto con la regla de los 2 meses (art. 33.5 LIRPF).");
        }
    }

    /**
     * Evalúa si una venta con minusvalía violaría la regla de los 2 meses.
     * <p>
     * Solo es relevante cuando la venta genera una <strong>minusvalía</strong>
     * ({@code gainLoss < 0}). Si hay plusvalía, se devuelve {@link ValidationResult#ok()}.
     *
     * @param assetIsin   ISIN del instrumento vendido.
     * @param saleDate    fecha de la venta.
     * @param gainLoss    plusvalía (+) / minusvalía (-) calculada por {@link FifoCalculatorService}.
     * @return resultado de la validación; nunca {@code null}.
     */
    public ValidationResult validate(String assetIsin, LocalDate saleDate, java.math.BigDecimal gainLoss) {
        Objects.requireNonNull(assetIsin, "assetIsin");
        Objects.requireNonNull(saleDate, "saleDate");
        Objects.requireNonNull(gainLoss, "gainLoss");

        // La regla solo aplica a minusvalías
        if (gainLoss.signum() >= 0) {
            return ValidationResult.ok();
        }

        LocalDate windowStart = saleDate.minusDays(WINDOW_DAYS);
        LocalDate windowEnd = saleDate.plusDays(WINDOW_DAYS);

        List<LocalDate> purchaseDates = repository.findAllPurchaseDatesByIsin(assetIsin);

        List<LocalDate> conflicting = purchaseDates.stream()
                .filter(d -> !d.isBefore(windowStart) && !d.isAfter(windowEnd))
                .distinct()
                .sorted()
                .toList();

        if (conflicting.isEmpty()) {
            return ValidationResult.ok();
        }

        String warning = buildWarning(assetIsin, saleDate, conflicting, gainLoss);
        return new ValidationResult(true, conflicting, warning);
    }

    /**
     * Variante que acepta un resultado FIFO directamente (el caso de uso habitual).
     *
     * @param result    resultado del análisis FIFO de la venta.
     * @return resultado de la validación de la regla de los 2 meses.
     */
    public ValidationResult validate(FifoCalculatorService.FifoResult result) {
        return validate(result.assetIsin(), result.saleDate(),
                result.totalGainLoss());
    }

    // =========================================================================
    // Helpers privados
    // =========================================================================

    private static String buildWarning(String isin, LocalDate saleDate,
                                        List<LocalDate> conflicting,
                                        java.math.BigDecimal gainLoss) {
        return String.format(
                "⚠ REGLA 2 MESES (art. 33.5 LIRPF): La venta de %s con fecha %s generaría una " +
                "minusvalía de %.2f € que podría quedar DIFERIDA porque existen compras de valores " +
                "homogéneos dentro de la ventana ±%d días: %s. " +
                "Consulte con su asesor fiscal antes de ejecutar esta operación.",
                isin, saleDate, gainLoss.abs(), WINDOW_DAYS, conflicting);
    }
}
