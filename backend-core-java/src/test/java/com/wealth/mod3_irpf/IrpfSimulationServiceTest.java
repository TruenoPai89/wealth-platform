package com.wealth.mod3_irpf;

import com.wealth.mod3_irpf.IrpfSimulationService.Input;
import com.wealth.mod3_irpf.IrpfSimulationService.Result;
import com.wealth.mod3_irpf.IrpfSimulationService.SaleSimulation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Valores esperados calculados a mano con la escala 2025-2026 (19/21/23/27/30 %).
 * Referencia independiente en Python: calculos.py (mismos numeros).
 */
class IrpfSimulationServiceTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private final SavingsTaxScale scale = SavingsTaxScale.COMMON_2025_2026;
    private final IrpfSimulationService service = new IrpfSimulationService(scale);

    private static Input input(int year, String gains, String losses, LossCarryForwardBucket... carry) {
        return new Input(year, bd(gains), bd(losses), List.of(carry));
    }

    /** Compara importes con compareTo: BigDecimal.equals distingue 2000 de 2000.00. */
    private static void assertSingleBucket(List<LossCarryForwardBucket> buckets, int year, String amount) {
        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).originYear()).isEqualTo(year);
        assertThat(buckets.get(0).remaining()).isEqualByComparingTo(amount);
    }

    @Nested
    @DisplayName("Escala del ahorro")
    class Scale {
        @Test void firstBracket()  { assertThat(scale.tax(bd("6000"))).isEqualByComparingTo("1140.00"); }
        @Test void twoBrackets()   { assertThat(scale.tax(bd("10000"))).isEqualByComparingTo("1980.00"); }
        @Test void limit50k()      { assertThat(scale.tax(bd("50000"))).isEqualByComparingTo("10380.00"); }
        @Test void limit200k()     { assertThat(scale.tax(bd("200000"))).isEqualByComparingTo("44880.00"); }
        @Test void limit300k()     { assertThat(scale.tax(bd("300000"))).isEqualByComparingTo("71880.00"); }
        @Test void above300k()     { assertThat(scale.tax(bd("350000"))).isEqualByComparingTo("86880.00"); }
        @Test void negativeBase()  { assertThat(scale.tax(bd("-500"))).isEqualByComparingTo("0.00"); }
        @Test void zeroBase()      { assertThat(scale.tax(BigDecimal.ZERO)).isEqualByComparingTo("0.00"); }
    }

    @Nested
    @DisplayName("Compensacion en el ejercicio")
    class Netting {
        @Test void gainsMinusLosses() {
            Result r = service.simulate(input(2026, "10000", "4000"));
            assertThat(r.netBalance()).isEqualByComparingTo("6000.00");
            assertThat(r.tax()).isEqualByComparingTo("1140.00");
            assertThat(r.averageRate()).isEqualByComparingTo("0.1900");
        }

        @Test void lossesExceedGains() {
            Result r = service.simulate(input(2026, "3000", "5000"));
            assertThat(r.tax()).isEqualByComparingTo("0.00");
            assertThat(r.taxableBase()).isEqualByComparingTo("0.00");
            assertSingleBucket(r.remainingCarryForward(), 2026, "2000");
        }
    }

    @Nested
    @DisplayName("Saldos negativos de ejercicios anteriores")
    class CarryForward {
        @Test void usesPriorLosses() {
            Result r = service.simulate(input(2026, "10000", "0",
                    new LossCarryForwardBucket(2024, bd("4000"))));
            assertThat(r.carryForwardUsed()).isEqualByComparingTo("4000.00");
            assertThat(r.taxableBase()).isEqualByComparingTo("6000.00");
            assertThat(r.tax()).isEqualByComparingTo("1140.00");
            assertThat(r.remainingCarryForward()).isEmpty();
        }

        @Test void partialUseKeepsRemainder() {
            Result r = service.simulate(input(2026, "3000", "0",
                    new LossCarryForwardBucket(2024, bd("5000"))));
            assertThat(r.tax()).isEqualByComparingTo("0.00");
            assertSingleBucket(r.remainingCarryForward(), 2024, "2000");
        }

        @Test void expiredBucketIsIgnored() {
            // Origen 2021: usable de 2022 a 2025; en 2026 ha caducado.
            Result r = service.simulate(input(2026, "10000", "0",
                    new LossCarryForwardBucket(2021, bd("4000"))));
            assertThat(r.carryForwardUsed()).isEqualByComparingTo("0.00");
            assertThat(r.tax()).isEqualByComparingTo("1980.00");
            assertThat(r.remainingCarryForward()).isEmpty();
        }

        @Test void oldestBucketConsumedFirst() {
            Result r = service.simulate(input(2026, "5000", "0",
                    new LossCarryForwardBucket(2025, bd("3000")),
                    new LossCarryForwardBucket(2023, bd("3000"))));
            assertThat(r.carryForwardUsed()).isEqualByComparingTo("5000.00");
            assertSingleBucket(r.remainingCarryForward(), 2025, "1000");
        }
    }

    @Nested
    @DisplayName("Simulacion de una venta")
    class SaleSimulationCases {
        @Test void realizingLossSavesTax() {
            // Ganancias 10.000, perdidas 4.000 -> cuota 1.140. Venta con -2.000 -> base 4.000 -> 760.
            SaleSimulation s = service.simulateSale(input(2026, "10000", "4000"), bd("-2000"));
            assertThat(s.before().tax()).isEqualByComparingTo("1140.00");
            assertThat(s.after().tax()).isEqualByComparingTo("760.00");
            assertThat(s.taxDelta()).isEqualByComparingTo("-380.00");
        }

        @Test void lossFullyOffsetsGains() {
            // Perdida de 4.000 contra ganancias de 10.000 sin otras perdidas: 1.980 -> 1.140 = ahorro 840.
            SaleSimulation s = service.simulateSale(input(2026, "10000", "0"), bd("-4000"));
            assertThat(s.taxDelta()).isEqualByComparingTo("-840.00");
        }

        @Test void realizingGainCostsTax() {
            SaleSimulation s = service.simulateSale(input(2026, "0", "0"), bd("10000"));
            assertThat(s.taxDelta()).isEqualByComparingTo("1980.00");
        }
    }
}