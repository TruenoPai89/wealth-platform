package com.wealth.mod1_rebalancer;

import com.wealth.mod1_rebalancer.RebalancePlan.CashFlowSummary;
import com.wealth.mod1_rebalancer.RebalancePlan.Constraints;
import com.wealth.mod1_rebalancer.RebalancePlan.PositionRow;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import com.wealth.shared.domain.RebalanceOrder;
import com.wealth.shared.domain.RebalanceOrder.Side;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Matematica estricta del rebalanceo (sin IA, sin solver). BigDecimal en todo, cantidades fraccionarias.
 * <p>
 * Para cada instrumento: valorObjetivo = pesoObjetivo * valorTotal; delta = objetivo - actual;
 * cantidad = delta / precio (6 decimales). El efectivo es un peso objetivo mas y actua como partida
 * de cierre: su saldo final es el saldo inicial - compras + ventas, de modo que el valor total se conserva exactamente.
 */
@Component
public class CashFlowRebalancer {

    static final int QTY_SCALE = 6;
    static final int WEIGHT_SCALE = 6;
    static final int MONEY_SCALE = 2;
    /** El efectivo se cierra siempre a 2 decimales con HALF_UP (precision contable de caja). */
    static final int CASH_SCALE = 2;
    private static final RoundingMode CASH_RM = RoundingMode.HALF_UP;
    private static final RoundingMode RM = RoundingMode.HALF_EVEN;
    private static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.000001");

    public RebalancePlan rebalance(Portfolio portfolio, Map<String, BigDecimal> targetWeights,
                                   Map<String, BigDecimal> lockedQuantities) {
        validateTargets(portfolio, targetWeights);
        BigDecimal total = portfolio.totalValue();
        if (total.signum() <= 0) {
            throw new IllegalArgumentException("Portfolio total value must be > 0: " + portfolio.portfolioId());
        }

        List<Calc> calcs = new ArrayList<>();
        BigDecimal buyTotal = BigDecimal.ZERO;
        BigDecimal sellTotal = BigDecimal.ZERO;
        AssetPosition cashPosition = null;

        for (AssetPosition p : portfolio.positions()) {
            BigDecimal weight = targetWeights.getOrDefault(p.instrumentId(), BigDecimal.ZERO);
            BigDecimal targetValue = weight.multiply(total);
            BigDecimal deltaValue = targetValue.subtract(p.marketValue());
            RebalanceOrder order = null;

            if (p.isCash()) {
                cashPosition = p;
            } else {
                BigDecimal deltaQty = deltaValue.divide(p.price(), QTY_SCALE, RM);
                if (deltaQty.signum() < 0) {
                    BigDecimal locked = lockedQuantities.getOrDefault(p.instrumentId(), BigDecimal.ZERO);
                    BigDecimal sellable = p.quantity().subtract(locked).max(BigDecimal.ZERO);
                    BigDecimal sellQty = deltaQty.negate().min(sellable);
                    if (sellQty.signum() > 0) {
                        order = order(p, Side.SELL, sellQty);
                        sellTotal = sellTotal.add(sellQty.multiply(p.price()));
                    }
                } else if (deltaQty.signum() > 0) {
                    order = order(p, Side.BUY, deltaQty);
                    buyTotal = buyTotal.add(deltaQty.multiply(p.price()));
                }
            }
            calcs.add(new Calc(p, weight, targetValue, deltaValue, order));
        }

        BigDecimal cashBefore = cashPosition.quantity();
        BigDecimal cashAfter = cashBefore.subtract(buyTotal).add(sellTotal);
        if (cashAfter.signum() < 0) {
            // No deberia ocurrir (pesos suman 1 y las ventas financian las compras); protege contra bloqueos.
            throw new IllegalStateException("Rebalance would need negative cash: " + cashAfter);
        }
        // Unica fuente del saldo final de caja: 2 decimales, HALF_UP (evita restos tipo 6500.000010).
        BigDecimal cashAfterRounded = cashAfter.setScale(CASH_SCALE, CASH_RM);

        List<PositionRow> rows = new ArrayList<>();
        List<RebalanceOrder> orders = new ArrayList<>();
        for (Calc c : calcs) {
            AssetPosition p = c.position();
            BigDecimal postQty;
            if (p.isCash()) {
                postQty = cashAfterRounded;
            } else if (c.order() == null) {
                postQty = p.quantity();
            } else if (c.order().side() == Side.BUY) {
                postQty = p.quantity().add(c.order().quantity());
            } else {
                postQty = p.quantity().subtract(c.order().quantity());
            }
            BigDecimal postValue = postQty.multiply(p.price());
            BigDecimal currentWeight = p.marketValue().divide(total, WEIGHT_SCALE, RM);

            rows.add(new PositionRow(
                    p.instrumentId(), p.isin(), p.name(), p.assetClass(),
                    qty(p.quantity()), p.price().setScale(MONEY_SCALE, RM), money(p.marketValue()),
                    currentWeight, weight(c.targetWeight()), weight(c.targetWeight()).subtract(currentWeight),
                    money(c.targetValue()), money(c.deltaValue()),
                    c.order() == null ? null : roundOrder(c.order()),
                    p.isCash() ? cashAfterRounded : qty(postQty),
                    money(postValue), postValue.divide(total, WEIGHT_SCALE, RM)));
            if (c.order() != null) {
                orders.add(roundOrder(c.order()));
            }
        }
        // Ventas primero (generan la caja que financia las compras), luego compras; estable por instrumento.
        orders.sort(Comparator.comparing((RebalanceOrder o) -> o.side() == Side.SELL ? 0 : 1)
                .thenComparing(RebalanceOrder::instrumentId));

        BigDecimal targetCash = targetWeights.getOrDefault(cashPosition.instrumentId(), BigDecimal.ZERO)
                .multiply(total);
        CashFlowSummary summary = new CashFlowSummary(money(cashBefore), money(buyTotal), money(sellTotal),
                money(sellTotal.subtract(buyTotal)), cashAfterRounded, money(targetCash));

        return new RebalancePlan(RebalancePlan.SCHEMA_VERSION, RebalancePlan.MODULE, portfolio.portfolioId(),
                portfolio.clientId(), portfolio.baseCurrency(), portfolio.asOf(), money(total),
                new Constraints(false, new TreeMap<>(lockedQuantities)), rows, orders, summary);
    }

