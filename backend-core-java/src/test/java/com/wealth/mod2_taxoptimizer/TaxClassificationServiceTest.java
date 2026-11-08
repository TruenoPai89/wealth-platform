package com.wealth.mod2_taxoptimizer;

import com.wealth.ledger.jev.JevRequest;
import com.wealth.ledger.jev.JevRequest.Question;
import com.wealth.ledger.jev.JevResponse;
import com.wealth.ledger.jev.OpenJevClient;
import com.wealth.mod2_taxoptimizer.TaxClassificationService.TaxClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitarios de {@link TaxClassificationService}.
 * <p>
 * Estrategia de test:
 * <ul>
 *   <li>No usa Spring ni bases de datos.</li>
 *   <li>El {@link TaxJevGateway} se sustituye por implementaciones anónimas (lambdas)
 *       que no hacen llamadas HTTP reales. Esto es posible porque {@link TaxJevGateway}
 *       es una {@code @FunctionalInterface} sin dependencias de Spring.</li>
 *   <li>Se prueba el fallback conservador cuando el gateway lanza excepción.</li>
 *   <li>Se verifica la separación de responsabilidades: el servicio nunca hace
 *       aritmética financiera, solo envía contexto y parsea clasificaciones.</li>
 * </ul>
 */
class TaxClassificationServiceTest {

    private static final String ISIN      = "IE00B4L5Y983";
    private static final LocalDate SALE_DATE  = LocalDate.of(2026, 11, 8);
    private static final LocalDate OLDEST_LOT = LocalDate.of(2022, 11, 10);

    // =========================================================================
    // Fábrica de stubs de TaxJevGateway (implementación funcional, sin NPE)
    // =========================================================================

    /** Crea un gateway stub que siempre devuelve la respuesta dada. */
    private static TaxJevGateway gatewayReturning(JevResponse response) {
        return request -> response;
    }

    /** Crea un gateway stub que siempre lanza OpenJevException (simula OpenJEV caído). */
    private static TaxJevGateway gatewayThrowing() {
        return request -> { throw new OpenJevClient.OpenJevException("Stub: simulated failure", null); };
    }

    /** Gateway capturador: almacena el último request enviado para verificarlo. */
    private static class CapturingGateway implements TaxJevGateway {
        private final JevResponse response;
        JevRequest lastRequest;

        CapturingGateway(JevResponse response) { this.response = response; }

        @Override
        public JevResponse classify(JevRequest request) {
            this.lastRequest = request;
            return response;
        }
    }

    // =========================================================================
    // Helper: fábrica de JevResponse
    // =========================================================================

    private static JevResponse okResponse(boolean hasImpact, String twoMonthRisk,
                                           boolean harvesting, String lotAge) {
        return new JevResponse(
                Map.of(
                    TaxClassificationService.Q_HAS_SIGNIFICANT_IMPACT, hasImpact,
                    TaxClassificationService.Q_TWO_MONTH_RISK,         twoMonthRisk,
                    TaxClassificationService.Q_RECOMMEND_HARVESTING,   harvesting,
                    TaxClassificationService.Q_LOT_AGE_PROFILE,        lotAge
                ),
                JevResponse.STATUS_OK);
    }

    // =========================================================================
    // 1. CONSTRUCCIÓN DEL REQUEST A OPENJEV
    // =========================================================================

    @Nested
    @DisplayName("1. Construcción del JevRequest enviado a OpenJEV")
    class RequestBuildingTests {

        private TaxClassificationService service;

        @BeforeEach
        void setUp() {
            // Para tests de buildRequest no necesitamos gateway real
            service = new TaxClassificationService(gatewayReturning(
                    okResponse(false, TaxClassification.RISK_NONE, false, TaxClassification.AGE_SHORT)));
        }

