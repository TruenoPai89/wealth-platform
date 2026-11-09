package com.wealth.mod3_irpf;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Modulo 3: simulador de IRPF sobre ganancias patrimoniales.
 * <p>
 * Consume el resultado del modulo 2 (ganancias realizadas y perdidas computables, ya depuradas por
 * la regla de los 2 meses) y calcula la base del ahorro, la compensacion de perdidas pendientes,
 * la cuota y el efecto fiscal de una venta hipotetica. Solo aritmetica con {@link BigDecimal}.
 * <p>
 * Fuera de alcance: deducciones, otras rentas, diferencias autonomicas, regla de los 2 meses.
 */
@Service
public class IrpfSimulationService {

    private final SavingsTaxScale scale;

    public IrpfSimulationService() {
        this(SavingsTaxScale.COMMON_2025_2026);
    }

    public IrpfSimulationService(SavingsTaxScale scale) {
        this.scale = scale;
    }

    /**
     * @param fiscalYear       ejercicio que se simula.
     * @param realizedGains    suma de plusvalias realizadas (>= 0).
     * @param computableLosses suma de minusvalias computables (>= 0, como valor positivo).
     * @param carryForward     saldos negativos de ejercicios anteriores.
     */
    public record Input(int fiscalYear, BigDecimal realizedGains, BigDecimal computableLosses,
                        List<LossCarryForwardBucket> carryForward) {
        public Input {
            if (realizedGains == null || realizedGains.signum() < 0)
                throw new IllegalArgumentException("realizedGains must be >= 0");
            if (computableLosses == null || computableLosses.signum() < 0)
                throw new IllegalArgumentException("computableLosses must be >= 0");
            carryForward = carryForward == null ? List.of() : List.copyOf(carryForward);
        }
    }

    /**
     * @param netBalance            ganancias menos perdidas del ejercicio (puede ser negativo).
     * @param carryForwardUsed      saldo de ejercicios anteriores aplicado.
     * @param taxableBase           base del ahorro sometida a gravamen (>= 0).
     * @param tax                   cuota integra.
     * @param averageRate           cuota / base gravada (0 si la base es 0).
     * @param remainingCarryForward saldos pendientes tras el ejercicio (incluye el nuevo si hubo saldo negativo).
     */
    public record Result(BigDecimal netBalance, BigDecimal carryForwardUsed, BigDecimal taxableBase,
                         BigDecimal tax, BigDecimal averageRate,
                         List<LossCarryForwardBucket> remainingCarryForward) {}

    /**
     * Efecto de una venta hipotetica.
     *
     * @param taxDelta cuota con la venta menos cuota sin ella: negativo = ahorro, positivo = coste.
     */
    public record SaleSimulation(Result before, Result after, BigDecimal taxDelta) {}

    public Result simulate(Input in) {
        BigDecimal net = in.realizedGains().subtract(in.computableLosses());

        // Saldos usables, los mas antiguos primero.
        List<LossCarryForwardBucket> usable = new ArrayList<>();
        List<LossCarryForwardBucket> remaining = new ArrayList<>();
        for (LossCarryForwardBucket b : in.carryForward()) {
            if (b.isUsableIn(in.fiscalYear()) && b.remaining().signum() > 0) {
                usable.add(b);
            }
        }
        usable.sort(Comparator.comparingInt(LossCarryForwardBucket::originYear));

        BigDecimal used = BigDecimal.ZERO;
        if (net.signum() > 0) {
            BigDecimal toCover = net;
            for (LossCarryForwardBucket b : usable) {
                BigDecimal take = toCover.min(b.remaining());
                used = used.add(take);
                toCover = toCover.subtract(take);
                BigDecimal left = b.remaining().subtract(take);
                if (left.signum() > 0) {
                    remaining.add(b.withRemaining(left));
                }
            }
        } else {
            remaining.addAll(usable);
            if (net.signum() < 0) {
                remaining.add(new LossCarryForwardBucket(in.fiscalYear(), net.negate()));
            }
        }

        BigDecimal taxable = net.signum() > 0 ? net.subtract(used) : BigDecimal.ZERO;
        BigDecimal tax = scale.tax(taxable);
        BigDecimal avg = taxable.signum() > 0
                ? tax.divide(taxable, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new Result(
                net.setScale(2, RoundingMode.HALF_UP),
                used.setScale(2, RoundingMode.HALF_UP),
                taxable.setScale(2, RoundingMode.HALF_UP),
                tax,
                avg,
                List.copyOf(remaining));
    }

    /**
     * Simula vender con un resultado dado: positivo = plusvalia, negativo = minusvalia.
     * Ojo: realizar una perdida tambien preserva saldo de ejercicios anteriores para el futuro;
     * ese valor futuro no se refleja en {@code taxDelta}.
     */
    public SaleSimulation simulateSale(Input in, BigDecimal saleResult) {
        Result before = simulate(in);
        BigDecimal gains = in.realizedGains().add(saleResult.max(BigDecimal.ZERO));
        BigDecimal losses = in.computableLosses().add(saleResult.negate().max(BigDecimal.ZERO));
        Result after = simulate(new Input(in.fiscalYear(), gains, losses, in.carryForward()));
        return new SaleSimulation(before, after, after.tax().subtract(before.tax()));
    }
}