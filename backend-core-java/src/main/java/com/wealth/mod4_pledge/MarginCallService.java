package com.wealth.mod4_pledge;

import com.wealth.mod4_pledge.LtvMonitorService.Zone;
import com.wealth.mod4_pledge.MarginRiskAnalysisDto.CurvePoint;
import com.wealth.mod4_pledge.MarginRiskAnalysisDto.FundBreakdown;
import com.wealth.mod4_pledge.MarginRiskAnalysisDto.HorizonProbability;
import com.wealth.mod4_pledge.MarginRiskAnalysisDto.Metrics;
import com.wealth.mod4_pledge.MarginRiskAnalysisDto.Recommendation;
import com.wealth.mod4_pledge.MarginRiskAnalysisDto.SecurityLevel;
import com.wealth.mod4_pledge.MarginRiskAnalysisDto.StressScenario;
import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Análisis de margin call para una pignoración configurada a mano (sin contrato en PDF). Cálculo 100 %
 * determinista: la IA solo redacta después la explicación, nunca decide cifras.
 * <ul>
 *   <li>Garantía computable = suma de (importe aportado x (1 - descuento)) de cada fondo.</li>
 *   <li>LTV = deuda / garantía computable; margin call cuando LTV &gt;= umbral.</li>
 *   <li>Garantía requerida = deuda / umbral; colchón = garantía - requerida.</li>
 *   <li>Volatilidad de la garantía con correlaciones entre fondos (no la cota con correlación 1).</li>
 *   <li>Probabilidad de margin call a 30/90/365 días: movimiento browniano geométrico sin deriva, misma fórmula
 *       cerrada que {@link LtvMonitorService}.</li>
 *   <li>Test de estrés: caída simultánea y uniforme de todos los fondos aportados.</li>
 * </ul>
 */
@Service
public class MarginCallService {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final String DEFAULT_PORTFOLIO = "PF-001";

    /** Descuentos del contrato de ejemplo; se usan si la petición no trae los suyos. */
    static final Map<AssetClass, BigDecimal> DEFAULT_HAIRCUTS = Map.of(
            AssetClass.EQUITY, new BigDecimal("0.30"),
            AssetClass.FIXED_INCOME, new BigDecimal("0.10"),
            AssetClass.COMMODITY, new BigDecimal("0.25"),
            AssetClass.CASH, BigDecimal.ZERO);

    static final int[] HORIZON_DAYS = {30, 90, 365};
    static final String[] STRESS_DROPS = {"0.10", "0.20", "0.30"};
    /** Caída contra la que se mide el "colchón de seguridad" para recomendar. */
    private static final BigDecimal TARGET_DROP = new BigDecimal("0.30");
    private static final int CURVE_POINTS = 13; // 0 % .. 60 % de caída, de 5 en 5
    private static final BigDecimal CURVE_STEP = new BigDecimal("0.05");
    private static final BigDecimal PENNY = new BigDecimal("0.005");

    private final FundRiskProfile riskProfile;

    public MarginCallService(FundRiskProfile riskProfile) {
        this.riskProfile = riskProfile;
    }

    /** Id de cartera efectivo: PF-001 si la petición no lo indica. */
    public static String portfolioIdOf(PledgeConfigDto config) {
        return config == null || config.portfolioId() == null || config.portfolioId().isBlank()
                ? DEFAULT_PORTFOLIO : config.portfolioId().trim();
    }

    /** Fondo aportado ya valorado. */
    private record Leg(AssetPosition position, BigDecimal pledged, BigDecimal haircut, BigDecimal collateral) {}

    /**
     * @throws IllegalArgumentException si la configuración no es válida (el controller la traduce a 400).
     */
    public MarginRiskAnalysisDto analyze(Portfolio portfolio, PledgeConfigDto config) {
        if (config == null) throw new IllegalArgumentException("Falta la configuración de pignoración");

        BigDecimal loan = require(config.loanAmount(), "El importe del crédito es obligatorio");
        if (loan.signum() < 0) throw new IllegalArgumentException("El importe del crédito debe ser >= 0");

        BigDecimal rate = require(config.interestRate(), "El tipo de interés es obligatorio");
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("El tipo de interés debe estar entre 0 y 1 (0,045 = 4,5 %)");
        }

