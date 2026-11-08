package com.wealth.mod1_rebalancer;

import com.wealth.mod1_rebalancer.CashInflowAllocator.Fund;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Reparto de una aportacion: solo compras, llenando primero lo que mas se aleja de su peso. */
class CashInflowAllocatorTest {

    private final CashInflowAllocator allocator = new CashInflowAllocator();

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static Fund fund(String id, String weight, String value) {
        return new Fund(id, bd(weight), bd(value));
    }

    private static BigDecimal total(Map<String, BigDecimal> buys) {
        return buys.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void aBalancedBasketSplitsTheContributionByWeight() {
        Map<String, BigDecimal> buys = allocator.allocate(List.of(
                fund("A", "1", "1000"), fund("B", "1", "1000"), fund("C", "1", "1000")), bd("3000"));

        assertThat(buys.get("A")).isCloseTo(bd("1000"), within(bd("0.000001")));
        assertThat(buys.get("B")).isCloseTo(bd("1000"), within(bd("0.000001")));
        assertThat(buys.get("C")).isCloseTo(bd("1000"), within(bd("0.000001")));
    }

    @Test
    void customWeightsAreRespected() {
        // 40/30/20/10 sobre una cesta vacia de valor: la aportacion se reparte exactamente asi
        Map<String, BigDecimal> buys = allocator.allocate(List.of(
                fund("A", "40", "0"), fund("B", "30", "0"), fund("C", "20", "0"), fund("D", "10", "0")), bd("5000"));

        assertThat(buys.get("A")).isCloseTo(bd("2000"), within(bd("0.000001")));
        assertThat(buys.get("B")).isCloseTo(bd("1500"), within(bd("0.000001")));
        assertThat(buys.get("C")).isCloseTo(bd("1000"), within(bd("0.000001")));
        assertThat(buys.get("D")).isCloseTo(bd("500"), within(bd("0.000001")));
    }

    @Test
    void theContributionIsFullyAllocated() {
        Map<String, BigDecimal> buys = allocator.allocate(List.of(
                fund("A", "40", "38000"), fund("B", "30", "31500"), fund("C", "30", "29500")), bd("5000"));

        assertThat(total(buys)).isCloseTo(bd("5000"), within(bd("0.000001")));
    }

    @Test
    void neverSellsAndFundsAboveTheirWeightReceiveNothing() {
        // A va muy por encima de su peso (50 %): con 1.000 EUR de aportacion no recibe nada
        Map<String, BigDecimal> buys = allocator.allocate(List.of(
                fund("A", "1", "9000"), fund("B", "1", "500"), fund("C", "1", "500")), bd("1000"));

        assertThat(buys.get("A")).isEqualByComparingTo("0");
        assertThat(buys.values()).allSatisfy(b -> assertThat(b.signum()).isGreaterThanOrEqualTo(0));
        assertThat(buys.get("B")).isCloseTo(bd("500"), within(bd("0.000001")));
        assertThat(buys.get("C")).isCloseTo(bd("500"), within(bd("0.000001")));
    }

    @Test
    void underweightFundsAreFilledFirstAndEndUpLevelled() {
        // Pesos iguales; valores 100 / 300 / 500 y 600 de aportacion. El valor total pasa a 900 + 600 = 1.500,
        // asi que cada fondo deberia quedar en 500: A compra 400, B compra 200 y C (ya en 500) no compra.
        Map<String, BigDecimal> buys = allocator.allocate(List.of(
                fund("A", "1", "100"), fund("B", "1", "300"), fund("C", "1", "500")), bd("600"));

        assertThat(buys.get("A")).isCloseTo(bd("400"), within(bd("0.000001")));
        assertThat(buys.get("B")).isCloseTo(bd("200"), within(bd("0.000001")));
        assertThat(buys.get("C")).isCloseTo(bd("0"), within(bd("0.000001")));
    }

    @Test
    void allFundsEndOnTheirTargetWeightWhenTheContributionIsLargeEnough() {
        Map<String, BigDecimal> buys = allocator.allocate(List.of(
                fund("A", "50", "100"), fund("B", "30", "200"), fund("C", "20", "300")), bd("10000"));

        BigDecimal newTotal = bd("600").add(bd("10000"));
        assertThat(bd("100").add(buys.get("A")).divide(newTotal, 6, java.math.RoundingMode.HALF_EVEN))
                .isCloseTo(bd("0.5"), within(bd("0.000001")));
        assertThat(bd("200").add(buys.get("B")).divide(newTotal, 6, java.math.RoundingMode.HALF_EVEN))
                .isCloseTo(bd("0.3"), within(bd("0.000001")));
        assertThat(bd("300").add(buys.get("C")).divide(newTotal, 6, java.math.RoundingMode.HALF_EVEN))
                .isCloseTo(bd("0.2"), within(bd("0.000001")));
    }

    @Test
    void aZeroWeightFundNeverReceivesMoney() {
        Map<String, BigDecimal> buys = allocator.allocate(List.of(
                fund("A", "1", "0"), fund("B", "0", "0")), bd("100"));

        assertThat(buys.get("B")).isEqualByComparingTo("0");
        assertThat(buys.get("A")).isCloseTo(bd("100"), within(bd("0.000001")));
    }

    @Test
    void relativeWeightsAreNormalised() {
        // 1:1 y 50:50 dan lo mismo
        Map<String, BigDecimal> a = allocator.allocate(List.of(fund("A", "1", "0"), fund("B", "1", "0")), bd("100"));
        Map<String, BigDecimal> b = allocator.allocate(List.of(fund("A", "50", "0"), fund("B", "50", "0")), bd("100"));

        assertThat(a.get("A")).isEqualByComparingTo(b.get("A"));
    }

    @Test
    void preservesTheOrderOfTheFunds() {
        Map<String, BigDecimal> buys = allocator.allocate(List.of(
                fund("Z", "1", "0"), fund("A", "1", "0"), fund("M", "1", "0")), bd("30"));

        assertThat(buys.keySet()).containsExactly("Z", "A", "M");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> allocator.allocate(List.of(), bd("100")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(List.of(fund("A", "1", "0")), bd("0")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(List.of(fund("A", "1", "0")), bd("-5")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(List.of(fund("A", "0", "0")), bd("100")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(List.of(fund("A", "-1", "0")), bd("100")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> allocator.allocate(List.of(fund("A", "1", "-1")), bd("100")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
