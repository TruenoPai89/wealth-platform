package com.wealth.mod4_pledge;

import com.wealth.mod4_pledge.LtvMonitorService.Zone;
import com.wealth.mod4_pledge.MarginRiskAnalysisDto.FundBreakdown;
import com.wealth.mod4_pledge.MarginRiskAnalysisDto.SecurityLevel;
import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Cartera = la del mock client_portfolio_jev.json (130.000 EUR de mercado):
 * MSCI World 38.000, S&P 500 31.500, bonos 29.500, oro 21.500, efectivo 9.500.
 * Descuentos del contrato de ejemplo: renta variable 30 %, renta fija 10 %, materias primas 25 %.
 * <p>
 * Caso base: MSCI 38.000 + bonos 29.500 en garantía = 38.000 x 0,70 + 29.500 x 0,90 = 53.150 EUR computables.
 */
class MarginCallServiceTest {

    private static final String MSCI = "IE00B4L5Y983";
    private static final String SP500 = "IE00B3XXRP09";
    private static final String BONDS = "IE00B4WXJJ64";
    private static final String GOLD = "IE00B579F325";

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static AssetPosition pos(String id, AssetClass c, String qty, String price) {
        return new AssetPosition(id, id, id, c, bd(qty), bd(price), "EUR", bd("0"));
    }

    private static final Portfolio PORTFOLIO = new Portfolio("PF-001", "CLI001", "EUR", Instant.EPOCH, List.of(
        pos(MSCI, AssetClass.EQUITY, "400", "95"),
        pos(SP500, AssetClass.EQUITY, "300", "105"),
        pos(BONDS, AssetClass.FIXED_INCOME, "250", "118"),
        pos(GOLD, AssetClass.COMMODITY, "100", "215"),
        AssetPosition.cash("EUR", bd("9500"))
    ));

    /** Mismos supuestos que fund_risk_profile.json. */
    private static final FundRiskProfile RISK = FundRiskProfile.of(
        Map.of(MSCI, 0.15, SP500, 0.16, BONDS, 0.055, GOLD, 0.15),
        Map.of(
            FundRiskProfile.key(MSCI, SP500), 0.95,
            FundRiskProfile.key(MSCI, BONDS), 0.10,
            FundRiskProfile.key(MSCI, GOLD), 0.05,
            FundRiskProfile.key(SP500, BONDS), 0.05,
            FundRiskProfile.key(SP500, GOLD), 0.05,
            FundRiskProfile.key(BONDS, GOLD), 0.15));

    private final MarginCallService service = new MarginCallService(RISK);

    private static PledgeConfigDto config(String loan, String threshold, PledgeConfigDto.CollateralItem... items) {
        return new PledgeConfigDto("PF-001", List.of(items), bd(loan), bd("0.04"), 5, bd(threshold), null);
    }

    private static PledgeConfigDto.CollateralItem item(String id, String amount) {
        return new PledgeConfigDto.CollateralItem(id, bd(amount));
    }

    private MarginRiskAnalysisDto base() {
        return service.analyze(PORTFOLIO, config("30000", "0.70", item(MSCI, "38000"), item(BONDS, "29500")));
    }

    @Nested
    @DisplayName("LTV y colateral")
    class LtvAndCollateral {
        private final MarginRiskAnalysisDto.Metrics m = base().metrics();

        @Test
        void collateralValueAppliesHaircutPerAssetClass() {
            assertThat(m.pledgedMarketValue()).isEqualByComparingTo("67500.00");
            assertThat(m.collateralValue()).isEqualByComparingTo("53150.00");
        }

        @Test
        void ltvIsDebtOverCollateralValue() {
            assertThat(m.ltv()).isEqualByComparingTo("0.5644"); // 30.000 / 53.150
            assertThat(m.zone()).isEqualTo(Zone.OK);
        }

        @Test
        void requiredCollateralAndSurplus() {
            assertThat(m.requiredCollateral()).isEqualByComparingTo("42857.14"); // 30.000 / 0,70
            assertThat(m.collateralSurplus()).isEqualByComparingTo("10292.86");  // 53.150 - 42.857,14
        }

        @Test
        void headroomIsInLtvPointsAndDistanceIsCollateralDrop() {
            assertThat(m.ltvHeadroom()).isEqualByComparingTo("0.1356");          // 0,70 - 0,5644
            assertThat(m.distanceToMarginCall()).isEqualByComparingTo("0.1937"); // 1 - 0,5644 / 0,70
        }

