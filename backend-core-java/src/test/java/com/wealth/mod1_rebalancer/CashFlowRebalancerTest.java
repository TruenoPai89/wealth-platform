package com.wealth.mod1_rebalancer;

import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import com.wealth.shared.domain.RebalanceOrder;
import com.wealth.shared.domain.RebalanceOrder.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CashFlowRebalancerTest {

    private static final String MSCI = "IE00B4L5Y983";
    private static final String SP500 = "IE00B3XXRP09";
    private static final String BOND = "IE00B4WXJJ64";
    private static final String GOLD = "IE00B579F325";
    private static final String CASH = "CASH:EUR";

    private final CashFlowRebalancer rebalancer = new CashFlowRebalancer();

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static AssetPosition pos(String id, AssetClass cls, String qty, String price) {
        return new AssetPosition(id, id, id, cls, bd(qty), bd(price), "EUR");
    }

    /** Misma cartera que client_portfolio_jev.json: total 130.000 EUR. */
    private static Portfolio portfolio() {
        return new Portfolio("PF-001", "CLI-001", "EUR", Instant.parse("2026-11-08T09:00:00Z"), List.of(
                pos(MSCI, AssetClass.EQUITY, "400", "95.00"),
                pos(SP500, AssetClass.EQUITY, "300", "105.00"),
                pos(BOND, AssetClass.FIXED_INCOME, "250", "118.00"),
                pos(GOLD, AssetClass.COMMODITY, "100", "215.00"),
                AssetPosition.cash("EUR", bd("9500.00"))));
    }

    private static Map<String, BigDecimal> targets() {
        return Map.of(MSCI, bd("0.35"), SP500, bd("0.20"), BOND, bd("0.30"), GOLD, bd("0.10"), CASH, bd("0.05"));
    }

    private static RebalanceOrder orderFor(RebalancePlan plan, String instrumentId) {
        return plan.orders().stream().filter(o -> o.instrumentId().equals(instrumentId)).findFirst().orElseThrow();
    }

    @Test
    void generatesFractionalOrdersFromTargetVsActual() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        assertThat(plan.totalValue()).isEqualByComparingTo("130000.00");
        assertThat(plan.orders()).hasSize(4);

        // MSCI: objetivo 45.500 vs actual 38.000 -> comprar 7.500 / 95 = 78,947368 uds
        RebalanceOrder msci = orderFor(plan, MSCI);
        assertThat(msci.side()).isEqualTo(Side.BUY);
        assertThat(msci.quantity()).isEqualByComparingTo("78.947368");
        assertThat(msci.amount()).isEqualByComparingTo("7500.00");

        // S&P 500: objetivo 26.000 vs actual 31.500 -> vender 5.500 / 105 = 52,380952 uds
        RebalanceOrder sp = orderFor(plan, SP500);
        assertThat(sp.side()).isEqualTo(Side.SELL);
        assertThat(sp.quantity()).isEqualByComparingTo("52.380952");
        assertThat(sp.amount()).isEqualByComparingTo("5500.00");

        // Renta fija: objetivo 39.000 vs actual 29.500 -> comprar 9.500 / 118 = 80,508475 uds
        RebalanceOrder bond = orderFor(plan, BOND);
        assertThat(bond.side()).isEqualTo(Side.BUY);
        assertThat(bond.quantity()).isEqualByComparingTo("80.508475");
        assertThat(bond.amount()).isEqualByComparingTo("9500.00");

        // Oro: objetivo 13.000 vs actual 21.500 -> vender 8.500 / 215 = 39,534884 uds
        RebalanceOrder gold = orderFor(plan, GOLD);
        assertThat(gold.side()).isEqualTo(Side.SELL);
        assertThat(gold.quantity()).isEqualByComparingTo("39.534884");
        assertThat(gold.amount()).isEqualByComparingTo("8500.00");

        // No hay orden sobre el efectivo: es la partida de cierre.
        assertThat(plan.orders()).noneMatch(o -> o.instrumentId().equals(CASH));
    }

    @Test
    void sellsAreListedBeforeBuys() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        assertThat(plan.orders()).extracting(RebalanceOrder::side)
                .containsExactly(Side.SELL, Side.SELL, Side.BUY, Side.BUY);
    }

    @Test
    void cashFlowSummaryAndCashReachItsTargetWeight() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());
        RebalancePlan.CashFlowSummary cf = plan.cashFlow();

        assertThat(cf.cashBefore()).isEqualByComparingTo("9500.00");
        assertThat(cf.buyTotal()).isEqualByComparingTo("17000.00");
        assertThat(cf.sellTotal()).isEqualByComparingTo("14000.00");
        assertThat(cf.netCashFlow()).isEqualByComparingTo("-3000.00");
        assertThat(cf.targetCash()).isEqualByComparingTo("6500.00");
        // 9.500 - 17.000 + 14.000 = 6.500 (5 % de 130.000); tolerancia de centimos por el redondeo a 6 decimales
        assertThat(cf.cashAfter()).isCloseTo(bd("6500.00"), within(bd("0.05")));
    }

    @Test
    void totalValueIsConservedAndWeightsLandOnTarget() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        BigDecimal postTotal = plan.positions().stream()
                .map(RebalancePlan.PositionRow::postTradeValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(postTotal).isCloseTo(bd("130000.00"), within(bd("0.05")));

        for (RebalancePlan.PositionRow row : plan.positions()) {
            assertThat(row.postTradeWeight())
                    .as("post-trade weight of %s", row.instrumentId())
                    .isCloseTo(row.targetWeight(), within(bd("0.000001")));
            assertThat(row.deltaWeight()).isEqualByComparingTo(row.targetWeight().subtract(row.currentWeight()));
        }
    }

    @Test
    void positionsTableCarriesCurrentWeightsAndOrderPerRow() {
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of());

        RebalancePlan.PositionRow msci = plan.positions().stream()
                .filter(r -> r.instrumentId().equals(MSCI)).findFirst().orElseThrow();
        assertThat(msci.currentWeight()).isEqualByComparingTo("0.292308"); // 38.000 / 130.000
        assertThat(msci.targetWeight()).isEqualByComparingTo("0.350000");
        assertThat(msci.order()).isNotNull();

        RebalancePlan.PositionRow cash = plan.positions().stream()
                .filter(r -> r.instrumentId().equals(CASH)).findFirst().orElseThrow();
        assertThat(cash.currentWeight()).isEqualByComparingTo("0.073077"); // 9.500 / 130.000
        assertThat(cash.order()).isNull();
    }

    @Test
    void balancedPortfolioProducesNoOrders() {
        Portfolio balanced = new Portfolio("PF-002", "CLI-002", "EUR", Instant.parse("2026-11-08T09:00:00Z"), List.of(
                pos(MSCI, AssetClass.EQUITY, "500", "100.00"),
                pos(BOND, AssetClass.FIXED_INCOME, "400", "100.00"),
                AssetPosition.cash("EUR", bd("10000.00"))));
        Map<String, BigDecimal> t = Map.of(MSCI, bd("0.50"), BOND, bd("0.40"), CASH, bd("0.10"));

        RebalancePlan plan = rebalancer.rebalance(balanced, t, Map.of());

        assertThat(plan.orders()).isEmpty();
        assertThat(plan.cashFlow().netCashFlow()).isEqualByComparingTo("0.00");
    }

    @Test
    void instrumentWithoutTargetIsFullySold() {
        Map<String, BigDecimal> noGold = Map.of(MSCI, bd("0.40"), SP500, bd("0.20"), BOND, bd("0.30"), CASH, bd("0.10"));

        RebalancePlan plan = rebalancer.rebalance(portfolio(), noGold, Map.of());

        RebalanceOrder gold = orderFor(plan, GOLD);
        assertThat(gold.side()).isEqualTo(Side.SELL);
        assertThat(gold.quantity()).isEqualByComparingTo("100.000000");
    }

    @Test
    void lockedQuantitiesCapTheSellOrder() {
        // 90 de las 100 participaciones de oro bloqueadas -> solo 10 vendibles (el skeleton base pasa mapa vacio)
        RebalancePlan plan = rebalancer.rebalance(portfolio(), targets(), Map.of(GOLD, bd("90")));

        RebalanceOrder gold = orderFor(plan, GOLD);
        assertThat(gold.side()).isEqualTo(Side.SELL);
        assertThat(gold.quantity()).isEqualByComparingTo("10.000000");
        assertThat(plan.constraints().lockedQuantities()).containsEntry(GOLD, bd("90"));
        // Se libera menos caja y las compras no se reducen: 9.500 - 17.000 + 5.500 + 2.150 = 150.
        // El efectivo queda por debajo de su objetivo (6.500) pero nunca negativo.
        assertThat(plan.cashFlow().cashAfter()).isCloseTo(bd("150.00"), within(bd("0.05")));
        assertThat(plan.cashFlow().cashAfter()).isLessThan(plan.cashFlow().targetCash());
    }

    @Test
    void rejectsTargetsThatDoNotSumToOne() {
        Map<String, BigDecimal> bad = Map.of(MSCI, bd("0.35"), SP500, bd("0.20"), BOND, bd("0.30"),
                GOLD, bd("0.10"), CASH, bd("0.10"));

        assertThatThrownBy(() -> rebalancer.rebalance(portfolio(), bad, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to 1");
    }

    @Test
    void rejectsTargetForUnknownInstrument() {
        Map<String, BigDecimal> bad = Map.of(MSCI, bd("0.35"), SP500, bd("0.20"), BOND, bd("0.30"),
                GOLD, bd("0.05"), CASH, bd("0.05"), "XX0000000000", bd("0.05"));

        assertThatThrownBy(() -> rebalancer.rebalance(portfolio(), bad, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XX0000000000");
    }

    @Test
    void rejectsNegativeTargetWeight() {
        Map<String, BigDecimal> bad = Map.of(MSCI, bd("1.10"), SP500, bd("-0.10"));

        assertThatThrownBy(() -> rebalancer.rebalance(portfolio(), bad, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
