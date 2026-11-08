package com.wealth.mod1_rebalancer;

import com.wealth.ledger.jev.JevLedgerAdapter;
import com.wealth.ledger.jev.JevTransactionService;
import com.wealth.ledger.jev.JournalEntryModel.JournalBatch;
import com.wealth.mod1_rebalancer.CashInflowAllocator.Fund;
import com.wealth.mod1_rebalancer.ContributionPlan.FundRow;
import com.wealth.mod1_rebalancer.RebalancePlan.Performance;
import com.wealth.mod1_rebalancer.RebalancePlan.ProjectedPosition;
import com.wealth.mod1_rebalancer.Transfer.Status;
import com.wealth.mod1_rebalancer.Transfer.Type;
import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import com.wealth.shared.domain.RebalanceOrder;
import com.wealth.shared.domain.RebalanceOrder.Side;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Invertir una aportacion de efectivo en una agrupacion (cash-inflow rebalancing). Dos pasos:
 * <ol>
 *   <li>{@link #preview}: calcula, sin efectos, que comprar de cada fondo (CashInflowAllocator: solo compras).</li>
 *   <li>{@link #execute}: recalcula en el servidor, genera los asientos de partida doble (aportacion + compras),
 *       registra los movimientos (Aportacion y una Compra por fondo) y actualiza la cartera.</li>
 * </ol>
 * Participaciones a 4 decimales HALF_UP; el importe de cada compra sale de la cantidad ya redondeada, de modo que
 * orden, asiento y caja cuadran. Lo que no se invierte por ese redondeo (centimos) queda en efectivo.
 * No toca RebalanceOrchestrator.
 */
@Service
public class ContributionService {

    private static final int QTY_SCALE = 4;
    private static final int MONEY_SCALE = 2;
    private static final int WEIGHT_SCALE = 6;
    private static final RoundingMode QTY_RM = RoundingMode.HALF_UP;
    private static final RoundingMode RM = RoundingMode.HALF_EVEN;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000");
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final JevLedgerAdapter ledger;
    private final BasketService baskets;
    private final CashInflowAllocator allocator;
    private final TargetAllocationService targets;
    private final JevTransactionService journal;
    private final TransferService transfers;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();

    public ContributionService(JevLedgerAdapter ledger, BasketService baskets, CashInflowAllocator allocator,
                               TargetAllocationService targets, JevTransactionService journal,
                               TransferService transfers, Clock clock) {
        this.ledger = ledger;
        this.baskets = baskets;
        this.allocator = allocator;
        this.targets = targets;
        this.journal = journal;
        this.transfers = transfers;
        this.clock = clock;
    }

    /** Resultado de ejecutar una aportacion (respuesta de POST .../baskets/{id}/contribution). */
    public record ContributionExecution(
            String executionId,
            String portfolioId,
            String basketId,
            String basketName,
            Instant executedAt,
            String journalReference,
            List<Transfer> transfers,
            ContributionPlan plan) {
    }

    /** Propuesta sin efectos: que se compraria con {@code amount} euros en la agrupacion. */
    public ContributionPlan preview(String portfolioId, String basketId, BigDecimal amount) {
        BigDecimal contribution = normalizeAmount(amount);
        Portfolio portfolio = ledger.loadPortfolio(portfolioId);
        Basket basket = baskets.find(portfolioId, basketId);
        return buildPlan(portfolio, basket, contribution);
    }

    /** Serializado con el rebalanceo (mismo cerrojo): dos escrituras simultaneas sobre la cartera la corromperian. */
    public ContributionExecution execute(String portfolioId, String basketId, BigDecimal amount) {
        synchronized (ledger) {
            ContributionPlan plan = preview(portfolioId, basketId, amount);
            Instant now = clock.instant();
            String executionId = "CON-" + portfolioId + "-" + ID_TIME.format(now) + "-" + sequence.incrementAndGet();

            JournalBatch batch = journal.buildContributionJournal(portfolioId, plan.baseCurrency(), plan.amount(),
                    plan.orders(), executionId, now);
            List<Transfer> movements = toTransfers(plan, batch, executionId, now);
            Portfolio after = projectedPortfolio(ledger.loadPortfolio(portfolioId), plan);

            // Todo construido sin error: ahora se aplica.
            journal.record(batch);
            transfers.recordAll(movements);
            ledger.applyPostedState(after);

            return new ContributionExecution(executionId, portfolioId, basketId, plan.basketName(), now,
                    batch.reference(), movements.stream().map(t -> t.withStatusAt(now)).toList(), plan);
        }
    }

    // ---- calculo -----------------------------------------------------------------------------------------------

    private ContributionPlan buildPlan(Portfolio portfolio, Basket basket, BigDecimal contribution) {
        Map<String, AssetPosition> byId = new HashMap<>();
        portfolio.positions().forEach(p -> byId.put(p.instrumentId(), p));
        AssetPosition cash = portfolio.positions().stream().filter(AssetPosition::isCash).findFirst().orElseThrow();

        List<Fund> funds = new ArrayList<>();
        basket.weights().forEach((id, w) -> funds.add(new Fund(id, w, byId.get(id).marketValue())));
        Map<String, BigDecimal> wanted = allocator.allocate(funds, contribution);

        // Compras: cantidad a 4 decimales HALF_UP; el importe sale de esa cantidad (lo que realmente se compra).
        List<RebalanceOrder> orders = new ArrayList<>();
        Map<String, RebalanceOrder> orderById = new HashMap<>();
        BigDecimal invested = BigDecimal.ZERO;
        for (Fund f : funds) {
            BigDecimal buyAmount = wanted.get(f.instrumentId());
            AssetPosition p = byId.get(f.instrumentId());
            BigDecimal quantity = buyAmount.divide(p.price(), QTY_SCALE, QTY_RM);
            if (quantity.signum() <= 0) {
                continue;
            }
            RebalanceOrder order = new RebalanceOrder(p.instrumentId(), p.name(), Side.BUY, quantity,
                    p.price().setScale(MONEY_SCALE, RM), money(quantity.multiply(p.price())), p.currency());
            orders.add(order);
            orderById.put(p.instrumentId(), order);
            invested = invested.add(order.amount());
        }
        // Los redondeos HALF_UP de cada cantidad pueden hacer que la suma se pase unos centimos de la aportacion.
        // Nunca se gasta mas de lo ingresado: se quita 0,0001 participaciones a la orden mas grande hasta que cuadre
        // (determinista y siempre termina: cada vuelta reduce el total).
        while (invested.compareTo(contribution) > 0) {
            RebalanceOrder largest = orders.stream().max(Comparator.comparing(RebalanceOrder::amount)).orElseThrow();
            int idx = orders.indexOf(largest);
            AssetPosition p = byId.get(largest.instrumentId());
            BigDecimal reduced = largest.quantity().subtract(new BigDecimal("0.0001"));
            invested = invested.subtract(largest.amount());
            if (reduced.signum() <= 0) {
                orders.remove(idx);
                orderById.remove(largest.instrumentId());
            } else {
                RebalanceOrder smaller = new RebalanceOrder(p.instrumentId(), p.name(), Side.BUY, reduced,
                        p.price().setScale(MONEY_SCALE, RM), money(reduced.multiply(p.price())), p.currency());
                orders.set(idx, smaller);
                orderById.put(p.instrumentId(), smaller);
                invested = invested.add(smaller.amount());
            }
        }
        if (orders.isEmpty()) {
            throw new IllegalArgumentException("The amount is too small to buy any shares of this basket");
        }

        BigDecimal cashBefore = money(cash.quantity());
        BigDecimal cashAfter = money(cashBefore.add(contribution).subtract(invested));
        BigDecimal valueBefore = money(portfolio.totalValue());
        BigDecimal totalAfter = valueBefore.add(contribution);

        // Detalle de la agrupacion (peso dentro de la cesta antes/despues)
        BigDecimal basketBefore = BigDecimal.ZERO;
        BigDecimal basketAfter = BigDecimal.ZERO;
        Map<String, BigDecimal> valueAfterById = new HashMap<>();
        for (Fund f : funds) {
            RebalanceOrder o = orderById.get(f.instrumentId());
            AssetPosition p = byId.get(f.instrumentId());
            BigDecimal qtyAfter = qty(p.quantity().add(o == null ? BigDecimal.ZERO : o.quantity()));
            BigDecimal valueAfter = money(qtyAfter.multiply(p.price()));
            valueAfterById.put(f.instrumentId(), valueAfter);
            basketBefore = basketBefore.add(f.currentValue());
            basketAfter = basketAfter.add(valueAfter);
        }
        List<FundRow> fundRows = new ArrayList<>();
        for (Fund f : funds) {
            RebalanceOrder o = orderById.get(f.instrumentId());
            BigDecimal valueAfter = valueAfterById.get(f.instrumentId());
            fundRows.add(new ContributionPlan.FundRow(
                    f.instrumentId(), byId.get(f.instrumentId()).name(), weight(f.weight()),
                    money(f.currentValue()), share(f.currentValue(), basketBefore),
                    o == null ? BigDecimal.ZERO.setScale(MONEY_SCALE) : o.amount(),
                    o == null ? BigDecimal.ZERO.setScale(QTY_SCALE) : o.quantity(),
                    valueAfter, share(valueAfter, basketAfter)));
        }

        // Cartera completa tras invertir, frente a la meta de la cartera
        Map<String, BigDecimal> portfolioTargets = targets.targetsFor(portfolio.portfolioId());
        List<ProjectedPosition> projected = new ArrayList<>();
        BigDecimal currentCost = BigDecimal.ZERO;
        BigDecimal projectedCost = BigDecimal.ZERO;
        BigDecimal projectedValue = BigDecimal.ZERO;
        for (AssetPosition p : portfolio.positions()) {
            RebalanceOrder o = orderById.get(p.instrumentId());
            BigDecimal qtyAfter = p.isCash() ? cashAfter : qty(p.quantity().add(o == null ? BigDecimal.ZERO : o.quantity()));
            BigDecimal valueAfter = money(qtyAfter.multiply(p.price()));
            BigDecimal costAfter = p.isCash() ? cashAfter : money(p.costBasis()).add(o == null ? BigDecimal.ZERO : o.amount());
            BigDecimal weightAfter = valueAfter.divide(totalAfter, WEIGHT_SCALE, RM);
            BigDecimal target = weight(portfolioTargets.getOrDefault(p.instrumentId(), BigDecimal.ZERO));
            BigDecimal pnl = valueAfter.subtract(costAfter);
            projected.add(new ProjectedPosition(p.instrumentId(), p.isin(), p.name(), p.assetClass(), qtyAfter,
                    p.price().setScale(MONEY_SCALE, RM), valueAfter, weightAfter, target, target.subtract(weightAfter),
                    costAfter, pnl, pct(pnl, costAfter)));
            currentCost = currentCost.add(money(p.costBasis()));
            projectedCost = projectedCost.add(costAfter);
            projectedValue = projectedValue.add(valueAfter);
        }
        return new ContributionPlan(portfolio.portfolioId(), basket.id(), basket.name(), portfolio.baseCurrency(),
                portfolio.asOf(), contribution, invested, contribution.subtract(invested), fundRows, orders,
                cashBefore, cashAfter, projected, performance(valueBefore, currentCost),
                performance(projectedValue, projectedCost));
    }

    // ---- movimientos y estado ---------------------------------------------------------------------------------

    /**
     * Una Aportacion (liquidada al instante: es un ingreso de efectivo) y una Compra por fondo (con la liquidacion
     * simulada de siempre: En proceso -> Completado). Los ids de asiento enlazan con el lote de partida doble.
     */
    private List<Transfer> toTransfers(ContributionPlan plan, JournalBatch batch, String executionId, Instant now) {
        Instant settlesAt = now.plus(transfers.settlementDelay());
        List<Transfer> result = new ArrayList<>();
        result.add(new Transfer(executionId + "-T01", executionId, plan.portfolioId(), now, now, Type.CONTRIBUTION,
                AssetPosition.CASH_PREFIX + plan.baseCurrency(), "Aportación a " + plan.basketName(), null,
                plan.amount(), plan.baseCurrency(), batch.entries().get(0).entryId(), Status.IN_PROGRESS));
        int n = 2;
        for (int i = 0; i < plan.orders().size(); i++) {
            RebalanceOrder o = plan.orders().get(i);
            result.add(new Transfer(executionId + "-T" + String.format("%02d", n++), executionId, plan.portfolioId(),
                    now, settlesAt, Type.BUY, o.instrumentId(), o.name(), o.quantity(), o.amount(), o.currency(),
                    batch.entries().get(i + 1).entryId(), Status.IN_PROGRESS));
        }
        return result;
    }

    /** Cartera tal y como queda tras invertir (la proyeccion del plan, con coste medio). */
    private static Portfolio projectedPortfolio(Portfolio before, ContributionPlan plan) {
        String currency = plan.baseCurrency();
        List<AssetPosition> positions = plan.projectedPositions().stream()
                .map(p -> p.assetClass() == AssetClass.CASH
                        ? AssetPosition.cash(currency, p.quantity())
                        : new AssetPosition(p.instrumentId(), p.isin(), p.name(), p.assetClass(), p.quantity(),
                                p.price(), currency, p.costBasis()))
                .toList();
        return new Portfolio(before.portfolioId(), before.clientId(), currency, before.asOf(), positions);
    }

    // ---- utilidades ----------------------------------------------------------------------------------------------

    private static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        BigDecimal rounded = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (rounded.signum() <= 0) {
            throw new IllegalArgumentException("The contribution must be at least 0.01");
        }
        if (rounded.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("The contribution exceeds the maximum allowed (" + MAX_AMOUNT + ")");
        }
        return rounded;
    }

    private static Performance performance(BigDecimal totalValue, BigDecimal totalCost) {
        BigDecimal pnl = totalValue.subtract(totalCost);
        return new Performance(totalValue, totalCost, pnl, pct(pnl, totalCost));
    }

    private static BigDecimal pct(BigDecimal pnl, BigDecimal cost) {
        if (cost.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return pnl.multiply(HUNDRED).divide(cost, 2, RM);
    }

    private static BigDecimal share(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? BigDecimal.ZERO.setScale(WEIGHT_SCALE) : part.divide(whole, WEIGHT_SCALE, RM);
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
}
