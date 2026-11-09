package com.wealth.mod3_irpf;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Escala de gravamen de la base liquidable del ahorro (IRPF, territorio comun).
 * <p>
 * Es la UNICA fuente de tramos del proyecto: {@code FifoCalculatorService} (modulo 2) deberia
 * delegar aqui en lugar de tener sus propias constantes.
 * <p>
 * Tramos 2025-2026: 19 % hasta 6.000, 21 % hasta 50.000, 23 % hasta 200.000,
 * 27 % hasta 300.000 y 30 % a partir de 300.000 (el ultimo tramo subio del 28 % al 30 % en 2025).
 * Verificado el 8-nov-2026 contra fuentes secundarias coincidentes; confirmar con el Manual
 * practico de Renta de la AEAT del ejercicio.
 */
public final class SavingsTaxScale {

    /** @param upTo limite superior del tramo; {@code null} = sin limite. */
    public record Bracket(BigDecimal upTo, BigDecimal rate) {}

    public static final SavingsTaxScale COMMON_2025_2026 = new SavingsTaxScale(List.of(
            new Bracket(new BigDecimal("6000"), new BigDecimal("0.19")),
            new Bracket(new BigDecimal("50000"), new BigDecimal("0.21")),
            new Bracket(new BigDecimal("200000"), new BigDecimal("0.23")),
            new Bracket(new BigDecimal("300000"), new BigDecimal("0.27")),
            new Bracket(null, new BigDecimal("0.30"))));

    private final List<Bracket> brackets;

    public SavingsTaxScale(List<Bracket> brackets) {
        this.brackets = List.copyOf(brackets);
    }

    /** Cuota integra para una base del ahorro. Cero si la base es cero o negativa. */
    public BigDecimal tax(BigDecimal base) {
        if (base == null || base.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal lower = BigDecimal.ZERO;
        for (Bracket b : brackets) {
            if (base.compareTo(lower) <= 0) break;
            BigDecimal top = b.upTo() == null ? base : base.min(b.upTo());
            tax = tax.add(top.subtract(lower).multiply(b.rate()));
            if (b.upTo() == null) break;
            lower = b.upTo();
        }
        return tax.setScale(2, RoundingMode.HALF_UP);
    }
}