package com.wealth.mod4_pledge;

import com.wealth.mod4_pledge.LtvMonitorService.PledgeStatus;
import com.wealth.mod4_pledge.LtvMonitorService.Zone;
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

/**
 * Cartera = la del mock client_portfolio_jev.json (130.000 EUR de mercado).
 * Contrato = el mock de pignoración (haircuts 30 / 10 / 25 / 0 %, límite 70 %, margin call 80 %).
 * Valor de garantía: 69.500 x 0,70 + 29.500 x 0,90 + 21.500 x 0,75 + 9.500 = 100.825 EUR.
 */
class LtvMonitorServiceTest {

    private static BigDecimal bd(String s) { 
        return new BigDecimal(s); 
    }

    private static AssetPosition pos(String id, AssetClass c, String qty, String price) {
        return new AssetPosition(id, id, id, c, bd(qty), bd(price), "EUR", bd("0"));
    }

    private static final Portfolio PORTFOLIO = new Portfolio("PF-001", "CLI001", "EUR", Instant.EPOCH, List.of(
        pos("IE00B4L5Y983", AssetClass.EQUITY, "400", "95"),
        pos("IE00B3XXRP09", AssetClass.EQUITY, "300", "105"),
        pos("IE00B4WXJJ64", AssetClass.FIXED_INCOME, "250", "118"),
        pos("IE00B579F325", AssetClass.COMMODITY, "100", "215"),
        AssetPosition.cash("EUR", bd("9500"))
    ));

    private static PledgeContractEntity contract(String loan) {
        return new PledgeContractEntity(
            "PF-001", "PC-1", "LN-1", bd(loan), bd("0.70"), bd("0.80"), 5,
            Map.of(
                AssetClass.EQUITY, bd("0.30"),
                AssetClass.FIXED_INCOME, bd("0.10"),
                AssetClass.COMMODITY, bd("0.25"), 
                AssetClass.CASH, bd("0")
            )
        );
    }

    private final LtvMonitorService service = new LtvMonitorService();

    @Nested
    @DisplayName("Situación actual (sin caída)")
    class Base {
        private final PledgeStatus s = service.status(PORTFOLIO, contract("65000"), BigDecimal.ZERO, 30);

        @Test 
        void marketAndCollateral() {
            assertThat(s.marketValue()).isEqualByComparingTo("130000.00");
            assertThat(s.collateralValue()).isEqualByComparingTo("100825.00");
        }

        @Test 
        void ltvAndZone() {
            assertThat(s.ltv()).isEqualByComparingTo("0.6447"); // 65.000 / 100.825
            assertThat(s.zone()).isEqualTo(Zone.OK);
        }

        @Test 
        void distanceToMarginCall() {
            // V_c = 65.000 / 0,80 = 81.250 -> colchón 19.575
            assertThat(s.collateralDropToMarginCall()).isEqualByComparingTo("0.1941"); // 19.575 / 100.825
            assertThat(s.equityDropToMarginCall()).isEqualByComparingTo("0.4024"); // 19.575 / 48.650
        }

        @Test 
        void noActionsNeeded() {
            assertThat(s.actions().repayCash()).isEqualByComparingTo("0.00");
            assertThat(s.actions().addCollateral()).isEqualByComparingTo("0.00");
            assertThat(s.actions().sellByClass().get(AssetClass.EQUITY).marketValueToSell())
                    .isEqualByComparingTo("0.00");
        }

        @Test 
        void tinyProbabilityFarFromBarrier() {
            assertThat(s.marginCallProbability()).isEqualByComparingTo("0.0000");
        }
    }

    @Nested
    @DisplayName("Caída de la renta variable del 25 %")
    class Shock25 {
        private final PledgeStatus s = service.status(PORTFOLIO, contract("65000"), bd("0.25"), 30);

        @Test 
        void collateralAndZone() {
            // Renta variable: 69.500 x 0,75 x 0,70 = 36.487,50 -> garantía 88.662,50
            assertThat(s.collateralValue()).isEqualByComparingTo("88662.50");
            assertThat(s.ltv()).isEqualByComparingTo("0.7331");
            assertThat(s.zone()).isEqualTo(Zone.LIMIT_EXCEEDED);
        }

