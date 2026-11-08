package com.wealth.mod2_taxoptimizer;

import com.wealth.mod2_taxoptimizer.TaxClassificationService.TaxClassification;
import com.wealth.shared.domain.RebalanceOrder;
import com.wealth.shared.spi.TaxEvaluatorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Adaptador principal del Módulo 2 (Optimizador Fiscal).
 * <p>
 * Implementa el puerto SPI {@link TaxEvaluatorPort} para que el Módulo 1 (Rebalanceador)
 * pueda consultar el peaje fiscal estimado de cualquier orden de venta <strong>sin
 * acoplarse a los detalles de FIFO, regla de los 2 meses ni a OpenJEV</strong>.
 *
 * <h3>Flujo interno de {@link #estimateFrictionCost(RebalanceOrder)}:</h3>
 * <ol>
 *   <li><strong>Short-circuit BUY:</strong> las órdenes de compra devuelven inmediatamente
 *       {@link FrictionCost#ZERO} (las compras no tributan en el momento de la compra).</li>
 *   <li><strong>FIFO Java (aritmética):</strong> {@link FifoCalculatorService#estimateSale}
 *       calcula qué lotes se ven afectados, la plusvalía/minusvalía total y el impuesto IRPF
 *       estimado. Todo con {@code BigDecimal}. Esta operación es de <em>solo lectura</em>.</li>
 *   <li><strong>Clasificación OpenJEV (cualitativa):</strong>
 *       {@link TaxClassificationService#classify} envía el contexto del resultado FIFO
 *       a OpenJEV para obtener clasificaciones sobre impacto fiscal, riesgo de la regla
 *       de los 2 meses y conveniencia del tax-loss harvesting. OpenJEV <em>jamás realiza
 *       aritmética financiera</em>; solo clasifica.</li>
 *   <li><strong>Validación local regla 2 meses:</strong>
 *       {@link TwoMonthRuleValidator#validate} comprueba contra el historial local si hay
 *       compras en la ventana ±60 días. Se ejecuta siempre como safety net, independientemente
 *       de lo que diga OpenJEV.</li>
 *   <li><strong>Enriquecimiento del resultado:</strong> se construye el {@link FrictionCost}
 *       final combinando la aritmética Java (el impuesto) con la clasificación OpenJEV
 *       (el warning enriquecido y el flag de violación). La clasificación OpenJEV puede
 *       añadir contexto al warning pero nunca modifica los valores monetarios calculados.</li>
 * </ol>
 *
 * <h3>Resiliencia:</h3>
 * Si OpenJEV no está disponible, {@link TaxClassificationService} devuelve un fallback
 * conservador y el adapter continúa operando con la validación local completa.
 * El Módulo 2 nunca falla por indisponibilidad de OpenJEV.
 */
@Component
public class TaxEvaluatorAdapter implements TaxEvaluatorPort {

    private static final Logger log = LoggerFactory.getLogger(TaxEvaluatorAdapter.class);

    /**
     * Cartera por defecto usada cuando la orden no incluye portfolioId explícito.
     * El Módulo 1 siempre pasa el portfolioId real; este valor es el fallback para
     * llamadas directas desde tests de integración o herramientas externas.
     */
    static final String DEFAULT_PORTFOLIO = "PF-001";

    private final FifoCalculatorService    fifoCalculator;
    private final TwoMonthRuleValidator    twoMonthValidator;
    private final TaxClassificationService classificationService;

    public TaxEvaluatorAdapter(FifoCalculatorService fifoCalculator,
                               TwoMonthRuleValidator twoMonthValidator,
                               TaxClassificationService classificationService) {
        this.fifoCalculator        = fifoCalculator;
        this.twoMonthValidator     = twoMonthValidator;
        this.classificationService = classificationService;
    }

    // =========================================================================
    // Implementación del puerto SPI
    // =========================================================================

    /**
     * {@inheritDoc}
     * <p>
     * Las órdenes BUY devuelven inmediatamente {@link FrictionCost#ZERO}.
     * Para SELL: FIFO (Java, aritmética) → OpenJEV (clasificación) → validación 2 meses (local).
     */
    @Override
    public FrictionCost estimateFrictionCost(RebalanceOrder order) {
        if (order == null) return FrictionCost.ZERO;

        // Las compras no generan peaje fiscal inmediato
        if (order.side() == RebalanceOrder.Side.BUY) {
            return FrictionCost.ZERO;
        }

        String    isin     = order.instrumentId();
        LocalDate saleDate = LocalDate.now();

        // ------------------------------------------------------------------
        // PASO 1 — Aritmética FIFO (Java puro, BigDecimal, solo lectura)
        // OpenJEV no participa aquí. Toda la matemática financiera es Java.
        // ------------------------------------------------------------------
        FifoCalculatorService.FifoResult fifo = fifoCalculator.estimateSale(
                DEFAULT_PORTFOLIO, isin, saleDate, order.quantity(), order.price());

        // ------------------------------------------------------------------
        // PASO 2 — Clasificación cualitativa por OpenJEV (no hace aritmética)
        // OpenJEV recibe el contexto del resultado FIFO (ya calculado) y devuelve
        // clasificaciones como "¿impacto significativo?", "¿riesgo regla 2 meses?".
        // ------------------------------------------------------------------
        LocalDate oldestLotDate = fifo.affectedLots().isEmpty()
                ? null
                : fifo.affectedLots().get(0).lot().purchaseDate();

        TaxClassification classification = classificationService.classify(
                isin,
                saleDate,
                fifo.totalGainLoss(),           // ya calculado en BigDecimal por el paso 1
                oldestLotDate,
                fifo.affectedLots().size());

        log.debug("OpenJEV tax classification for ISIN={}: impact={}, twoMonthRisk={}, " +
                  "harvesting={}, lotAge={}, fromFallback={}",
                isin,
                classification.hasSignificantTaxImpact(),
                classification.twoMonthRiskLevel(),
                classification.recommendHarvesting(),
                classification.lotAgeProfile(),
                classification.fromFallback());

        // ------------------------------------------------------------------
        // PASO 3 — Validación local de la regla de los 2 meses (safety net)
        // Se ejecuta siempre. OpenJEV enriquece el warning pero no lo reemplaza.
        // La validación local es autoritativa sobre la violación de la regla.
        // ------------------------------------------------------------------
        TwoMonthRuleValidator.ValidationResult twoMonth = twoMonthValidator.validate(fifo);

        // ------------------------------------------------------------------
        // PASO 4 — Ensamblaje del FrictionCost final
        // Los valores monetarios SIEMPRE provienen del cálculo Java (paso 1).
        // OpenJEV solo puede enriquecer el mensaje de warning cualitativo.
        // ------------------------------------------------------------------
        String warning = buildEnrichedWarning(twoMonth, classification);

        return new FrictionCost(
                fifo.totalGainLoss(),           // BigDecimal calculado en paso 1
                fifo.estimatedTax(),            // BigDecimal calculado en paso 1
                fifo.affectedLots().size(),
                twoMonth.violated(),
                warning);
    }

    // =========================================================================
    // API extendida (para TaxOptimizerController)
    // =========================================================================

    /**
     * Versión enriquecida de la evaluación fiscal que devuelve el detalle completo:
     * resultado FIFO, clasificación OpenJEV y resultado de la regla de los 2 meses.
     * Usada por {@link TaxOptimizerController} para los endpoints de análisis detallados.
     *
     * @param portfolioId  identificador de la cartera.
     * @param isin         ISIN del instrumento.
     * @param quantity     cantidad a vender.
     * @param salePrice    precio de venta unitario.
     * @param saleDate     fecha de la venta teórica.
     * @return análisis fiscal completo con detalle FIFO, clasificación OpenJEV y regla 2 meses.
     */
    public TaxAnalysisResult analyzeFullDetail(String portfolioId,
                                               String isin,
                                               java.math.BigDecimal quantity,
                                               java.math.BigDecimal salePrice,
                                               LocalDate saleDate) {
        // Paso 1: aritmética FIFO (Java, BigDecimal) — toda la matemática financiera
        FifoCalculatorService.FifoResult fifo = fifoCalculator.estimateSale(
                portfolioId, isin, saleDate, quantity, salePrice);

        // Paso 2: clasificación OpenJEV (cualitativa, no aritmética)
        LocalDate oldestLotDate = fifo.affectedLots().isEmpty()
                ? null
                : fifo.affectedLots().get(0).lot().purchaseDate();

        TaxClassification classification = classificationService.classify(
                isin, saleDate, fifo.totalGainLoss(), oldestLotDate, fifo.affectedLots().size());

        // Paso 3: validación local regla 2 meses (siempre se ejecuta)
        TwoMonthRuleValidator.ValidationResult twoMonth = twoMonthValidator.validate(fifo);

        return new TaxAnalysisResult(fifo, classification, twoMonth);
    }

    /**
     * Resultado enriquecido del análisis fiscal para el endpoint REST.
     *
     * @param fifo           resultado FIFO con aritmética BigDecimal (fuente de verdad numérica).
     * @param classification clasificación cualitativa de OpenJEV (enriquecimiento).
     * @param twoMonth       resultado de la validación local de la regla de los 2 meses.
     */
    public record TaxAnalysisResult(
            FifoCalculatorService.FifoResult fifo,
            TaxClassification classification,
            TwoMonthRuleValidator.ValidationResult twoMonth) {

        /**
         * Coste de fricción fiscal resumido compatible con {@link TaxEvaluatorPort}.
         * Los valores monetarios siempre provienen del campo {@link #fifo()}.
         */
        public FrictionCost toFrictionCost() {
            boolean isViolation = twoMonth.violated();
            String  warning     = isViolation ? twoMonth.warning() : null;
            return new FrictionCost(
                    fifo.totalGainLoss(),
                    fifo.estimatedTax(),
                    fifo.affectedLots().size(),
                    isViolation,
                    warning);
        }
    }

    // =========================================================================
    // Helpers privados
    // =========================================================================

    /**
     * Construye el mensaje de warning final combinando:
     * <ol>
     *   <li>La validación local de la regla de los 2 meses (autoritativa).</li>
     *   <li>Las recomendaciones de OpenJEV (enriquecedoras, nunca bloqueantes).</li>
     * </ol>
     * <p>
     * <strong>Invariante:</strong> OpenJEV puede añadir información contextual pero
     * nunca puede suprimir una advertencia de violación detectada localmente.
     */
    private static String buildEnrichedWarning(TwoMonthRuleValidator.ValidationResult twoMonth,
                                               TaxClassification classification) {
        // Sin warning si no hay violación ni recomendación de harvesting
        if (!twoMonth.violated() && !classification.recommendHarvesting()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        // Primero: advertencia de la regla de los 2 meses (autoritativa — validación local)
        if (twoMonth.violated()) {
            sb.append(twoMonth.warning());
        }

        // Segundo: enriquecimiento OpenJEV — oportunidad de harvesting
        if (classification.recommendHarvesting() && !twoMonth.violated()) {
            sb.append("💡 OPORTUNIDAD TAX-HARVESTING: OpenJEV identifica que esta minusvalía ");
            sb.append("podría materializarse para compensar otras plusvalías del ejercicio. ");
            sb.append("Consulte con su asesor fiscal antes de ejecutar.");
        } else if (classification.recommendHarvesting()) {
            sb.append(" | 💡 OpenJEV detecta potencial de tax-harvesting si se gestiona ");
            sb.append("la ventana temporal correctamente.");
        }

        // Tercero: enriquecimiento OpenJEV — contexto del perfil del lote (solo si fiable)
        if (!classification.fromFallback() && classification.isLongTermHold()) {
            sb.append(" | ℹ Perfil del lote más antiguo: largo plazo (>3 años). ");
            sb.append("La plusvalía tributa en la base del ahorro al tipo mínimo del 19%.");
        }

        return sb.length() > 0 ? sb.toString() : null;
    }
}
