package com.wealth.mod1_rebalancer;

import com.wealth.mod1_rebalancer.RebalancePlan.CashFlowSummary;
import com.wealth.mod1_rebalancer.RebalancePlan.Constraints;
import com.wealth.mod1_rebalancer.RebalancePlan.Performance;
import com.wealth.mod1_rebalancer.RebalancePlan.PositionRow;
import com.wealth.mod1_rebalancer.RebalancePlan.ProjectedPosition;
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
 * cantidad = delta / precio, redondeada a 4 decimales HALF_UP. El importe de cada orden sale de esa cantidad ya
 * redondeada (lo que realmente se opera). El efectivo es un peso objetivo mas y actua como partida de cierre:
 * su saldo final es el saldo inicial - compras + ventas, a 2 decimales HALF_UP, de modo que el valor total se
 * conserva y los asientos contables de cada orden cuadran con la caja.
 * <p>
 * Ademas calcula el P&amp;L no realizado (valor - coste base) de la cartera actual y de la proyectada tras las ordenes.
 */
@Component
public class CashFlowRebalancer {

    /** Participaciones: maximo 4 decimales, HALF_UP. */
    static final int QTY_SCALE = 4;
    private static final RoundingMode QTY_RM = RoundingMode.HALF_UP;
    static final int WEIGHT_SCALE = 6;
    static final int MONEY_SCALE = 2;
    /** El efectivo se cierra siempre a 2 decimales con HALF_UP (precision contable de caja). */
    static final int CASH_SCALE = 2;
    private static final RoundingMode CASH_RM = RoundingMode.HALF_UP;
    private static final RoundingMode RM = RoundingMode.HALF_EVEN;
    private static final BigDecimal WEIGHT_TOLERANCE = new BigDecimal("0.000001");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

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
                BigDecimal deltaQty = deltaValue.divide(p.price(), QTY_SCALE, QTY_RM);
                if (deltaQty.signum() < 0) {
                    BigDecimal locked = lockedQuantities.getOrDefault(p.instrumentId(), BigDecimal.ZERO);
                    // El tope vendible se trunca (DOWN): nunca se ordena vender mas de lo disponible por redondeo.
                    BigDecimal sellable = p.quantity().subtract(locked).max(BigDecimal.ZERO)
                            .setScale(QTY_SCALE, RoundingMode.DOWN);
                    BigDecimal sellQty = deltaQty.negate().min(sellable);
                    if (sellQty.signum() > 0) {
                        order = order(p, Side.SELL, sellQty);
                        sellTotal = sellTotal.add(order.amount());
                    }
                } else if (deltaQty.signum() > 0) {
                    order = order(p, Side.BUY, deltaQty);
                    buyTotal = buyTotal.add(order.amount());
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
        List<ProjectedPosition> projected = new ArrayList<>();
        List<RebalanceOrder> orders = new ArrayList<>();
        BigDecimal currentCostTotal = BigDecimal.ZERO;
        BigDecimal projectedValueTotal = BigDecimal.ZERO;
        BigDecimal projectedCostTotal = BigDecimal.ZERO;

        for (Calc c : calcs) {
            AssetPosition p = c.position();
            RebalanceOrder order = c.order();

            BigDecimal postQty;
            if (p.isCash()) {
                postQty = cashAfterRounded;
            } else if (order == null) {
                postQty = qty(p.quantity());
            } else if (order.side() == Side.BUY) {
                postQty = qty(p.quantity().add(order.quantity()));
            } else {
                postQty = qty(p.quantity().subtract(order.quantity()));
            }
            BigDecimal postValue = postQty.multiply(p.price());
            BigDecimal postWeight = postValue.divide(total, WEIGHT_SCALE, RM);
            BigDecimal currentWeight = p.marketValue().divide(total, WEIGHT_SCALE, RM);
            BigDecimal targetWeight = weight(c.targetWeight());

            BigDecimal marketValue = money(p.marketValue());
            BigDecimal costBasis = money(p.costBasis());
            BigDecimal pnl = marketValue.subtract(costBasis);
            currentCostTotal = currentCostTotal.add(costBasis);

            rows.add(new PositionRow(
                    p.instrumentId(), p.isin(), p.name(), p.assetClass(),
                    qty(p.quantity()), p.price().setScale(MONEY_SCALE, RM), marketValue,
                    costBasis, pnl, pct(pnl, costBasis),
                    currentWeight, targetWeight, targetWeight.subtract(currentWeight),
                    money(c.targetValue()), money(c.deltaValue()),
                    order,
                    p.isCash() ? cashAfterRounded : postQty,
                    money(postValue), postWeight));

            BigDecimal projectedCost = projectedCostBasis(p, order, cashAfterRounded);
            BigDecimal projectedValue = money(postValue);
            BigDecimal projectedPnl = projectedValue.subtract(projectedCost);
            projectedValueTotal = projectedValueTotal.add(projectedValue);
            projectedCostTotal = projectedCostTotal.add(projectedCost);
            projected.add(new ProjectedPosition(
                    p.instrumentId(), p.isin(), p.name(), p.assetClass(),
                    p.isCash() ? cashAfterRounded : postQty, p.price().setScale(MONEY_SCALE, RM), projectedValue,
                    postWeight, targetWeight, targetWeight.subtract(postWeight),
                    projectedCost, projectedPnl, pct(projectedPnl, projectedCost)));

            if (order != null) {
                orders.add(order);
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
                new Constraints(false, new TreeMap<>(lockedQuantities)), rows, orders, summary,
                performance(money(total), currentCostTotal),
                projected, performance(projectedValueTotal, projectedCostTotal));
    }

    /**
     * Coste base tras operar, por COSTE MEDIO: compra suma su importe; venta retira la parte proporcional.
     * El efectivo va a valor nominal.
     */
    private static BigDecimal projectedCostBasis(AssetPosition p, RebalanceOrder order, BigDecimal cashAfter) {
        if (p.isCash()) {
            return cashAfter;
        }
        BigDecimal cost = money(p.costBasis());
        if (order == null) {
            return cost;
        }
        if (order.side() == Side.BUY) {
            return cost.add(order.amount());
        }
        BigDecimal removed = cost.multiply(order.quantity()).divide(p.quantity(), MONEY_SCALE, RM);
        return cost.subtract(removed).max(BigDecimal.ZERO);
    }

    private static Performance performance(BigDecimal totalValue, BigDecimal totalCost) {
        BigDecimal pnl = totalValue.subtract(totalCost);
        return new Performance(totalValue, totalCost, pnl, pct(pnl, totalCost));
    }

    /** pnl / coste * 100 con 2 decimales; 0,00 si no hay coste (evita division por cero). */
    private static BigDecimal pct(BigDecimal pnl, BigDecimal cost) {
        if (cost.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return pnl.multiply(HUNDRED).divide(cost, 2, RM);
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

    /** Orden ya redondeada: cantidad a 4 decimales e importe = cantidad redondeada * precio, a 2 decimales. */
    private static RebalanceOrder order(AssetPosition p, Side side, BigDecimal quantity) {
        return new RebalanceOrder(p.instrumentId(), p.name(), side, quantity,
                p.price().setScale(MONEY_SCALE, RM), money(quantity.multiply(p.price())), p.currency());
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RM);
    }

    private static BigDecimal qty(BigDecimal v) {
        return v.setScale(QTY_SCALE, QTY_RM);
    }

    private static BigDecimal weight(BigDecimal v) {
        return v.setScale(WEIGHT_SCALE, RM);
    }

    private record Calc(AssetPosition position, BigDecimal targetWeight, BigDecimal targetValue,
                        BigDecimal deltaValue, RebalanceOrder order) {
    }
}