    private static void validateTargets(Portfolio portfolio, Map<String, BigDecimal> targets) {
        Set<String> known = new HashSet<>();
        portfolio.positions().forEach(p -> known.add(p.instrumentId()));
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : targets.entrySet()) {
            if (e.getValue() == null || e.getValue().signum() < 0) {
                throw new IllegalArgumentException("Invalid target weight for " + e.getKey() + ": " + e.getValue());
            }
            if (!known.contains(e.getKey())) {
                throw new IllegalArgumentException("Target for instrument not in portfolio (no price/quantity): " + e.getKey());
            }
            sum = sum.add(e.getValue());
        }
        if (sum.subtract(BigDecimal.ONE).abs().compareTo(WEIGHT_TOLERANCE) > 0) {
            throw new IllegalArgumentException("Target weights must sum to 1 but sum to " + sum);
        }
    }

    private static RebalanceOrder order(AssetPosition p, Side side, BigDecimal quantity) {
        return new RebalanceOrder(p.instrumentId(), p.name(), side, quantity, p.price(),
                quantity.multiply(p.price()), p.currency());
    }

    private static RebalanceOrder roundOrder(RebalanceOrder o) {
        return new RebalanceOrder(o.instrumentId(), o.name(), o.side(), qty(o.quantity()),
                o.price().setScale(MONEY_SCALE, RM), money(o.amount()), o.currency());
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RM);
    }

    private static BigDecimal qty(BigDecimal v) {
        return v.setScale(QTY_SCALE, RM);
    }

    private static BigDecimal weight(BigDecimal v) {
        return v.setScale(WEIGHT_SCALE, RM);
    }

    private record Calc(AssetPosition position, BigDecimal targetWeight, BigDecimal targetValue,
                        BigDecimal deltaValue, RebalanceOrder order) {
    }
}