        @Test
        @DisplayName("El modelo enviado es el correcto")
        void requestUsesCorrectModel() {
            JevRequest req = service.buildRequest(ISIN, SALE_DATE,
                    new BigDecimal("5000.00"), OLDEST_LOT, 2);

            assertThat(req.model()).isEqualTo(TaxClassificationService.MODEL_TAX_CLASSIFIER);
        }

        @Test
        @DisplayName("El request contiene las 4 preguntas de clasificación")
        void requestContainsFourQuestions() {
            JevRequest req = service.buildRequest(ISIN, SALE_DATE,
                    new BigDecimal("5000.00"), OLDEST_LOT, 2);

            assertThat(req.questions()).containsKey(TaxClassificationService.Q_HAS_SIGNIFICANT_IMPACT);
            assertThat(req.questions()).containsKey(TaxClassificationService.Q_TWO_MONTH_RISK);
            assertThat(req.questions()).containsKey(TaxClassificationService.Q_RECOMMEND_HARVESTING);
            assertThat(req.questions()).containsKey(TaxClassificationService.Q_LOT_AGE_PROFILE);
            assertThat(req.questions()).hasSize(4);
        }

        @Test
        @DisplayName("Todas las preguntas tienen tipo, instrucciones y criterios definidos")
        void questionsAreWellFormed() {
            JevRequest req = service.buildRequest(ISIN, SALE_DATE,
                    new BigDecimal("5000.00"), OLDEST_LOT, 2);

            for (Map.Entry<String, Question> entry : req.questions().entrySet()) {
                Question q = entry.getValue();
                assertThat(q.type()).as("type for " + entry.getKey()).isNotBlank();
                assertThat(q.instructions()).as("instructions for " + entry.getKey()).isNotBlank();
                assertThat(q.criteria()).as("criteria for " + entry.getKey()).isNotEmpty();
            }
        }

        @Test
        @DisplayName("El state del request incluye el ISIN, la fecha y la ganancia/pérdida")
        void stateContainsKeyContext() {
            JevRequest req = service.buildRequest(ISIN, SALE_DATE,
                    new BigDecimal("36450.00"), OLDEST_LOT, 1);

            assertThat(req.state()).contains(ISIN);
            assertThat(req.state()).contains("2026-11-08");
            assertThat(req.state()).contains("36450.00");
            assertThat(req.state()).contains("plusvalía");
        }

        @Test
        @DisplayName("El state describe correctamente una minusvalía (abs, sin signo negativo)")
        void stateDescribesLossCorrectly() {
            JevRequest req = service.buildRequest(ISIN, SALE_DATE,
                    new BigDecimal("-800.00"), OLDEST_LOT, 1);

            assertThat(req.state()).contains("minusvalía");
            assertThat(req.state()).contains("800.00");
            // BigDecimal negativo se convierte a abs() en el mensaje — el signo no viaja a OpenJEV
            assertThat(req.state()).doesNotContain("-800.00");
        }

        @Test
        @DisplayName("El state maneja correctamente la ausencia de lote histórico")
        void stateHandlesNullOldestLot() {
            JevRequest req = service.buildRequest(ISIN, SALE_DATE,
                    BigDecimal.ZERO, null, 0);

            assertThat(req.state()).contains("No hay lotes históricos");
        }

        @Test
        @DisplayName("classify() reenvía el request correcto al gateway (verificación de integración)")
        void classifyForwardsRequestToGateway() {
            CapturingGateway capturing = new CapturingGateway(
                    okResponse(true, TaxClassification.RISK_LOW, false, TaxClassification.AGE_LONG));
            TaxClassificationService svc = new TaxClassificationService(capturing);

            svc.classify(ISIN, SALE_DATE, new BigDecimal("5000"), OLDEST_LOT, 1);

            assertThat(capturing.lastRequest).isNotNull();
            assertThat(capturing.lastRequest.model())
                    .isEqualTo(TaxClassificationService.MODEL_TAX_CLASSIFIER);
            assertThat(capturing.lastRequest.state()).contains(ISIN);
        }
    }

