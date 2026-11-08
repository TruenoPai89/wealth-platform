package com.wealth.mod1_rebalancer;

import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** projectedPositions: como queda la cartera tras ejecutar las ordenes. */
class RebalancerProjectionTest {

    private static final String MSCI = "IE00B4L5Y983";
    private static final String SP500 = "IE00B3XXRP09";
    private static final String BOND = "IE00B4WXJJ64";
    private static final String GOLD = "IE00B579F325";
    private static final String CASH = "CASH:EUR";
    private static final BigDecimal EPS = new BigDecimal("0.000001");

    private final CashFlowRebalancer rebalancer = new CashFlowRebalancer();

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static AssetPosition pos(String id, AssetClass cls, String qty, String price, String cost) {
        return new AssetPosition(id, id, id, cls, bd(qty), bd(price), "EUR", bd(cost));
    }

    private static Portfolio portfolio() {
        return new Portfolio("PF-001", "CLI-001", "EUR", Instant.parse("2026-11-08T09:00:00Z"), List.of(
                pos(MSCI, AssetClass.EQUITY, "400", "95.00", "34000.00"),
                pos(SP500, AssetClass.EQUITY, "300", "105.00", "27000.00"),
                pos(BOND, AssetClass.FIXED_INCOME, "250", "118.00", "29000.00"),
                pos(GOLD, AssetClass.COMMODITY, "100", "215.00", "18000.00"),
                AssetPosition.cash("EUR", bd("9500.00"))));
    }

    private static Map<String, BigDecimal> targets() {
        return Map.of(MSCI, bd("0.35"), SP500, bd("0.20"), BOND, bd("0.30"), GOLD, bd("0.10"), CASH, bd("0.05"));
    }

    private static RebalancePlan.ProjectedPosition proj(RebalancePlan plan, String id) {
        return plan.projectedPositions().stream().filter(r -> r.instrumentId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void hasOneProjectedPositionPerCurrentPosition() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        assertThat(plan.projectedPositions()).extracting(RebalancePlan.ProjectedPosition::instrumentId)
                .containsExactlyElementsOf(plan.positions().stream().map(RebalancePlan.PositionRow::instrumentId).toList());
    }

    @Test
    void buyAddsQuantityAndCost() {
        RebalancePlan.ProjectedPosition msci = proj(rebalancer.rebalance(portfolio(), targets(), Map.of()), MSCI);

        assertThat(msci.quantity()).isEqualByComparingTo("478.9474");
        assertThat(msci.marketValue()).isCloseTo(bd("45500.00"), within(bd("0.05")));
        assertThat(msci.costBasis()).isEqualByComparingTo("41500.00"); // 34.000 + 7.500 comprados
        assertThat(msci.unrealizedPnLAmount()).isCloseTo(bd("4000.00"), within(bd("0.05")));
    }

    @Test
    void sellRemovesQuantityAndProportionalCost() {
        RebalancePlan.ProjectedPosition gold = proj(rebalancer.rebalance(portfolio(), targets(), Map.of()), GOLD);

        assertThat(gold.quantity()).isEqualByComparingTo("60.4651");
        // Sale el 39,5349 % del coste de 18.000 (7.116,28): quedan 10.883,72
        assertThat(gold.costBasis()).isEqualByComparingTo("10883.72");
        assertThat(gold.unrealizedPnLPercent()).isEqualByComparingTo("19.44");
    }

    @Test
    void projectedWeightsLandOnTheTargets() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        for (RebalancePlan.ProjectedPosition p : plan.projectedPositions()) {
            assertThat(p.weight()).as("peso proyectado de %s", p.instrumentId())
                    .isCloseTo(p.targetWeight(), within(bd("0.0001")));
            assertThat(p.deltaWeight().abs()).isLessThan(bd("0.0001"));
        }
    }

    @Test
    void projectedCashIsTheTargetAtTwoDecimals() {
        RebalancePlan.ProjectedPosition cash = proj(rebalancer.rebalance(portfolio(), targets(), Map.of()), CASH);

        assertThat(cash.quantity()).isEqualByComparingTo("6500.00");
        assertThat(cash.quantity().scale()).isEqualTo(2);
        assertThat(cash.unrealizedPnLAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void totalValueIsConservedAndSellingRealizesGains() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        assertThat(plan.projectedPerformance().totalValue()).isCloseTo(bd("130000.00"), within(bd("0.05")));
        assertThat(plan.projectedPerformance().unrealizedPnLAmount())
                .isLessThan(plan.performance().unrealizedPnLAmount());
    }

    @Test
    void projectedWeightsSumToOne() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        BigDecimal sum = plan.projectedPositions().stream()
                .map(RebalancePlan.ProjectedPosition::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isCloseTo(BigDecimal.ONE, within(bd("0.0001")));
    }

    @Test
    void lockedQuantityLimitsTheProjectedSale() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of(GOLD, bd("90")));

        // Solo 10 uds vendibles: quedan 90 en la cartera proyectada y el peso no llega al objetivo
        RebalancePlan.ProjectedPosition gold = proj(plan, GOLD);
        assertThat(gold.quantity()).isEqualByComparingTo("90.0000");
        assertThat(gold.weight().subtract(gold.targetWeight())).isGreaterThan(EPS);
    }
}
