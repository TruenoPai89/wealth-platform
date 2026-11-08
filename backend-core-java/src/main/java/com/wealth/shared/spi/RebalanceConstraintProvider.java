package com.wealth.shared.spi;

import com.wealth.shared.domain.Portfolio;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Punto de extension para que otros modulos (p.ej. mod4 pignoracion) limiten el rebalanceo.
 * Devuelve, por instrumentId, la cantidad NO vendible (bloqueada). El modulo 1 base no tiene restricciones.
 */
public interface RebalanceConstraintProvider {

    Map<String, BigDecimal> lockedQuantities(Portfolio portfolio);
}