        Integer years = config.termYears();
        if (years == null || years < 1 || years > 40) {
            throw new IllegalArgumentException("El plazo debe estar entre 1 y 40 años");
        }

        BigDecimal threshold = require(config.marginCallLtv(), "El umbral de margin call es obligatorio");
        if (threshold.signum() <= 0 || threshold.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("El umbral de margin call debe estar entre 0 y 1 (0,70 = 70 %)");
        }

        Map<AssetClass, BigDecimal> haircuts = haircutsOf(config.haircuts());
        List<Leg> legs = legsOf(portfolio, config, haircuts);

        BigDecimal collateral = sumCollateral(legs);
        if (collateral.signum() <= 0) {
            throw new IllegalArgumentException(
                    "La garantía computable es 0: con esos descuentos los fondos elegidos no cuentan como garantía");
        }

        // ── Métricas principales ──────────────────────────────────────────────────────
        BigDecimal ltv = loan.divide(collateral, MC);
        BigDecimal required = loan.divide(threshold, MC);
        BigDecimal surplus = collateral.subtract(required);
        BigDecimal headroom = threshold.subtract(ltv);
        BigDecimal distance = BigDecimal.ONE.subtract(ltv.divide(threshold, MC));
        Zone zone = ltv.compareTo(threshold) >= 0 ? Zone.MARGIN_CALL : Zone.OK;

        double sigma = volatility(legs);
        BigDecimal growth = BigDecimal.ONE.add(rate);
        BigDecimal interestOverTerm = loan.multiply(growth.pow(years, MC).subtract(BigDecimal.ONE), MC);

        Metrics metrics = new Metrics(
                money(loan), money(sumPledged(legs)), money(collateral), money(required), money(surplus),
                frac(ltv), threshold.setScale(4, RoundingMode.HALF_UP), frac(headroom), frac(distance), zone,
                BigDecimal.valueOf(sigma).setScale(4, RoundingMode.HALF_UP),
                money(loan.multiply(rate, MC)), money(interestOverTerm));

        // ── Desglose por fondo ────────────────────────────────────────────────────────
        List<FundBreakdown> funds = breakdown(legs, collateral, surplus, sigma);

        // ── Probabilidad de margin call por horizonte ─────────────────────────────────
        List<HorizonProbability> probabilities = new ArrayList<>();
        for (int days : HORIZON_DAYS) {
            double yearsFraction = days / 365.0;
            BigDecimal projectedLoan = loan.multiply(
                    BigDecimal.valueOf(Math.pow(growth.doubleValue(), yearsFraction)), MC);
            // Barrera fija = la deuda al final del horizonte: cota conservadora (la deuda real crece despacio).
            double barrier = projectedLoan.divide(threshold, MC).divide(collateral, MC).doubleValue();
            double p = LtvMonitorService.barrierHitProbability(barrier, sigma, yearsFraction);
            probabilities.add(new HorizonProbability(days,
                    BigDecimal.valueOf(p).setScale(4, RoundingMode.HALF_UP), money(projectedLoan)));
        }

        // ── Test de estrés y curva de caída ───────────────────────────────────────────
        List<StressScenario> stress = new ArrayList<>();
        for (String d : STRESS_DROPS) {
            stress.add(stress(new BigDecimal(d), collateral, loan, threshold));
        }
        List<CurvePoint> curve = new ArrayList<>();
        for (int i = 0; i < CURVE_POINTS; i++) {
            BigDecimal drop = CURVE_STEP.multiply(BigDecimal.valueOf(i));
            BigDecimal c = collateral.multiply(BigDecimal.ONE.subtract(drop), MC);
            curve.add(new CurvePoint(drop.setScale(2, RoundingMode.HALF_UP), money(c), frac(loan.divide(c, MC))));
        }

        // ── Nivel de seguridad y recomendaciones ──────────────────────────────────────
        double p365 = probabilities.get(probabilities.size() - 1).probability().doubleValue();
        StressScenario at30 = stress.get(2);
        SecurityLevel level = securityLevel(zone, stress.get(0).zone(), stress.get(1).zone(), p365);
        List<Recommendation> recs = recommendations(portfolio, legs, haircuts, funds, metrics, at30, sigma, threshold);