    // =========================================================================
    // 2. PARSEO DE LA RESPUESTA DE OPENJEV
    // =========================================================================

    @Nested
    @DisplayName("2. Parseo de JevResponse → TaxClassification")
    class ResponseParsingTests {

        private TaxClassificationService service;

        @BeforeEach
        void setUp() {
            service = new TaxClassificationService(gatewayReturning(null)); // no se usa en estos tests
        }

        @Test
        @DisplayName("Respuesta OK con todos los campos se parsea correctamente")
        void fullResponseParsedCorrectly() {
            JevResponse response = okResponse(
                    true,
                    TaxClassification.RISK_HIGH,
                    false,
                    TaxClassification.AGE_LONG);

            TaxClassification result = service.parseClassification(response);

            assertThat(result.hasSignificantTaxImpact()).isTrue();
            assertThat(result.twoMonthRiskLevel()).isEqualTo(TaxClassification.RISK_HIGH);
            assertThat(result.recommendHarvesting()).isFalse();
            assertThat(result.lotAgeProfile()).isEqualTo(TaxClassification.AGE_LONG);
            assertThat(result.fromFallback()).isFalse();
        }

        @Test
        @DisplayName("Respuesta con status=error → fallback conservador")
        void errorStatusReturnsFallback() {
            JevResponse errorResponse = new JevResponse(Map.of(), JevResponse.STATUS_ERROR);

            TaxClassification result = service.parseClassification(errorResponse);

            assertThat(result.fromFallback()).isTrue();
            assertThat(result.hasSignificantTaxImpact()).isTrue();
            assertThat(result.twoMonthRiskLevel()).isEqualTo(TaxClassification.RISK_MEDIUM);
        }

        @Test
        @DisplayName("Respuesta null → fallback conservador")
        void nullResponseReturnsFallback() {
            TaxClassification result = service.parseClassification(null);

            assertThat(result.fromFallback()).isTrue();
        }

        @Test
        @DisplayName("Campos ausentes en decisions → valores por defecto conservadores")
        void missingDecisionFieldsUseConservativeDefaults() {
            JevResponse partialResponse = new JevResponse(
                    Map.of(TaxClassificationService.Q_HAS_SIGNIFICANT_IMPACT, true),
                    JevResponse.STATUS_OK);

            TaxClassification result = service.parseClassification(partialResponse);

            assertThat(result.twoMonthRiskLevel()).isEqualTo(TaxClassification.RISK_MEDIUM);
            assertThat(result.lotAgeProfile()).isEqualTo(TaxClassification.AGE_SHORT);
            assertThat(result.fromFallback()).isFalse();
        }

        @Test
        @DisplayName("Decisión booleana como String 'true'/'false' se parsea correctamente")
        void booleanDecisionAsStringIsParsed() {
            JevResponse response = new JevResponse(
                    Map.of(
                        TaxClassificationService.Q_HAS_SIGNIFICANT_IMPACT, "true",
                        TaxClassificationService.Q_RECOMMEND_HARVESTING,   "false",
                        TaxClassificationService.Q_TWO_MONTH_RISK,         TaxClassification.RISK_LOW,
                        TaxClassificationService.Q_LOT_AGE_PROFILE,        TaxClassification.AGE_MEDIUM
                    ),
                    JevResponse.STATUS_OK);

            TaxClassification result = service.parseClassification(response);

            assertThat(result.hasSignificantTaxImpact()).isTrue();
            assertThat(result.recommendHarvesting()).isFalse();
        }
    }

    // =========================================================================
    // 3. RESILIENCIA — FALLBACK CUANDO OPENJEV FALLA
    // =========================================================================

    @Nested
    @DisplayName("3. Resiliencia: fallback conservador cuando OpenJEV no está disponible")
    class ResilienceTests {

