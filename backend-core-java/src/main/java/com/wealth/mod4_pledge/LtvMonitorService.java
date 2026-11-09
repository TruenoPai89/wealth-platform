package com.wealth.mod4_pledge;

import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Módulo 4: estado de un préstamo pignoraticio. Cálculo 100 % determinista (Claude no interviene).
 * <ul>
 *   <li>Valor de garantía = suma de valor de mercado x (1 - haircut) de cada posición.</li>
 *   <li>LTV = deuda / valor de garantía, comparado con el límite y con el umbral de margin call.</li>
 *   <li>Acciones para volver al límite: amortizar, aportar efectivo o vender por clase de activo.</li>
 *   <li>Escenario: caída de la renta variable (equityShock) aplicada a los precios EQUITY.</li>
 *   <li>Probabilidad estimada de margin call en un horizonte (movimiento browniano geométrico).</li>
 * </ul>
 */
@Service
public class LtvMonitorService {

    private static final MathContext MC = MathContext.DECIMAL64;

    /**
     * Volatilidades anuales SUPUESTAS por clase de activo (no estimadas con datos).
     * Se usan solo para la probabilidad de margin call, que se presenta como estimación.
     */
    static final Map<AssetClass, Double> ANNUAL_VOLATILITY = Map.of(
        AssetClass.EQUITY, 0.18,
        AssetClass.FIXED_INCOME, 0.06,
        AssetClass.COMMODITY, 0.15,
        AssetClass.CASH, 0.0
    );

    public enum Zone { OK, LIMIT_EXCEEDED, MARGIN_CALL }

    public record PositionCollateral(
        String instrumentId, 
        String name,
        AssetClass assetClass,
        BigDecimal marketValue, 
        BigDecimal haircut, 
        BigDecimal collateralValue
    ) {}

    /**
     * @param marketValueToSell valor de MERCADO a vender de esa clase para volver al límite.
     * @param available         valor de mercado disponible de esa clase.
     * @param feasible          si hay suficiente para cubrirlo solo con esa clase.
     */
    public record SaleAction(BigDecimal marketValueToSell, BigDecimal available, boolean feasible) {}

    /**
     * @param repayCash     efectivo externo a amortizar.
     * @param addCollateral efectivo a aportar como garantía adicional.
     * @param sellByClass   alternativa: vender activos de una clase y amortizar con lo obtenido.
     */
    public record Actions(BigDecimal repayCash, BigDecimal addCollateral, Map<AssetClass, SaleAction> sellByClass) {}

    /**
     * @param collateralDropToMarginCall caída del valor de garantía que dispara el margin call (0.19 = 19 %).
     * @param equityDropToMarginCall     caída de la renta variable que lo dispara (null si ni al 100 %).
     * @param marginCallProbability      estimación en el horizonte (0..1). Supuestos: ver ANNUAL_VOLATILITY.
     */
    public record PledgeStatus(
        String portfolioId, 
        String contractId, 
        String loanId, 
        BigDecimal equityShock,
        BigDecimal marketValue, 
        BigDecimal collateralValue, 
        BigDecimal loanBalance,
        BigDecimal ltv, 
        BigDecimal ltvMax, 
        BigDecimal ltvMarginCall, 
        Zone zone,
        Integer cureDays, 
        BigDecimal collateralDropToMarginCall,
        BigDecimal equityDropToMarginCall, 
        Actions actions,
        int horizonDays, 
        BigDecimal collateralVolatility,
        BigDecimal marginCallProbability,
        List<PositionCollateral> positions
    ) {}

