package com.wealth.mod1_rebalancer;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebalanceo por aportacion de caja (cash-inflow rebalancing): reparte una aportacion entre los fondos de una
 * agrupacion SOLO COMPRANDO (nunca vende) para acercar su composicion a los pesos objetivo de la agrupacion.
 * <p>
 * Es un llenado por niveles ("water-filling"). Con pesos objetivo w_i (suma 1) y valores actuales v_i, se busca el
 * valor total T de la agrupacion tras comprar tal que cada fondo que recibe dinero queda exactamente en w_i * T, y
 * los fondos que ya van por encima de su peso no reciben nada:
 * <pre>
 *   compra_i = max(0, w_i * T - v_i)      con T tal que  sum(compra_i) = aportacion
 * </pre>
 * Si todos los fondos van por debajo de su peso, T = valor actual + aportacion y la agrupacion queda exactamente en
 * sus pesos. Si alguno va por encima, no se vende: simplemente no recibe y T es mayor. Se resuelve ordenando los
 * fondos por el umbral v_i / w_i (el valor de T a partir del cual el fondo empieza a recibir) y activandolos en
 * orden hasta que el siguiente no entra. Sin IA, BigDecimal en todo.
 */
@Component
public class CashInflowAllocator {

    private static final int SCALE = 12;
    private static final RoundingMode RM = RoundingMode.HALF_EVEN;

    /** Un fondo de la agrupacion: peso objetivo (>= 0) y valor de mercado actual (>= 0). */
    public record Fund(String instrumentId, BigDecimal weight, BigDecimal currentValue) {
    }

    /**
     * @return instrumentId -> importe a comprar (>= 0), en el mismo orden que {@code funds}. La suma es la
     *         aportacion (salvo ~1e-9 de redondeo interno); los fondos sin compra figuran con 0.
     */
    public Map<String, BigDecimal> allocate(List<Fund> funds, BigDecimal amount) {
        if (funds == null || funds.isEmpty()) {
            throw new IllegalArgumentException("A basket needs at least one fund");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Contribution amount must be > 0");
        }
        BigDecimal weightSum = BigDecimal.ZERO;
        for (Fund f : funds) {
            if (f.weight() == null || f.weight().signum() < 0) {
                throw new IllegalArgumentException("Invalid weight for " + f.instrumentId());
            }
            if (f.currentValue() == null || f.currentValue().signum() < 0) {
                throw new IllegalArgumentException("Invalid current value for " + f.instrumentId());
            }
            weightSum = weightSum.add(f.weight());
        }
        if (weightSum.signum() <= 0) {
            throw new IllegalArgumentException("Basket weights must not all be zero");
        }

        // Pesos normalizados (suma 1) y solo los fondos con peso > 0, por umbral v/w ascendente.
        Map<String, BigDecimal> weight = new HashMap<>();
        List<Fund> candidates = new ArrayList<>();
        for (Fund f : funds) {
            BigDecimal w = f.weight().divide(weightSum, SCALE, RM);
            weight.put(f.instrumentId(), w);
            if (w.signum() > 0) {
                candidates.add(f);
            }
        }
        // v_a / w_a < v_b / w_b  <=>  v_a * w_b < v_b * w_a  (sin dividir)
        candidates.sort(Comparator.comparing(
                (Fund f) -> f.currentValue().divide(weight.get(f.instrumentId()), SCALE, RM)));

        BigDecimal sumValue = BigDecimal.ZERO;
        BigDecimal sumWeight = BigDecimal.ZERO;
        BigDecimal level = BigDecimal.ZERO;
        int active = candidates.size();
        for (int k = 0; k < candidates.size(); k++) {
            Fund f = candidates.get(k);
            sumValue = sumValue.add(f.currentValue());
            sumWeight = sumWeight.add(weight.get(f.instrumentId()));
            level = amount.add(sumValue).divide(sumWeight, SCALE, RM);
            if (k == candidates.size() - 1) {
                break;
            }
            Fund next = candidates.get(k + 1);
            // El siguiente solo entra si el nivel supera su umbral: level * w_next > v_next
            if (level.multiply(weight.get(next.instrumentId())).compareTo(next.currentValue()) <= 0) {
                active = k + 1;
                break;
            }
        }

        Map<String, BigDecimal> buys = new HashMap<>();
        for (int i = 0; i < active; i++) {
            Fund f = candidates.get(i);
            BigDecimal buy = weight.get(f.instrumentId()).multiply(level).subtract(f.currentValue());
            buys.put(f.instrumentId(), buy.max(BigDecimal.ZERO).setScale(10, RM));
        }
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Fund f : funds) {
            result.put(f.instrumentId(), buys.getOrDefault(f.instrumentId(), BigDecimal.ZERO.setScale(10)));
        }
        return result;
    }
}