        @Test
        @DisplayName("Cuando el gateway lanza excepción, classify() devuelve fallback (nunca propaga)")
        void gatewayExceptionReturnsFallback() {
            TaxClassificationService svc = new TaxClassificationService(gatewayThrowing());

            TaxClassification result = svc.classify(
                    ISIN, SALE_DATE, new BigDecimal("-500"), OLDEST_LOT, 1);

            assertThat(result).isNotNull();
            assertThat(result.fromFallback()).isTrue();
        }

        @Test
        @DisplayName("El fallback conservador asume impacto significativo y riesgo medio")
        void fallbackIsConservative() {
            TaxClassification fallback = TaxClassification.conservativeFallback();

            assertThat(fallback.hasSignificantTaxImpact()).isTrue();
            assertThat(fallback.twoMonthRiskLevel()).isEqualTo(TaxClassification.RISK_MEDIUM);
            assertThat(fallback.recommendHarvesting()).isFalse();
            assertThat(fallback.lotAgeProfile()).isEqualTo(TaxClassification.AGE_SHORT);
            assertThat(fallback.fromFallback()).isTrue();
        }

        @Test
        @DisplayName("El fallback conservador tiene hasMeaningfulTwoMonthRisk() == true")
        void fallbackHasMeaningfulRisk() {
            assertThat(TaxClassification.conservativeFallback().hasMeaningfulTwoMonthRisk()).isTrue();
        }

        @Test
        @DisplayName("Respuesta con status=partial → fallback conservador")
        void partialStatusReturnsFallback() {
            TaxClassificationService svc = new TaxClassificationService(
                    gatewayReturning(new JevResponse(Map.of(), JevResponse.STATUS_PARTIAL)));

            TaxClassification result = svc.classify(ISIN, SALE_DATE, BigDecimal.ZERO, null, 0);

            assertThat(result.fromFallback()).isTrue();
        }
    }

    // =========================================================================
    // 4. SEPARACIÓN DE RESPONSABILIDADES — OpenJEV NO hace aritmética
    // =========================================================================

    @Nested
    @DisplayName("4. Separación de responsabilidades: OpenJEV solo clasifica")
    class SeparationOfConcernsTests {

        @Test
        @DisplayName("TaxClassification no contiene ningún BigDecimal (solo clasifica)")
        void taxClassificationContainsNoBigDecimal() {
            TaxClassification c = new TaxClassification(
                    true, TaxClassification.RISK_HIGH, false, TaxClassification.AGE_LONG, false);

            assertThat(c.hasSignificantTaxImpact()).isInstanceOf(Boolean.class);
            assertThat(c.twoMonthRiskLevel()).isInstanceOf(String.class);
            assertThat(c.recommendHarvesting()).isInstanceOf(Boolean.class);
            assertThat(c.lotAgeProfile()).isInstanceOf(String.class);
            assertThat(c.fromFallback()).isInstanceOf(Boolean.class);
        }

        @Test
        @DisplayName("JevRequest no contiene BigDecimal en su estructura de datos")
        void jevRequestContainsNoBigDecimal() {
            TaxClassificationService svc = new TaxClassificationService(
                    gatewayReturning(okResponse(false, TaxClassification.RISK_NONE,
                            false, TaxClassification.AGE_SHORT)));

            JevRequest req = svc.buildRequest(ISIN, SALE_DATE,
                    new BigDecimal("36450.78"), OLDEST_LOT, 2);

            // El state es String (BigDecimal convertido a texto antes de enviarse)
            assertThat(req.state()).isInstanceOf(String.class);
            req.questions().values().forEach(q -> {
                assertThat(q.type()).isInstanceOf(String.class);
                assertThat(q.instructions()).isInstanceOf(String.class);
                assertThat(q.criteria()).allMatch(c -> c instanceof String);
            });
        }

