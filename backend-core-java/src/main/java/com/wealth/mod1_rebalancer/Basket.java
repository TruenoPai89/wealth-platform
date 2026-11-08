package com.wealth.mod1_rebalancer;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Agrupacion de fondos (cesta / model portfolio): un conjunto de fondos con un peso objetivo cada uno (suma 1).
 * Los pesos estan normalizados y conservan el orden de definicion.
 */
public record Basket(String id, String name, String description, Map<String, BigDecimal> weights) {
}