        @Test
        void distanceToMarginCallIsTheDropThatReachesTheThreshold() {
            // Aplicar exactamente esa caída a la garantía deja el LTV en el umbral.
            double drop = m.distanceToMarginCall().doubleValue();
            double ltvAfter = m.loanAmount().doubleValue() / (m.collateralValue().doubleValue() * (1 - drop));
            assertThat(ltvAfter).isCloseTo(0.70, within(0.002));
        }

        @Test
        void interestIsCompoundedOverTheTerm() {
            assertThat(m.firstYearInterest()).isEqualByComparingTo("1200.00");      // 30.000 x 4 %
            assertThat(m.totalInterestOverTerm()).isEqualByComparingTo("6499.59");  // 30.000 x (1,04^5 - 1)
        }

        @Test
        void ltvAtOrAboveThresholdIsMarginCall() {
            // 53.150 x 0,70 = 37.205: con esa deuda el LTV es exactamente el umbral.
            var s = service.analyze(PORTFOLIO, config("37205", "0.70", item(MSCI, "38000"), item(BONDS, "29500")));
            assertThat(s.metrics().zone()).isEqualTo(Zone.MARGIN_CALL);
            assertThat(s.metrics().collateralSurplus().abs()).isLessThan(bd("0.01"));
        }

        @Test
        void zeroLoanHasZeroLtvAndNoRisk() {
            var s = service.analyze(PORTFOLIO, config("0", "0.70", item(MSCI, "38000")));
            assertThat(s.metrics().ltv()).isEqualByComparingTo("0");
            assertThat(s.securityLevel()).isEqualTo(SecurityLevel.HIGH);
            assertThat(s.marginCallProbability()).allSatisfy(p -> assertThat(p.probability()).isEqualByComparingTo("0"));
        }
    }

    @Nested
    @DisplayName("Volatilidad ponderada y desglose por fondo")
    class VolatilityAndBreakdown {

        @Test
        void collateralVolatilityUsesCorrelationsNotTheSimpleAverage() {
            // w = 26.600/53.150 y 26.550/53.150; vol 15 % y 5,5 %; rho 0,10 -> 8,25 % (la media simple sería 10,3 %).
            var sigma = base().metrics().collateralVolatility().doubleValue();
            assertThat(sigma).isCloseTo(0.0825, within(0.0002));
        }

        @Test
        void singleFundVolatilityIsItsOwn() {
            var s = service.analyze(PORTFOLIO, config("20000", "0.70", item(MSCI, "38000")));
            assertThat(s.metrics().collateralVolatility()).isEqualByComparingTo("0.1500");
        }

        @Test
        void perfectlyCorrelatedFundsGiveTheWeightedAverage() {
            var risk = FundRiskProfile.of(Map.of(MSCI, 0.20, BONDS, 0.10),
                    Map.of(FundRiskProfile.key(MSCI, BONDS), 1.0));
            var s = new MarginCallService(risk).analyze(PORTFOLIO,
                    config("20000", "0.70", item(MSCI, "38000"), item(BONDS, "29500")));
            double expected = (26600 * 0.20 + 26550 * 0.10) / 53150;
            assertThat(s.metrics().collateralVolatility().doubleValue()).isCloseTo(expected, within(0.0002));
        }