        return new MarginRiskAnalysisDto(
                portfolio.portfolioId(), metrics, funds, probabilities, stress, curve, level, recs,
                assumptions(legs, config.haircuts() != null && !config.haircuts().isEmpty()));
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // Validación y construcción de las garantías
    // ═════════════════════════════════════════════════════════════════════════════════

    private static Map<AssetClass, BigDecimal> haircutsOf(Map<AssetClass, BigDecimal> given) {
        if (given == null || given.isEmpty()) return DEFAULT_HAIRCUTS;
        given.forEach((k, v) -> {
            if (v == null || v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("El descuento de " + k + " debe estar entre 0 y 1");
            }
        });
        return given;
    }

    private static List<Leg> legsOf(Portfolio portfolio, PledgeConfigDto config, Map<AssetClass, BigDecimal> haircuts) {
        if (config.collateral() == null || config.collateral().isEmpty()) {
            throw new IllegalArgumentException("Selecciona al menos un fondo como garantía");
        }
        Map<String, AssetPosition> byId = new LinkedHashMap<>();
        portfolio.positions().forEach(p -> byId.put(p.instrumentId(), p));

        Set<String> seen = new HashSet<>();
        List<Leg> legs = new ArrayList<>();
        for (PledgeConfigDto.CollateralItem item : config.collateral()) {
            if (item == null || item.instrumentId() == null || item.amount() == null) {
                throw new IllegalArgumentException("Cada garantía necesita instrumentId e importe");
            }
            AssetPosition position = byId.get(item.instrumentId());
            if (position == null) {
                throw new IllegalArgumentException(
                        "El fondo " + item.instrumentId() + " no está en la cartera " + portfolio.portfolioId());
            }
            if (position.isCash()) {
                throw new IllegalArgumentException("El efectivo no se selecciona como fondo en garantía");
            }
            if (!seen.add(item.instrumentId())) {
                throw new IllegalArgumentException("El fondo " + item.instrumentId() + " está repetido");
            }
            if (item.amount().signum() <= 0) {
                throw new IllegalArgumentException("El importe aportado de " + position.name() + " debe ser > 0");
            }
            if (item.amount().compareTo(position.marketValue().add(PENNY)) > 0) {
                throw new IllegalArgumentException("El importe aportado de " + position.name()
                        + " supera el valor de la posición (" + money(position.marketValue()) + " EUR)");
            }
            legs.add(leg(position, item.amount(), haircuts));
        }
        return legs;
    }

