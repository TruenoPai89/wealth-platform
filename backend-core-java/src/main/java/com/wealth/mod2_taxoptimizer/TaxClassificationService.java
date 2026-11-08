package com.wealth.mod2_taxoptimizer;

import com.wealth.ledger.jev.JevRequest;
import com.wealth.ledger.jev.JevRequest.Question;
import com.wealth.ledger.jev.JevResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Servicio de clasificación fiscal del Módulo 2.
 * <p>
 * <strong>Responsabilidad única:</strong> actuar como puerta de entrada a OpenJEV System One
 * para obtener clasificaciones cualitativas sobre escenarios fiscales. Este servicio
 * <em>nunca realiza aritmética financiera</em>; esa responsabilidad pertenece exclusivamente
 * a {@link FifoCalculatorService}.
 * <p>
 * <strong>Separación de responsabilidades (Regla de Arquitectura Crítica):</strong>
 * <ol>
 *   <li><strong>Este servicio → OpenJEV:</strong> envía contexto estructurado y recibe
 *       clasificaciones cualitativas ({@link TaxClassification}).</li>
 *   <li><strong>{@link FifoCalculatorService}:</strong> ejecuta toda la aritmética FIFO
 *       y de tramos IRPF con {@code BigDecimal}.</li>
 *   <li><strong>{@link TaxEvaluatorAdapter}:</strong> orquesta ambos y construye el
 *       {@link com.wealth.shared.spi.TaxEvaluatorPort.FrictionCost} final.</li>
 * </ol>
 * <p>
 * <strong>Resiliencia:</strong> si OpenJEV no está disponible o devuelve un error,
 * este servicio devuelve {@link TaxClassification#conservativeFallback()} para que el
 * adapter pueda seguir operando con la lógica local completa. El módulo nunca falla
 * por indisponibilidad de OpenJEV.
 *
 * <h3>Modelo OpenJEV utilizado: {@value MODEL_TAX_CLASSIFIER}</h3>
 * <p>
 * Las preguntas enviadas a OpenJEV son clasificaciones binarias o de categoría:
 * <ul>
 *   <li>{@code "has_significant_tax_impact"} — ¿La operación tiene impacto fiscal relevante?</li>
 *   <li>{@code "two_month_risk_level"} — Nivel de riesgo de la regla de los 2 meses.</li>
 *   <li>{@code "recommend_harvesting"} — ¿Recomienda tax-loss harvesting en este contexto?</li>
 *   <li>{@code "lot_age_profile"} — Perfil de antigüedad del lote más antiguo afectado.</li>
 * </ul>
 */
@Service
public class TaxClassificationService {

    private static final Logger log = LoggerFactory.getLogger(TaxClassificationService.class);

    /** Identificador del modelo OpenJEV para clasificación fiscal del Módulo 2. */
    static final String MODEL_TAX_CLASSIFIER = "tax-classifier-v1";

    // ---- Claves de preguntas (deben coincidir con las claves de JevResponse.decisions) ----

    /** ¿La operación tiene impacto fiscal significativo para el contribuyente? */
    static final String Q_HAS_SIGNIFICANT_IMPACT = "has_significant_tax_impact";

    /** Nivel de riesgo de la regla de los 2 meses: "none", "low", "medium", "high". */
    static final String Q_TWO_MONTH_RISK = "two_month_risk_level";

    /** ¿Es recomendable aplicar tax-loss harvesting en este escenario? */
    static final String Q_RECOMMEND_HARVESTING = "recommend_harvesting";

    /** Perfil de antigüedad del lote más antiguo: "short_term", "medium_term", "long_term". */
    static final String Q_LOT_AGE_PROFILE = "lot_age_profile";

    private final TaxJevGateway jevGateway;

    public TaxClassificationService(TaxJevGateway jevGateway) {
        this.jevGateway = jevGateway;
    }

    // =========================================================================
    // Resultado de clasificación (puramente cualitativo — sin BigDecimal)
    // =========================================================================

    /**
     * Resultado de la clasificación fiscal devuelta por OpenJEV.
     * <p>
     * Todos los campos son clasificaciones cualitativas. El backend Java usa estos
     * valores como gobernadores de lógica, no como operandos matemáticos.
     *
     * @param hasSignificantTaxImpact  {@code true} si OpenJEV considera que la operación
     *                                 tiene un impacto fiscal relevante para el contribuyente.
     * @param twoMonthRiskLevel        nivel de riesgo de la regla de los 2 meses según el
     *                                 contexto. Valores: {@code "none"}, {@code "low"},
     *                                 {@code "medium"}, {@code "high"}.
     * @param recommendHarvesting      {@code true} si OpenJEV recomienda aplicar estrategia
     *                                 de tax-loss harvesting para este ISIN y contexto.
     * @param lotAgeProfile            perfil de antigüedad del lote más antiguo afectado.
     *                                 Valores: {@code "short_term"} (<1 año),
     *                                 {@code "medium_term"} (1-3 años),
     *                                 {@code "long_term"} (>3 años).
     * @param fromFallback             {@code true} si esta clasificación proviene del
     *                                 fallback conservador (OpenJEV no disponible).
     */
    public record TaxClassification(
            boolean hasSignificantTaxImpact,
            String twoMonthRiskLevel,
            boolean recommendHarvesting,
            String lotAgeProfile,
            boolean fromFallback) {

        /** Nivel de riesgo de la regla de los 2 meses: sin riesgo. */
        public static final String RISK_NONE   = "none";
        /** Nivel de riesgo de la regla de los 2 meses: bajo. */
        public static final String RISK_LOW    = "low";
        /** Nivel de riesgo de la regla de los 2 meses: medio. */
        public static final String RISK_MEDIUM = "medium";
        /** Nivel de riesgo de la regla de los 2 meses: alto. */
        public static final String RISK_HIGH   = "high";

        /** Perfil corto plazo: lote adquirido hace menos de 1 año. */
        public static final String AGE_SHORT  = "short_term";
        /** Perfil medio plazo: lote adquirido entre 1 y 3 años. */
        public static final String AGE_MEDIUM = "medium_term";
        /** Perfil largo plazo: lote adquirido hace más de 3 años. */
        public static final String AGE_LONG   = "long_term";

        /**
         * Fallback conservador usado cuando OpenJEV no está disponible o falla.
         * Asume impacto significativo y riesgo medio para garantizar que el backend
         * ejecute la validación completa con sus propias reglas locales.
         */
        public static TaxClassification conservativeFallback() {
            return new TaxClassification(
                    true,       // asumir impacto significativo
                    RISK_MEDIUM, // riesgo medio conservador
                    false,      // no recomendar harvesting sin clasificación fiable
                    AGE_SHORT,  // asumir corto plazo (más conservador fiscalmente)
                    true);
        }

        /** ¿Hay riesgo alto o medio de violación de la regla de los 2 meses? */
        public boolean hasMeaningfulTwoMonthRisk() {
            return RISK_HIGH.equals(twoMonthRiskLevel) || RISK_MEDIUM.equals(twoMonthRiskLevel);
        }

        /** ¿El lote más antiguo tiene antigüedad larga (> 3 años)? */
        public boolean isLongTermHold() {
            return AGE_LONG.equals(lotAgeProfile);
        }
    }

    // =========================================================================
    // API pública
    // =========================================================================

    /**
     * Clasifica fiscalmente un escenario de venta FIFO usando OpenJEV.
     * <p>
     * <strong>Qué hace OpenJEV aquí:</strong> recibe el contexto del escenario (ISIN,
     * ganancia/pérdida estimada, fecha, antigüedad del lote más antiguo) y devuelve
     * clasificaciones cualitativas sobre el impacto fiscal, el riesgo de la regla de
     * los 2 meses y si es conveniente aplicar harvesting.
     * <p>
     * <strong>Qué NO hace OpenJEV:</strong> no calcula plusvalías, no aplica tramos IRPF,
     * no determina cantidades monetarias. Solo clasifica el escenario.
     *
     * @param isin              ISIN del instrumento a vender.
     * @param saleDate          fecha de la venta teórica.
     * @param estimatedGainLoss plusvalía (+) o minusvalía (-) ya calculada por
     *                          {@link FifoCalculatorService} en {@code BigDecimal}.
     * @param oldestLotDate     fecha de compra del lote FIFO más antiguo que se vería afectado;
     *                          {@code null} si no hay lotes históricos.
     * @param affectedLotCount  número de lotes que se consumirían total o parcialmente.
     * @return clasificación fiscal cualitativa; nunca {@code null}.
     */
    public TaxClassification classify(String isin,
                                      LocalDate saleDate,
                                      BigDecimal estimatedGainLoss,
                                      LocalDate oldestLotDate,
                                      int affectedLotCount) {
        try {
            JevRequest request = buildRequest(isin, saleDate, estimatedGainLoss,
                    oldestLotDate, affectedLotCount);
            JevResponse response = jevGateway.classify(request);
            return parseClassification(response);
        } catch (Exception e) {
            log.warn("OpenJEV classification failed for ISIN={}, saleDate={}. " +
                     "Using conservative fallback. Cause: {}", isin, saleDate, e.getMessage());
            return TaxClassification.conservativeFallback();
        }
    }

    // =========================================================================
    // Construcción del request (paquete-privado para tests)
    // =========================================================================

    /**
     * Construye el {@link JevRequest} para la clasificación fiscal.
     * Visible a nivel de paquete para facilitar tests unitarios sin mocking complejo.
     */
    JevRequest buildRequest(String isin,
                            LocalDate saleDate,
                            BigDecimal estimatedGainLoss,
                            LocalDate oldestLotDate,
                            int affectedLotCount) {

        // Estado del escenario: contexto estructurado en texto para OpenJEV
        String state = buildStateContext(isin, saleDate, estimatedGainLoss,
                oldestLotDate, affectedLotCount);

        // Preguntas de clasificación — OpenJEV NO hace aritmética, solo clasifica
        Map<String, Question> questions = Map.of(

            Q_HAS_SIGNIFICANT_IMPACT, new Question(
                "boolean",
                "Evalúa si esta operación de venta tiene un impacto fiscal significativo " +
                "para un contribuyente español del IRPF, considerando el importe estimado " +
                "de ganancia/pérdida, el número de lotes afectados y la fecha de operación.",
                List.of(
                    "La ganancia o pérdida estimada supera 1.000 EUR",
                    "Hay más de un lote fiscal afectado",
                    "La operación se realiza cerca del cierre del ejercicio fiscal (octubre-diciembre)"
                )),

            Q_TWO_MONTH_RISK, new Question(
                "category",
                "Clasifica el nivel de riesgo de que esta venta con pérdida potencial " +
                "quede sujeta a la regla anti-aplicación de la AEAT (art. 33.5 LIRPF), " +
                "que difiere la minusvalía si se recompra el mismo valor en ±60 días.",
                List.of(
                    "none: La operación genera plusvalía, la regla no aplica",
                    "low: Genera minusvalía pero el plan de rebalanceo no prevé recompra en 60 días",
                    "medium: Genera minusvalía y hay indicios de recompra potencial en la ventana",
                    "high: Genera minusvalía y el rebalanceo incluye recompra del mismo ISIN"
                )),

            Q_RECOMMEND_HARVESTING, new Question(
                "boolean",
                "¿Es recomendable desde el punto de vista del tax-loss harvesting aplicar " +
                "esta venta para materializar la minusvalía y compensarla con otras plusvalías " +
                "del ejercicio, dado el contexto fiscal del contribuyente?",
                List.of(
                    "La operación genera minusvalía materializable",
                    "Existen plusvalías en la cartera con las que compensar",
                    "No hay riesgo alto de la regla de los 2 meses"
                )),

            Q_LOT_AGE_PROFILE, new Question(
                "category",
                "Clasifica el perfil de antigüedad del lote más antiguo que se vería " +
                "afectado por esta venta FIFO, según la fecha de adquisición.",
                List.of(
                    "short_term: El lote fue adquirido hace menos de 1 año",
                    "medium_term: El lote fue adquirido entre 1 y 3 años",
                    "long_term: El lote fue adquirido hace más de 3 años"
                ))
        );

        return new JevRequest(state, MODEL_TAX_CLASSIFIER, questions);
    }

    // =========================================================================
    // Parseo de la respuesta (paquete-privado para tests)
    // =========================================================================

    /**
     * Convierte el {@link JevResponse} de OpenJEV en un {@link TaxClassification}.
     * Si la respuesta no es fiable (status ≠ "ok"), aplica el fallback conservador.
     */
    TaxClassification parseClassification(JevResponse response) {
        if (response == null || !response.isReliable()) {
            log.warn("OpenJEV returned unreliable response (status={}). Using conservative fallback.",
                    response == null ? "null" : response.status());
            return TaxClassification.conservativeFallback();
        }

        boolean hasImpact       = response.decisionAsBoolean(Q_HAS_SIGNIFICANT_IMPACT);
        String  twoMonthRisk    = response.decisionAsString(Q_TWO_MONTH_RISK)
                                          .orElse(TaxClassification.RISK_MEDIUM);
        boolean recHarvesting   = response.decisionAsBoolean(Q_RECOMMEND_HARVESTING);
        String  lotAgeProfile   = response.decisionAsString(Q_LOT_AGE_PROFILE)
                                          .orElse(TaxClassification.AGE_SHORT);

        return new TaxClassification(hasImpact, twoMonthRisk, recHarvesting, lotAgeProfile, false);
    }

    // =========================================================================
    // Helpers privados
    // =========================================================================

    /**
     * Construye el texto de contexto del estado del escenario para OpenJEV.
     * El formato es legible en lenguaje natural para que el modelo lo procese.
     * Los valores monetarios se pasan como texto (OpenJEV NO los calcula).
     */
    private static String buildStateContext(String isin,
                                            LocalDate saleDate,
                                            BigDecimal estimatedGainLoss,
                                            LocalDate oldestLotDate,
                                            int affectedLotCount) {
        String gainLossDescription = estimatedGainLoss.signum() >= 0
                ? "plusvalía estimada de " + estimatedGainLoss.toPlainString() + " EUR"
                : "minusvalía estimada de " + estimatedGainLoss.abs().toPlainString() + " EUR";

        String lotAgeDescription = oldestLotDate != null
                ? "El lote más antiguo fue adquirido el " + oldestLotDate
                : "No hay lotes históricos registrados para este instrumento";

        return String.format(
                "Escenario de venta fiscal:\n" +
                "- Instrumento (ISIN): %s\n" +
                "- Fecha de venta teórica: %s\n" +
                "- Resultado fiscal FIFO calculado por el backend Java: %s\n" +
                "- Número de lotes FIFO afectados: %d\n" +
                "- %s\n" +
                "- Mercado: España (IRPF, régimen general del ahorro, art. 33-37 LIRPF)",
                isin,
                saleDate,
                gainLossDescription,
                affectedLotCount,
                lotAgeDescription);
    }
}