        @Test 
        void actionsBackToLimit() {
            // Amortizar: 65.000 - 0,70 x 88.662,50 = 2.936,25
            assertThat(s.actions().repayCash()).isEqualByComparingTo("2936.25");
            // Aportar: 65.000 / 0,70 - 88.662,50 = 4.194,64
            assertThat(s.actions().addCollateral()).isEqualByComparingTo("4194.64");
            // Vender renta variable: 2.936,25 / (1 - 0,70 x 0,70) = 5.757,35
            LtvMonitorService.SaleAction eq = s.actions().sellByClass().get(AssetClass.EQUITY);
            assertThat(eq.marketValueToSell()).isEqualByComparingTo("5757.35");
            assertThat(eq.feasible()).isTrue();
        }

        @Test 
        void saleLeavesLtvAtLimit() {
            BigDecimal x = s.actions().sellByClass().get(AssetClass.COMMODITY).marketValueToSell();
            BigDecimal newLtv = bd("65000").subtract(x)
                    .divide(bd("88662.50").subtract(bd("0.75").multiply(x)), java.math.MathContext.DECIMAL64);
            assertThat(newLtv.doubleValue()).isCloseTo(0.70, org.assertj.core.data.Offset.offset(1e-4));
        }
    }

    @Nested
    @DisplayName("Caída de la renta variable del 45 %")
    class Shock45 {
        @Test 
        void marginCall() {
            PledgeStatus s = service.status(PORTFOLIO, contract("65000"), bd("0.45"), 30);
            assertThat(s.zone()).isEqualTo(Zone.MARGIN_CALL);
            assertThat(s.collateralDropToMarginCall()).isEqualByComparingTo("0.0000");
            assertThat(s.equityDropToMarginCall()).isEqualByComparingTo("0.0000");
            assertThat(s.marginCallProbability()).isEqualByComparingTo("1.0000");
        }
    }

    @Nested
    @DisplayName("Probabilidad de tocar la barrera (fórmula cerrada)")
    class Probability {
        // Valores de referencia calculados en Python (teoria_hackfinance.pdf, tabla de la sección 2.2).
        @Test 
        void sigma25() {
            assertThat(LtvMonitorService.barrierHitProbability(0.875, 0.25, 30.0 / 252))
                    .isCloseTo(0.1299, org.assertj.core.data.Offset.offset(1e-3));
        }

        @Test 
        void sigma15() {
            assertThat(LtvMonitorService.barrierHitProbability(0.875, 0.15, 30.0 / 252))
                    .isCloseTo(0.0106, org.assertj.core.data.Offset.offset(1e-3));
        }

        @Test 
        void alreadyBelowBarrier() {
            assertThat(LtvMonitorService.barrierHitProbability(1.2, 0.2, 0.1)).isEqualTo(1.0);
        }

        @Test 
        void normalCdf() {
            assertThat(LtvMonitorService.normalCdf(0)).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-7));
            assertThat(LtvMonitorService.normalCdf(1.96)).isCloseTo(0.9750, org.assertj.core.data.Offset.offset(1e-4));
        }
    }

    @Nested
    @DisplayName("Validaciones del contrato")
    class Validation {
        @Test 
        void missingClassIsNotAdmitted() {
            PledgeContractEntity c = new PledgeContractEntity(
                "PF-001", "PC", "LN", bd("1000"), bd("0.7"), bd("0.8"),
                null, Map.of(AssetClass.CASH, bd("0"))
            );
            assertThat(c.haircutFor(AssetClass.EQUITY)).isEqualByComparingTo("1");

            PledgeStatus s = service.status(PORTFOLIO, c, BigDecimal.ZERO, 30);
            assertThat(s.collateralValue()).isEqualByComparingTo("9500.00"); // solo cuenta el efectivo
        }

        @Test 
        void marginCallMustExceedLimit() {
            assertThatThrownBy(() -> new PledgeContractEntity(
                "PF-001", "PC", "LN", bd("1"), bd("0.8"), bd("0.7"),
                null, Map.of()
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test 
        void shockOutOfRange() {
            assertThatThrownBy(() -> service.status(PORTFOLIO, contract("65000"), bd("1.2"), 30))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}