    public PledgeStatus status(Portfolio portfolio, PledgeContractEntity contract, BigDecimal equityShock, int horizonDays) {
        BigDecimal shock = equityShock == null ? BigDecimal.ZERO : equityShock;
        if (shock.signum() < 0 || shock.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("equityShock debe estar entre 0 y 1 (0.25 = caída del 25 %)");
        }
        if (horizonDays <= 0) {
            throw new IllegalArgumentException("horizonDays debe ser > 0");
        }

        // 1. Valor de mercado y de garantía por posición (con el escenario aplicado).
        List<PositionCollateral> rows = new ArrayList<>();
        Map<AssetClass, BigDecimal> marketByClass = new EnumMap<>(AssetClass.class);
        Map<AssetClass, BigDecimal> collateralByClass = new EnumMap<>(AssetClass.class);
        BigDecimal market = BigDecimal.ZERO;
        BigDecimal collateral = BigDecimal.ZERO;

        for (AssetPosition p : portfolio.positions()) {
            BigDecimal mv = p.marketValue();
            if (p.assetClass() == AssetClass.EQUITY) {
                mv = mv.multiply(BigDecimal.ONE.subtract(shock));
            }
            BigDecimal h = contract.haircutFor(p.assetClass());
            BigDecimal cv = mv.multiply(BigDecimal.ONE.subtract(h));

            rows.add(new PositionCollateral(p.instrumentId(), p.name(), p.assetClass(), money(mv), h, money(cv)));
            marketByClass.merge(p.assetClass(), mv, BigDecimal::add);
            collateralByClass.merge(p.assetClass(), cv, BigDecimal::add);
            market = market.add(mv);
            collateral = collateral.add(cv);
        }

        if (collateral.signum() <= 0) {
            throw new IllegalArgumentException("La cartera no tiene garantía admitida");
        }

        // 2. LTV y zona.
        BigDecimal d = contract.loanBalance();
        BigDecimal lMax = contract.ltvMax();
        BigDecimal lCall = contract.ltvMarginCall();
        BigDecimal ltv = d.divide(collateral, MC);
        Zone zone = ltv.compareTo(lCall) >= 0 ? Zone.MARGIN_CALL
                  : ltv.compareTo(lMax) > 0  ? Zone.LIMIT_EXCEEDED : Zone.OK;

        // 3. Distancia al margin call.
        BigDecimal criticalCollateral = d.divide(lCall, MC); // V_c = D / lambda_c
        BigDecimal cushion = collateral.subtract(criticalCollateral); // cuánto puede bajar V
        BigDecimal dropToCall = cushion.signum() <= 0 ? BigDecimal.ZERO : cushion.divide(collateral, MC);

        BigDecimal eqColl = collateralByClass.getOrDefault(AssetClass.EQUITY, BigDecimal.ZERO);
        BigDecimal equityDrop;
        if (cushion.signum() <= 0) {
            equityDrop = BigDecimal.ZERO;
        } else if (eqColl.signum() == 0 || cushion.compareTo(eqColl) > 0) {
            equityDrop = null; // ni al 100 %
        } else {
            equityDrop = cushion.divide(eqColl, MC);
        }

        // 4. Acciones para volver al límite de disposición.
        BigDecimal excess = d.subtract(lMax.multiply(collateral)); // D - L * V
        BigDecimal repay = excess.max(BigDecimal.ZERO);
        BigDecimal add = d.divide(lMax, MC).subtract(collateral).max(BigDecimal.ZERO);

        Map<AssetClass, SaleAction> sales = new EnumMap<>(AssetClass.class);
        for (Map.Entry<AssetClass, BigDecimal> e : marketByClass.entrySet()) {
            if (e.getKey() == AssetClass.CASH) continue; // usar efectivo = amortizar
            
            BigDecimal h = contract.haircutFor(e.getKey());
            // Vender x de mercado amortiza x de deuda y quita (1-h)x de garantía:
            // (D - x) / (V - (1-h) x) = L => x = (D - L V) / (1 - L (1-h))
            BigDecimal denom = BigDecimal.ONE.subtract(lMax.multiply(BigDecimal.ONE.subtract(h)));
            BigDecimal x = repay.signum() == 0 ? BigDecimal.ZERO : repay.divide(denom, MC);
            sales.put(e.getKey(), new SaleAction(money(x), money(e.getValue()), x.compareTo(e.getValue()) <= 0));
        }

        // 5. Probabilidad estimada de margin call.
        double sigma = 0.0;
        for (Map.Entry<AssetClass, BigDecimal> e : collateralByClass.entrySet()) {
            double w = e.getValue().divide(collateral, MC).doubleValue();
            sigma += w * ANNUAL_VOLATILITY.getOrDefault(e.getKey(), 0.0); // correlación 1: cota conservadora
        }

        double prob = barrierHitProbability(criticalCollateral.divide(collateral, MC).doubleValue(), sigma, horizonDays / 365.0);

        return new PledgeStatus(
            portfolio.portfolioId(),
            contract.contractId(), 
            contract.loanId(),
            shock, 
            money(market), 
            money(collateral), 
            money(d),
            ltv.setScale(4, RoundingMode.HALF_UP), 
            lMax, 
            lCall, 
            zone,
            contract.cureDays(),
            dropToCall.setScale(4, RoundingMode.HALF_UP),
            equityDrop == null ? null : equityDrop.setScale(4, RoundingMode.HALF_UP),
            new Actions(money(repay), money(add), sales),
            horizonDays, 
            BigDecimal.valueOf(sigma).setScale(4, RoundingMode.HALF_UP),
            BigDecimal.valueOf(prob).setScale(4, RoundingMode.HALF_UP),
            List.copyOf(rows)
        );
    }

    /**
     * Probabilidad de que un movimiento browniano geométrico sin deriva (mu = 0) toque la barrera
     * antes de T, partiendo de 1. Fórmula cerrada por el principio de reflexión:
     * P = Phi((b - nu T)/(s sqrt T)) + exp(2 nu b / s^2) Phi((b + nu T)/(s sqrt T)),
     * con b = ln(barrera), nu = -s^2/2.
     *
     * @param barrierOverValue V_c / V (menor que 1 si aún no se ha tocado).
     * @param sigma            volatilidad anual.
     * @param years            horizonte en años.
     */
    static double barrierHitProbability(double barrierOverValue, double sigma, double years) {
        if (barrierOverValue >= 1.0) return 1.0;
        if (barrierOverValue <= 0.0 || sigma <= 0.0 || years <= 0.0) return 0.0;

        double b = Math.log(barrierOverValue);
        double nu = -0.5 * sigma * sigma;
        double sd = sigma * Math.sqrt(years);

        return normalCdf((b - nu * years) / sd)
             + Math.exp(2 * nu * b / (sigma * sigma)) * normalCdf((b + nu * years) / sd);
    }

    /** Distribución normal estándar vía erf (Abramowitz-Stegun 7.1.26, error < 1.5e-7). */
    static double normalCdf(double x) {
        double z = Math.abs(x) / Math.sqrt(2);
        double t = 1.0 / (1.0 + 0.3275911 * z);
        double poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))));
        double erf = 1.0 - poly * Math.exp(-z * z);
        return x >= 0 ? 0.5 * (1 + erf) : 0.5 * (1 - erf);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}