        @Test
        @DisplayName("classify() recibe BigDecimal (del FIFO Java) y devuelve solo strings/booleans")
        void classifyInputIsBigDecimalOutputIsQualitative() {
            TaxClassificationService svc = new TaxClassificationService(
                    gatewayReturning(okResponse(true, TaxClassification.RISK_LOW,
                            true, TaxClassification.AGE_LONG)));

            BigDecimal gainLossCalculatedByJava = new BigDecimal("36450.00");
            TaxClassification result = svc.classify(
                    ISIN, SALE_DATE, gainLossCalculatedByJava, OLDEST_LOT, 2);

            assertThat(result.lotAgeProfile()).isEqualTo(TaxClassification.AGE_LONG);
            assertThat(result.twoMonthRiskLevel()).isEqualTo(TaxClassification.RISK_LOW);
            assertThat(result.hasSignificantTaxImpact()).isTrue();
            assertThat(result.recommendHarvesting()).isTrue();
        }

        @Test
        @DisplayName("JevResponse.isReliable() es false para status=error y partial")
        void jevResponseReliableCheck() {
            assertThat(new JevResponse(Map.of(), JevResponse.STATUS_OK).isReliable()).isTrue();
            assertThat(new JevResponse(Map.of(), JevResponse.STATUS_ERROR).isReliable()).isFalse();
            assertThat(new JevResponse(Map.of(), JevResponse.STATUS_PARTIAL).isReliable()).isFalse();
        }

        @Test
        @DisplayName("JevResponse.decisionAsBoolean() usa false como fallback seguro")
        void jevResponseBooleanFallback() {
            JevResponse response = new JevResponse(Map.of(), JevResponse.STATUS_OK);
            assertThat(response.decisionAsBoolean("nonexistent_key")).isFalse();
        }

        @Test
        @DisplayName("JevResponse.decisionAsString() devuelve Optional.empty() si falta la clave")
        void jevResponseStringFallback() {
            JevResponse response = new JevResponse(Map.of(), JevResponse.STATUS_OK);
            assertThat(response.decisionAsString("nonexistent_key")).isEmpty();
        }
    }

    // =========================================================================
    // 5. MÉTODOS DE CONVENIENCIA DE TaxClassification
    // =========================================================================

    @Nested
    @DisplayName("5. Métodos de conveniencia de TaxClassification")
    class TaxClassificationHelperTests {

        @Test
        @DisplayName("hasMeaningfulTwoMonthRisk() es true para RISK_MEDIUM y RISK_HIGH")
        void meaningfulRiskForMediumAndHigh() {
            assertThat(new TaxClassification(false, TaxClassification.RISK_HIGH,
                    false, TaxClassification.AGE_SHORT, false).hasMeaningfulTwoMonthRisk()).isTrue();
            assertThat(new TaxClassification(false, TaxClassification.RISK_MEDIUM,
                    false, TaxClassification.AGE_SHORT, false).hasMeaningfulTwoMonthRisk()).isTrue();
        }

        @Test
        @DisplayName("hasMeaningfulTwoMonthRisk() es false para RISK_LOW y RISK_NONE")
        void noMeaningfulRiskForLowAndNone() {
            assertThat(new TaxClassification(false, TaxClassification.RISK_LOW,
                    false, TaxClassification.AGE_SHORT, false).hasMeaningfulTwoMonthRisk()).isFalse();
            assertThat(new TaxClassification(false, TaxClassification.RISK_NONE,
                    false, TaxClassification.AGE_SHORT, false).hasMeaningfulTwoMonthRisk()).isFalse();
        }

        @Test
        @DisplayName("isLongTermHold() es true solo para AGE_LONG")
        void longTermHoldOnlyForAgeLong() {
            assertThat(new TaxClassification(false, TaxClassification.RISK_NONE,
                    false, TaxClassification.AGE_LONG, false).isLongTermHold()).isTrue();
            assertThat(new TaxClassification(false, TaxClassification.RISK_NONE,
                    false, TaxClassification.AGE_MEDIUM, false).isLongTermHold()).isFalse();
            assertThat(new TaxClassification(false, TaxClassification.RISK_NONE,
                    false, TaxClassification.AGE_SHORT, false).isLongTermHold()).isFalse();
        }
    }
}