    private static Leg leg(AssetPosition position, BigDecimal pledged, Map<AssetClass, BigDecimal> haircuts) {
        BigDecimal haircut = haircuts.getOrDefault(position.assetClass(), BigDecimal.ONE);
        return new Leg(position, pledged, haircut, pledged.multiply(BigDecimal.ONE.subtract(haircut), MC));
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // Volatilidad y desglose
    // ═════════════════════════════════════════════════════════════════════════════════

    /** Volatilidad anual del valor de la garantía: raíz de w' S R S w, con pesos sobre el valor computable. */
    private double volatility(List<Leg> legs) {
        BigDecimal total = sumCollateral(legs);
        if (total.signum() <= 0) return 0.0;
        double var = 0.0;
        for (Leg a : legs) {
            for (Leg b : legs) {
                var += weight(a, total) * weight(b, total)
                        * riskProfile.volatility(a.position()) * riskProfile.volatility(b.position())
                        * riskProfile.correlation(a.position(), b.position());
            }
        }
        return Math.sqrt(Math.max(var, 0.0));
    }

    private static double weight(Leg leg, BigDecimal total) {
        return leg.collateral().divide(total, MC).doubleValue();
    }

    private List<FundBreakdown> breakdown(List<Leg> legs, BigDecimal collateral, BigDecimal surplus, double sigma) {
        double variance = sigma * sigma;
        List<FundBreakdown> out = new ArrayList<>();
        for (Leg leg : legs) {
            double w = weight(leg, collateral);
            double s = riskProfile.volatility(leg.position());

            // Contribución a la varianza = w_i s_i (sum_j w_j s_j rho_ij) / varianza total; suman 1.
            double cov = 0.0;
            for (Leg other : legs) {
                cov += weight(other, collateral) * riskProfile.volatility(other.position())
                        * riskProfile.correlation(leg.position(), other.position());
            }
            double contribution = variance > 0 ? w * s * cov / variance : 0.0;

            // Caída de ESTE fondo (él solo) que agota el colchón: surplus / collateral_i.
            BigDecimal standalone = null;
            if (surplus.signum() <= 0) {
                standalone = BigDecimal.ZERO.setScale(4);
            } else if (leg.collateral().signum() > 0) {
                BigDecimal drop = surplus.divide(leg.collateral(), MC);
                if (drop.compareTo(BigDecimal.ONE) <= 0) standalone = frac(drop);
            }

            out.add(new FundBreakdown(
                    leg.position().instrumentId(), leg.position().name(), leg.position().assetClass(),
                    money(leg.pledged()), money(leg.position().marketValue()),
                    leg.haircut().setScale(4, RoundingMode.HALF_UP), money(leg.collateral()),
                    BigDecimal.valueOf(w).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(s).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(contribution).setScale(4, RoundingMode.HALF_UP),
                    money(leg.collateral().multiply(BigDecimal.valueOf(s), MC)),
                    standalone));
        }
        return List.copyOf(out);
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // Estrés y nivel de seguridad
    // ═════════════════════════════════════════════════════════════════════════════════

    private static StressScenario stress(BigDecimal drop, BigDecimal collateral, BigDecimal loan, BigDecimal threshold) {
        BigDecimal c = collateral.multiply(BigDecimal.ONE.subtract(drop), MC);
        BigDecimal ltv = loan.divide(c, MC);
        Zone zone = ltv.compareTo(threshold) >= 0 ? Zone.MARGIN_CALL : Zone.OK;
        BigDecimal topUp = loan.divide(threshold, MC).subtract(c).max(BigDecimal.ZERO);
        BigDecimal repay = loan.subtract(threshold.multiply(c, MC)).max(BigDecimal.ZERO);
        return new StressScenario(drop.setScale(2, RoundingMode.HALF_UP), money(c), frac(ltv), zone,
                money(topUp), money(repay));
    }

    /**
     * Regla (documentada también en {@code assumptions}):
     * <ul>
     *   <li>LOW: ya hay margin call, o una caída del 10 % lo provoca, o P(365 d) &gt;= 25 %.</li>
     *   <li>MEDIUM: una caída del 20 % lo provoca, o P(365 d) &gt;= 10 %.</li>
     *   <li>HIGH: en otro caso.</li>
     * </ul>
     */
    static SecurityLevel securityLevel(Zone zone, Zone at10, Zone at20, double p365) {
        if (zone == Zone.MARGIN_CALL || at10 == Zone.MARGIN_CALL || p365 >= 0.25) return SecurityLevel.LOW;
        if (at20 == Zone.MARGIN_CALL || p365 >= 0.10) return SecurityLevel.MEDIUM;
        return SecurityLevel.HIGH;
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // Recomendaciones
    // ═════════════════════════════════════════════════════════════════════════════════

    private List<Recommendation> recommendations(Portfolio portfolio, List<Leg> legs,
                                                 Map<AssetClass, BigDecimal> haircuts, List<FundBreakdown> funds,
                                                 Metrics m, StressScenario at30, double sigma, BigDecimal threshold) {
        List<Recommendation> recs = new ArrayList<>();
        BigDecimal loan = m.loanAmount();
        BigDecimal collateral = m.collateralValue();

        if (m.zone() == Zone.MARGIN_CALL) {
            BigDecimal topUp = m.requiredCollateral().subtract(collateral).max(BigDecimal.ZERO);
            BigDecimal repay = loan.subtract(threshold.multiply(collateral, MC)).max(BigDecimal.ZERO);
            recs.add(new Recommendation("CURE_MARGIN_CALL",
                    "El LTV (" + pct(m.ltv()) + ") ya alcanza el umbral de margin call (" + pct(m.marginCallLtv())
                            + "). Para volver al umbral hay que aportar " + eur(topUp)
                            + " de garantía computable (por ejemplo, efectivo) o amortizar " + eur(repay)
                            + " del crédito.",
                    figures("topUp", money(topUp), "repay", money(repay), "ltv", m.ltv())));
        }

        boolean needsBuffer = at30.zone() == Zone.MARGIN_CALL;
        if (needsBuffer) {
            BigDecimal keep = BigDecimal.ONE.subtract(TARGET_DROP);
            BigDecimal addCollateral = m.requiredCollateral().divide(keep, MC).subtract(collateral).max(BigDecimal.ZERO);
            BigDecimal repay = loan.subtract(threshold.multiply(keep, MC).multiply(collateral, MC)).max(BigDecimal.ZERO);
            recs.add(new Recommendation("RESTORE_BUFFER",
                    "Con la garantía actual, una caída del 30 % de los fondos provocaría margin call. Para resistirla "
                            + "habría que sumar " + eur(addCollateral) + " de garantía computable o amortizar "
                            + eur(repay) + " del crédito.",
                    figures("addCollateral", money(addCollateral), "repay", money(repay))));
        }

        // Solo cuando el riesgo importa: con renta variable y bonos, la renta variable siempre aporta > 50 % del
        // riesgo, y avisarlo con una garantía holgada sería ruido.
        boolean riskMatters = needsBuffer || m.zone() == Zone.MARGIN_CALL;
        for (FundBreakdown f : funds) {
            if (riskMatters && f.riskContribution().doubleValue() >= 0.5) {
                recs.add(new Recommendation("CONCENTRATION",
                        funds.size() == 1
                                ? "Toda la garantía depende de un solo fondo (" + f.name()
                                  + "): repartirla entre varios fondos poco correlacionados reduce el riesgo."
                                : f.name() + " aporta el " + pct(f.riskContribution()) + " del riesgo de la garantía "
                                  + "con un peso del " + pct(f.weight()) + ". Reducir su peso o añadir fondos poco "
                                  + "correlacionados rebajaría la volatilidad.",
                        figures("riskContribution", f.riskContribution(), "weight", f.weight())));
                break;
            }
        }

        if (needsBuffer) {
            lowVolatilityIdea(portfolio, legs, haircuts, m, sigma, threshold).ifPresent(recs::add);
        }

        if (recs.isEmpty()) {
            recs.add(new Recommendation("NO_ACTION_NEEDED",
                    "La garantía soporta una caída del 30 % sin margin call. No hace falta rebalancear la garantía.",
                    Map.of()));
        }
        return List.copyOf(recs);
    }

    /**
     * Si hay un fondo de la cartera con saldo sin pignorar y menos volátil que la garantía actual: qué pasaría al
     * aportar todo lo disponible de él. Solo se sugiere si de verdad mejora distancia y volatilidad.
     */
    private Optional<Recommendation> lowVolatilityIdea(Portfolio portfolio, List<Leg> legs,
                                                       Map<AssetClass, BigDecimal> haircuts, Metrics m,
                                                       double sigma, BigDecimal threshold) {
        AssetPosition best = null;
        BigDecimal bestAvailable = BigDecimal.ZERO;
        for (AssetPosition p : portfolio.positions()) {
            if (p.isCash() || haircuts.getOrDefault(p.assetClass(), BigDecimal.ONE).compareTo(BigDecimal.ONE) >= 0) {
                continue;
            }
            BigDecimal pledged = legs.stream().filter(l -> l.position().instrumentId().equals(p.instrumentId()))
                    .map(Leg::pledged).findFirst().orElse(BigDecimal.ZERO);
            BigDecimal available = p.marketValue().subtract(pledged);
            if (available.compareTo(PENNY) <= 0 || riskProfile.volatility(p) >= sigma) continue;
            if (best == null || riskProfile.volatility(p) < riskProfile.volatility(best)) {
                best = p;
                bestAvailable = available;
            }
        }
        if (best == null) return Optional.empty();

        List<Leg> extended = new ArrayList<>();
        boolean merged = false;
        for (Leg l : legs) {
            if (l.position().instrumentId().equals(best.instrumentId())) {
                extended.add(leg(best, l.pledged().add(bestAvailable), haircuts));
                merged = true;
            } else {
                extended.add(l);
            }
        }
        if (!merged) extended.add(leg(best, bestAvailable, haircuts));

        BigDecimal newCollateral = sumCollateral(extended);
        double newSigma = volatility(extended);
        BigDecimal newDistance = BigDecimal.ONE.subtract(
                m.loanAmount().divide(newCollateral, MC).divide(threshold, MC));
        if (newSigma >= sigma || newDistance.compareTo(m.distanceToMarginCall()) <= 0) return Optional.empty();

        return Optional.of(new Recommendation("ADD_LOW_VOLATILITY_FUND",
                "Aportar también " + eur(bestAvailable) + " de " + best.name() + " (menos volátil que el resto de la "
                        + "garantía) subiría la distancia al margin call del " + pct(m.distanceToMarginCall())
                        + " al " + pct(newDistance) + " y bajaría la volatilidad de la garantía del "
                        + pct(BigDecimal.valueOf(sigma)) + " al " + pct(BigDecimal.valueOf(newSigma)) + ".",
                figures("pledgeAmount", money(bestAvailable),
                        "distanceBefore", m.distanceToMarginCall(), "distanceAfter", frac(newDistance),
                        "volatilityBefore", BigDecimal.valueOf(sigma).setScale(4, RoundingMode.HALF_UP),
                        "volatilityAfter", BigDecimal.valueOf(newSigma).setScale(4, RoundingMode.HALF_UP))));
    }

    private List<String> assumptions(List<Leg> legs, boolean customHaircuts) {
        List<String> out = new ArrayList<>();
        List<String> genericFunds = legs.stream().filter(l -> !riskProfile.hasFundData(l.position()))
                .map(l -> l.position().name()).toList();
        out.add("Volatilidades y correlaciones por fondo: SUPUESTOS ilustrativos, no series históricas de precios "
                + "(el simulador no las tiene)."
                + (genericFunds.isEmpty() ? "" : " Sin dato propio, se usa la volatilidad de su clase: "
                + String.join(", ", genericFunds) + "."));
        out.add("Probabilidad de margin call: el valor de la garantía se modela como movimiento browniano geométrico "
                + "sin deriva, con la volatilidad ponderada de los fondos (con sus correlaciones); es la probabilidad de "
                + "tocar el umbral en algún momento del horizonte. La deuda se fija en su valor al final del horizonte "
                + "(intereses capitalizados anualmente), lo que es conservador.");
        out.add("Test de estrés: caída simultánea y uniforme de todos los fondos aportados; la deuda no cambia.");
        out.add(customHaircuts
                ? "Descuentos por clase de activo: los indicados en la petición."
                : "Descuentos por clase de activo: los del contrato de ejemplo (renta variable 30 %, renta fija 10 %, "
                + "materias primas 25 %).");
        out.add("Nivel de seguridad: BAJO si ya hay margin call, si una caída del 10 % lo provoca o si la probabilidad "
                + "a 365 días es >= 25 %; MEDIO si lo provoca una caída del 20 % o la probabilidad a 365 días es >= 10 %; "
                + "ALTO en otro caso.");
        return List.copyOf(out);
    }

    // ═════════════════════════════════════════════════════════════════════════════════
    // Utilidades
    // ═════════════════════════════════════════════════════════════════════════════════

    private static BigDecimal require(BigDecimal v, String message) {
        if (v == null) throw new IllegalArgumentException(message);
        return v;
    }

    private static BigDecimal sumCollateral(List<Leg> legs) {
        return legs.stream().map(Leg::collateral).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumPledged(List<Leg> legs) {
        return legs.stream().map(Leg::pledged).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal frac(BigDecimal v) {
        return v.setScale(4, RoundingMode.HALF_UP);
    }

    /** 12345.6 -> "12.345,60 EUR" (formato español, sin depender del locale de la JVM). */
    private static String eur(BigDecimal v) {
        return String.format(Locale.GERMANY, "%,.2f", money(v)) + " EUR";
    }

    /** 0.1442 -> "14,4 %". */
    private static String pct(BigDecimal fraction) {
        return String.format(Locale.GERMANY, "%.1f", fraction.doubleValue() * 100) + " %";
    }

    private static Map<String, BigDecimal> figures(Object... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (BigDecimal) kv[i + 1]);
        return Map.copyOf(m);
    }
}
