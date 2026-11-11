package com.wealth.mod4_pledge;

import com.wealth.mod4_pledge.LtvMonitorService.Zone;
import com.wealth.shared.domain.AssetClass;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Resultado de {@code POST /api/v1/margin/analyze}. Importes en EUR con 2 decimales; proporciones (LTV,
 * probabilidades, volatilidades, caídas) como fracciones con 4 decimales.
 *
 * @param assumptions supuestos del cálculo, en texto, para mostrarlos junto a los números.
 */
public record MarginRiskAnalysisDto(
        String portfolioId,
        Metrics metrics,
        List<FundBreakdown> funds,
        List<HorizonProbability> marginCallProbability,
        List<StressScenario> stressTest,
        List<CurvePoint> drawdownCurve,
        SecurityLevel securityLevel,
        List<Recommendation> recommendations,
        List<String> assumptions) {

    /** Nivel de seguridad frente al margin call: HIGH = poco riesgo. */
    public enum SecurityLevel { LOW, MEDIUM, HIGH }

    /**
     * @param pledgedMarketValue       valor de mercado aportado como garantía.
     * @param collateralValue          valor computable de la garantía (tras descuentos).
     * @param requiredCollateral       garantía mínima para no estar en margin call: deuda / umbral.
     * @param collateralSurplus        garantía disponible - requerida (negativo si ya hay margin call).
     * @param ltv                      deuda / valor computable.
     * @param ltvHeadroom              umbral - LTV, en puntos de LTV (0.18 = 18 puntos).
     * @param distanceToMarginCall     caída (fracción) de la garantía que dispararía el margin call; negativa si ya
     *                                 se ha superado.
     * @param collateralVolatility     volatilidad anual ponderada de la garantía, con correlaciones entre fondos.
     * @param firstYearInterest        intereses del primer año (deuda x tipo).
     * @param totalInterestOverTerm    intereses acumulados al plazo, con capitalización y sin amortizar.
     */
    public record Metrics(
            BigDecimal loanAmount,
            BigDecimal pledgedMarketValue,
            BigDecimal collateralValue,
            BigDecimal requiredCollateral,
            BigDecimal collateralSurplus,
            BigDecimal ltv,
            BigDecimal marginCallLtv,
            BigDecimal ltvHeadroom,
            BigDecimal distanceToMarginCall,
            Zone zone,
            BigDecimal collateralVolatility,
            BigDecimal firstYearInterest,
            BigDecimal totalInterestOverTerm) {}

    /**
     * @param pledgedAmount               valor de mercado aportado.
     * @param positionValue               valor de mercado de toda la posición en la cartera.
     * @param weight                      peso en el valor computable de la garantía.
     * @param riskContribution            parte de la varianza de la garantía que aporta este fondo (suman 1).
     * @param oneSigmaLoss                pérdida de valor computable en un movimiento adverso de 1 desviación
     *                                    típica anual de este fondo.
     * @param standaloneDropToMarginCall  caída de ESTE fondo (él solo) que dispara el margin call; null si ni al
     *                                    100 % lo haría.
     */
    public record FundBreakdown(
            String instrumentId,
            String name,
            AssetClass assetClass,
            BigDecimal pledgedAmount,
            BigDecimal positionValue,
            BigDecimal haircut,
            BigDecimal collateralValue,
            BigDecimal weight,
            BigDecimal annualVolatility,
            BigDecimal riskContribution,
            BigDecimal oneSigmaLoss,
            BigDecimal standaloneDropToMarginCall) {}

    /**
     * @param probability    probabilidad de que el LTV toque el umbral en algún momento del horizonte.
     * @param projectedLoan  deuda al final del horizonte (con intereses capitalizados).
     */
    public record HorizonProbability(int horizonDays, BigDecimal probability, BigDecimal projectedLoan) {}

    /**
     * @param drop         caída aplicada a todos los fondos aportados (0.20 = -20 %).
     * @param topUpNeeded  garantía computable a añadir para salir del margin call (0 si no lo hay).
     * @param repayNeeded  deuda a amortizar para salir del margin call (0 si no lo hay).
     */
    public record StressScenario(
            BigDecimal drop,
            BigDecimal collateralValue,
            BigDecimal ltv,
            Zone zone,
            BigDecimal topUpNeeded,
            BigDecimal repayNeeded) {}

    public record CurvePoint(BigDecimal drop, BigDecimal collateralValue, BigDecimal ltv) {}

    /**
     * @param code    identificador estable de la recomendación (RESTORE_BUFFER, CURE_MARGIN_CALL, CONCENTRATION,
     *                ADD_LOW_VOLATILITY_FUND, NO_ACTION_NEEDED).
     * @param message texto en español listo para mostrar.
     * @param figures cifras que sostienen el mensaje (importes y fracciones), por nombre.
     */
    public record Recommendation(String code, String message, Map<String, BigDecimal> figures) {}
}
