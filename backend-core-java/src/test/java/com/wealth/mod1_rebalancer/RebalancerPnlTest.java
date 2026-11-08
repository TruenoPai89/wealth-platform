package com.wealth.mod1_rebalancer;

import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import com.wealth.shared.domain.RebalanceOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Redondeo a 4 decimales y P&L (coste base) del rebalanceador. */
class RebalancerPnlTest {

    private static final String MSCI = "IE00B4L5Y983";
    private static final String SP500 = "IE00B3XXRP09";
    private static final String BOND = "IE00B4WXJJ64";
    private static final String GOLD = "IE00B579F325";
    private static final String CASH = "CASH:EUR";
    private static final Instant AS_OF = Instant.parse("2026-11-08T09:00:00Z");

    private final CashFlowRebalancer rebalancer = new CashFlowRebalancer();

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static AssetPosition pos(String id, AssetClass cls, String qty, String price, String cost) {
        return new AssetPosition(id, id, id, cls, bd(qty), bd(price), "EUR", bd(cost));
    }

    /** Misma cartera que client_portfolio_jev.json: valor 130.000, coste 117.500 (108.000 + 9.500 de caja). */
    private static Portfolio portfolio() {
        return new Portfolio("PF-001", "CLI-001", "EUR", AS_OF, List.of(
                pos(MSCI, AssetClass.EQUITY, "400", "95.00", "34000.00"),
                pos(SP500, AssetClass.EQUITY, "300", "105.00", "27000.00"),
                pos(BOND, AssetClass.FIXED_INCOME, "250", "118.00", "29000.00"),
                pos(GOLD, AssetClass.COMMODITY, "100", "215.00", "18000.00"),
                AssetPosition.cash("EUR", bd("9500.00"))));
    }

    private static Map<String, BigDecimal> targets() {
        return Map.of(MSCI, bd("0.35"), SP500, bd("0.20"), BOND, bd("0.30"), GOLD, bd("0.10"), CASH, bd("0.05"));
    }

    private static RebalancePlan.PositionRow row(RebalancePlan plan, String id) {
        return plan.positions().stream().filter(r -> r.instrumentId().equals(id)).findFirst().orElseThrow();
    }

    private static RebalanceOrder order(RebalancePlan plan, String id) {
        return plan.orders().stream().filter(o -> o.instrumentId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void orderQuantitiesHaveAtMostFourDecimals() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        assertThat(plan.orders()).allSatisfy(o -> assertThat(o.quantity().scale()).isLessThanOrEqualTo(4));
        assertThat(order(plan, SP500).quantity()).isEqualByComparingTo("52.3810");
        assertThat(order(plan, MSCI).quantity()).isEqualByComparingTo("78.9474");
    }

    @Test
    void postTradeQuantityHasFourDecimalsAndCashTwo() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        assertThat(row(plan, MSCI).postTradeQuantity()).isEqualByComparingTo("478.9474");
        assertThat(row(plan, MSCI).postTradeQuantity().scale()).isEqualTo(4);
        assertThat(row(plan, CASH).postTradeQuantity().scale()).isEqualTo(2);
    }

    @Test
    void roundsHalfUpAtTheFourthDecimal() {
        // Delta exacto de 0,00005 uds (0,005 EUR a 100 EUR/ud): HALF_UP sube a 0,0001 (HALF_EVEN daria 0,0000)
        Portfolio p = new Portfolio("PF-Y", "CLI-Y", "EUR", AS_OF, List.of(
                pos(MSCI, AssetClass.EQUITY, "10", "100.00", "1000.00"),
                AssetPosition.cash("EUR", bd("1000.00"))));
        Map<String, BigDecimal> t = Map.of(MSCI, bd("0.5000025"), CASH, bd("0.4999975"));

        RebalancePlan plan = rebalancer.rebalance(p, t, Map.of());

        assertThat(order(plan, MSCI).quantity()).isEqualByComparingTo("0.0001");
    }

    @Test
    void orderAmountIsTheRoundedQuantityTimesPrice() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        for (RebalanceOrder o : plan.orders()) {
            assertThat(o.amount()).isEqualByComparingTo(o.quantity().multiply(o.price()).setScale(2, java.math.RoundingMode.HALF_EVEN));
        }
        // 52,3810 * 105 = 5.500,005 -> 5.500,00
        assertThat(order(plan, SP500).amount()).isEqualByComparingTo("5500.00");
    }

    @Test
    void computesUnrealizedPnLPerPosition() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        assertThat(row(plan, MSCI).costBasis()).isEqualByComparingTo("34000.00");
        assertThat(row(plan, MSCI).unrealizedPnLAmount()).isEqualByComparingTo("4000.00");
        assertThat(row(plan, MSCI).unrealizedPnLPercent()).isEqualByComparingTo("11.76");
        assertThat(row(plan, SP500).unrealizedPnLAmount()).isEqualByComparingTo("4500.00");
        assertThat(row(plan, SP500).unrealizedPnLPercent()).isEqualByComparingTo("16.67");
        assertThat(row(plan, BOND).unrealizedPnLAmount()).isEqualByComparingTo("500.00");
        assertThat(row(plan, GOLD).unrealizedPnLAmount()).isEqualByComparingTo("3500.00");
        assertThat(row(plan, GOLD).unrealizedPnLPercent()).isEqualByComparingTo("19.44");
    }

    @Test
    void cashHasNoPnL() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        assertThat(row(plan, CASH).unrealizedPnLAmount()).isEqualByComparingTo("0.00");
        assertThat(row(plan, CASH).unrealizedPnLPercent()).isEqualByComparingTo("0.00");
    }

    @Test
    void computesPortfolioPerformanceForTheKpiCards() {
        RebalancePlan.Performance perf = rebalancer.rebalance(portfolio(), targets(), Map.of()).performance();

        assertThat(perf.totalValue()).isEqualByComparingTo("130000.00");
        assertThat(perf.totalCost()).isEqualByComparingTo("117500.00");
        assertThat(perf.unrealizedPnLAmount()).isEqualByComparingTo("12500.00");
        assertThat(perf.unrealizedPnLPercent()).isEqualByComparingTo("10.64");
    }

    @Test
    void portfolioPnLEqualsSumOfPositionPnL() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        BigDecimal sum = plan.positions().stream()
                .map(RebalancePlan.PositionRow::unrealizedPnLAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(plan.performance().unrealizedPnLAmount()).isEqualByComparingTo(sum);
    }

    @Test
    void pnlPercentIsZeroWhenThereIsNoCostBasis() {
        Portfolio p = new Portfolio("PF-Z", "CLI-Z", "EUR", AS_OF, List.of(
                pos(MSCI, AssetClass.EQUITY, "10", "100.00", "0"),
                AssetPosition.cash("EUR", bd("0.00"))));

        RebalancePlan plan = rebalancer.rebalance(p, Map.of(MSCI, bd("1.0"), CASH, bd("0")), Map.of());

        assertThat(row(plan, MSCI).unrealizedPnLPercent()).isEqualByComparingTo("0.00");
        assertThat(plan.performance().unrealizedPnLAmount()).isCloseTo(bd("1000.00"), within(bd("0.001")));
    }
}