        @Test
        void weightsAndRiskContributionsSumToOne() {
            var funds = base().funds();
            assertThat(funds.stream().map(FundBreakdown::weight).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isCloseTo(BigDecimal.ONE, within(bd("0.0002")));
            assertThat(funds.stream().map(FundBreakdown::riskContribution).reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isCloseTo(BigDecimal.ONE, within(bd("0.0002")));
        }

        @Test
        void theVolatileFundContributesMostOfTheRiskDespiteEqualWeight() {
            var funds = base().funds();
            FundBreakdown equity = funds.stream().filter(f -> f.instrumentId().equals(MSCI)).findFirst().orElseThrow();
            FundBreakdown bonds = funds.stream().filter(f -> f.instrumentId().equals(BONDS)).findFirst().orElseThrow();
            assertThat(equity.weight().doubleValue()).isCloseTo(bonds.weight().doubleValue(), within(0.01));
            assertThat(equity.riskContribution()).isGreaterThan(bd("0.85"));
            assertThat(bonds.riskContribution()).isLessThan(bd("0.15"));
        }

        @Test
        void standaloneDropIsSurplusOverThatFundsCollateral() {
            var equity = base().funds().stream().filter(f -> f.instrumentId().equals(MSCI)).findFirst().orElseThrow();
            assertThat(equity.collateralValue()).isEqualByComparingTo("26600.00");
            assertThat(equity.standaloneDropToMarginCall()).isEqualByComparingTo("0.3869"); // 10.292,86 / 26.600
        }

        @Test
        void partialAmountOfAFundIsHonoured() {
            var s = service.analyze(PORTFOLIO, config("10000", "0.70", item(MSCI, "10000")));
            assertThat(s.funds().get(0).pledgedAmount()).isEqualByComparingTo("10000.00");
            assertThat(s.metrics().collateralValue()).isEqualByComparingTo("7000.00");
        }

        @Test
        void fundWithoutSpecificDataFallsBackToItsAssetClassVolatility() {
            var empty = new MarginCallService(FundRiskProfile.of(Map.of(), Map.of()));
            var s = empty.analyze(PORTFOLIO, config("10000", "0.70", item(MSCI, "20000")));
            assertThat(s.metrics().collateralVolatility()).isEqualByComparingTo("0.1800"); // renta variable genérica
            assertThat(s.assumptions()).anyMatch(a -> a.contains("clase"));
        }
    }

    @Nested
    @DisplayName("Probabilidad de margin call (30 / 90 / 365 días)")
    class Probability {

        @Test
        void hasThe30_90And365DayHorizons() {
            assertThat(base().marginCallProbability()).extracting(MarginRiskAnalysisDto.HorizonProbability::horizonDays)
                    .containsExactly(30, 90, 365);
        }

        @Test
        void probabilityGrowsWithTheHorizon() {
            // Con 35.000 de deuda (LTV 65,9 % frente a un umbral del 70 %) las tres probabilidades son apreciables.
            var p = service.analyze(PORTFOLIO, config("35000", "0.70", item(MSCI, "38000"), item(BONDS, "29500")))
                    .marginCallProbability();
            assertThat(p.get(0).probability()).isGreaterThan(bd("0.01")).isLessThan(p.get(1).probability());
            assertThat(p.get(1).probability()).isLessThan(p.get(2).probability());
        }

        @Test
        void aComfortableLoanHasNegligibleShortTermProbability() {
            var p = base().marginCallProbability();
            assertThat(p.get(0).probability()).isLessThan(bd("0.001"));
            assertThat(p.get(0).probability()).isLessThanOrEqualTo(p.get(1).probability());
            assertThat(p.get(1).probability()).isLessThan(p.get(2).probability());
        }

        @Test
        void projectedLoanCapitalisesTheInterest() {
            assertThat(base().marginCallProbability().get(2).projectedLoan()).isEqualByComparingTo("31200.00"); // 30.000 x 1,04
        }

        @Test
        void aMoreLeveragedLoanIsMoreLikelyToHitMarginCall() {
            var calm = service.analyze(PORTFOLIO, config("20000", "0.70", item(MSCI, "38000"), item(BONDS, "29500")));
            var tight = service.analyze(PORTFOLIO, config("35000", "0.70", item(MSCI, "38000"), item(BONDS, "29500")));
            assertThat(tight.marginCallProbability().get(2).probability())
                    .isGreaterThan(calm.marginCallProbability().get(2).probability());
        }

        @Test
        void alreadyInMarginCallMeansCertainty() {
            var s = service.analyze(PORTFOLIO, config("50000", "0.70", item(MSCI, "38000"), item(BONDS, "29500")));
            assertThat(s.marginCallProbability()).allSatisfy(p -> assertThat(p.probability()).isEqualByComparingTo("1"));
        }

        @Test
        void matchesTheClosedFormBarrierFormula() {
            // Barrera 1 año = deuda proyectada / umbral / garantía; sigma de la garantía.
            double sigma = base().metrics().collateralVolatility().doubleValue();
            double barrier = (31200.0 / 0.70) / 53150.0;
            double expected = LtvMonitorService.barrierHitProbability(barrier, sigma, 1.0);
            assertThat(base().marginCallProbability().get(2).probability().doubleValue())
                    .isCloseTo(expected, within(0.0006));
        }
    }

    @Nested
    @DisplayName("Test de estrés y curva de caída")
    class Stress {

        @Test
        void hasTheMinus10_20And30Scenarios() {
            assertThat(base().stressTest()).extracting(s -> s.drop().toPlainString())
                    .containsExactly("0.10", "0.20", "0.30");
        }

        @Test
        void tenPercentDropKeepsTheLoanSafeButTwentyDoesNot() {
            var st = base().stressTest();
            assertThat(st.get(0).collateralValue()).isEqualByComparingTo("47835.00");
            assertThat(st.get(0).ltv()).isEqualByComparingTo("0.6272"); // 30.000 / 47.835
            assertThat(st.get(0).zone()).isEqualTo(Zone.OK);
            assertThat(st.get(1).ltv()).isEqualByComparingTo("0.7056"); // 30.000 / 42.520
            assertThat(st.get(1).zone()).isEqualTo(Zone.MARGIN_CALL);
        }

        @Test
        void topUpAndRepayBringTheLoanBackToTheThreshold() {
            var s30 = base().stressTest().get(2);
            assertThat(s30.collateralValue()).isEqualByComparingTo("37205.00");
            assertThat(s30.topUpNeeded()).isEqualByComparingTo("5652.14");  // 42.857,14 - 37.205
            assertThat(s30.repayNeeded()).isEqualByComparingTo("3956.50");  // 30.000 - 0,70 x 37.205
        }

        @Test
        void noTopUpWhenThereIsNoMarginCall() {
            var s10 = base().stressTest().get(0);
            assertThat(s10.topUpNeeded()).isEqualByComparingTo("0");
            assertThat(s10.repayNeeded()).isEqualByComparingTo("0");
        }

        @Test
        void drawdownCurveStartsAtTheCurrentLtvAndLtvRisesAsCollateralFalls() {
            var curve = base().drawdownCurve();
            assertThat(curve.get(0).drop()).isEqualByComparingTo("0");
            assertThat(curve.get(0).ltv()).isEqualByComparingTo("0.5644");
            for (int i = 1; i < curve.size(); i++) {
                assertThat(curve.get(i).ltv()).isGreaterThan(curve.get(i - 1).ltv());
                assertThat(curve.get(i).collateralValue()).isLessThan(curve.get(i - 1).collateralValue());
            }
        }
    }

    @Nested
    @DisplayName("Nivel de seguridad y recomendaciones")
    class LevelAndRecommendations {

        @Test
        void securityLevelRule() {
            assertThat(MarginCallService.securityLevel(Zone.MARGIN_CALL, Zone.MARGIN_CALL, Zone.MARGIN_CALL, 1.0))
                    .isEqualTo(SecurityLevel.LOW);
            assertThat(MarginCallService.securityLevel(Zone.OK, Zone.MARGIN_CALL, Zone.MARGIN_CALL, 0.01))
                    .isEqualTo(SecurityLevel.LOW);
            assertThat(MarginCallService.securityLevel(Zone.OK, Zone.OK, Zone.OK, 0.25)).isEqualTo(SecurityLevel.LOW);
            assertThat(MarginCallService.securityLevel(Zone.OK, Zone.OK, Zone.MARGIN_CALL, 0.01))
                    .isEqualTo(SecurityLevel.MEDIUM);
            assertThat(MarginCallService.securityLevel(Zone.OK, Zone.OK, Zone.OK, 0.10)).isEqualTo(SecurityLevel.MEDIUM);
            assertThat(MarginCallService.securityLevel(Zone.OK, Zone.OK, Zone.OK, 0.09)).isEqualTo(SecurityLevel.HIGH);
        }

        @Test
        void baseCaseIsMediumBecauseMinus20BreaksIt() {
            assertThat(base().securityLevel()).isEqualTo(SecurityLevel.MEDIUM);
        }

        @Test
        void comfortableLoanIsHigh() {
            var s = service.analyze(PORTFOLIO, config("10000", "0.70", item(MSCI, "38000"), item(BONDS, "29500")));
            assertThat(s.securityLevel()).isEqualTo(SecurityLevel.HIGH);
            assertThat(s.recommendations()).extracting(MarginRiskAnalysisDto.Recommendation::code)
                    .containsExactly("NO_ACTION_NEEDED");
        }

        @Test
        void minus30BreakingTheLoanRecommendsRestoringTheBuffer() {
            var codes = base().recommendations().stream().map(MarginRiskAnalysisDto.Recommendation::code).toList();
            assertThat(codes).contains("RESTORE_BUFFER");
            var rec = base().recommendations().stream().filter(r -> r.code().equals("RESTORE_BUFFER")).findFirst().orElseThrow();
            // Garantía necesaria para aguantar -30 %: 42.857,14 / 0,70 = 61.224,49; faltan 8.074,49.
            assertThat(rec.figures().get("addCollateral")).isEqualByComparingTo("8074.49");
        }

        @Test
        void alreadyInMarginCallRecommendsCuringIt() {
            var s = service.analyze(PORTFOLIO, config("50000", "0.70", item(MSCI, "38000"), item(BONDS, "29500")));
            assertThat(s.securityLevel()).isEqualTo(SecurityLevel.LOW);
            var rec = s.recommendations().stream().filter(r -> r.code().equals("CURE_MARGIN_CALL")).findFirst().orElseThrow();
            assertThat(rec.figures().get("topUp")).isEqualByComparingTo("18278.57"); // 71.428,57 - 53.150
            assertThat(rec.figures().get("repay")).isEqualByComparingTo("12795.00"); // 50.000 - 0,70 x 53.150
        }

        @Test
        void concentrationIsFlaggedWhenOneFundCarriesMostOfTheRisk() {
            var codes = base().recommendations().stream().map(MarginRiskAnalysisDto.Recommendation::code).toList();
            assertThat(codes).contains("CONCENTRATION");
        }

        @Test
        void suggestsAddingTheLowVolatilityFundThatImprovesTheBuffer() {
            // MSCI solo con deuda alta: los bonos (5,5 %) están libres y bajan la volatilidad y la distancia mejora.
            var s = service.analyze(PORTFOLIO, config("34000", "0.70", item(MSCI, "38000")));
            var rec = s.recommendations().stream().filter(r -> r.code().equals("ADD_LOW_VOLATILITY_FUND")).findFirst().orElseThrow();
            assertThat(rec.figures().get("pledgeAmount")).isEqualByComparingTo("29500.00");
            assertThat(rec.figures().get("distanceAfter")).isGreaterThan(rec.figures().get("distanceBefore"));
            assertThat(rec.figures().get("volatilityAfter")).isLessThan(rec.figures().get("volatilityBefore"));
        }

        @Test
        void everyAnalysisStatesItsAssumptions() {
            assertThat(base().assumptions()).hasSizeGreaterThanOrEqualTo(4);
            assertThat(base().assumptions().get(0)).contains("SUPUESTOS");
        }
    }

    @Nested
    @DisplayName("Validación")
    class Validation {

        private PledgeConfigDto with(String loan, String rate, Integer years, String threshold,
                                     List<PledgeConfigDto.CollateralItem> collateral) {
            return new PledgeConfigDto("PF-001", collateral, loan == null ? null : bd(loan),
                    rate == null ? null : bd(rate), years, threshold == null ? null : bd(threshold), null);
        }

        private final List<PledgeConfigDto.CollateralItem> ok = List.of(item(MSCI, "1000"));

        @Test
        void rejectsMissingOrInvalidScalars() {
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with(null, "0.04", 5, "0.70", ok)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("crédito");
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("-1", "0.04", 5, "0.70", ok)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("crédito");
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("100", "1.5", 5, "0.70", ok)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("interés");
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("100", "0.04", 0, "0.70", ok)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("plazo");
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("100", "0.04", 5, "1", ok)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("umbral");
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("100", "0.04", 5, "0", ok)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("umbral");
        }

        @Test
        void rejectsEmptyOrUnknownOrDuplicatedCollateral() {
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("100", "0.04", 5, "0.70", List.of())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("al menos un fondo");
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("100", "0.04", 5, "0.70", List.of(item("XX0000000000", "10")))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no está en la cartera");
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("100", "0.04", 5, "0.70", List.of(item(MSCI, "10"), item(MSCI, "20")))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repetido");
        }

        @Test
        void rejectsAmountsAboveThePositionOrNotPositive() {
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("100", "0.04", 5, "0.70", List.of(item(MSCI, "38000.02")))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supera");
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("100", "0.04", 5, "0.70", List.of(item(MSCI, "0")))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("> 0");
        }

        @Test
        void rejectsCashAsCollateralFund() {
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, with("100", "0.04", 5, "0.70", List.of(item("CASH:EUR", "10")))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("efectivo");
        }

        @Test
        void rejectsCollateralThatCountsForNothing() {
            var cfg = new PledgeConfigDto("PF-001", List.of(item(MSCI, "1000")), bd("100"), bd("0.04"), 5, bd("0.70"),
                    Map.of(AssetClass.FIXED_INCOME, bd("0.10"))); // renta variable ausente = no admitida
            assertThatThrownBy(() -> service.analyze(PORTFOLIO, cfg))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("garantía computable es 0");
        }

        @Test
        void usesPf001WhenThePortfolioIdIsMissing() {
            assertThat(MarginCallService.portfolioIdOf(new PledgeConfigDto(null, ok, bd("1"), bd("0.04"), 5, bd("0.7"), null)))
                    .isEqualTo("PF-001");
            assertThat(MarginCallService.portfolioIdOf(new PledgeConfigDto("  ", ok, bd("1"), bd("0.04"), 5, bd("0.7"), null)))
                    .isEqualTo("PF-001");
        }
    }
}
