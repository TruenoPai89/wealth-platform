package com.wealth.mod3_irpf;

import com.wealth.mod2_taxoptimizer.TaxLotEntity;
import com.wealth.mod2_taxoptimizer.TaxLotRepository;
import com.wealth.mod2_taxoptimizer.TwoMonthRuleValidator;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Modulo 3: resumen fiscal del ejercicio para la declaracion (Modelo 100), solo la parte de
 * ganancias y perdidas patrimoniales por venta de fondos y ETFs.
 * <p>
 * Para cada venta del ano calcula, con FIFO estricto (art. 37.2 LIRPF), el valor de adquisicion,
 * el valor de transmision y la ganancia o perdida; aplica la regla de los 2 meses (art. 33.5 LIRPF);
 * suma el ejercicio y delega en {@link IrpfSimulationService} la compensacion con saldos de anos
 * anteriores, la base del ahorro y la cuota.
 * <p>
 * <strong>No modifica los lotes reales.</strong> Las ventas se procesan por orden de fecha sobre una
 * COPIA de los lotes: asi dos ventas del mismo fondo consumen lotes distintos (la segunda empieza
 * donde acabo la primera) y el resto de la aplicacion sigue viendo la cartera intacta.
 * <p>
 * Fuera de alcance: rendimientos del capital mobiliario (dividendos, intereses), rendimientos del
 * trabajo, deducciones, minimos personales y resultado a pagar o devolver. Las perdidas diferidas por
 * la regla de los 2 meses se excluyen del ejercicio; no se modela su integracion posterior.
 */
@Service
public class AnnualTaxReportService {

    /** Limite de operaciones por peticion (evita peticiones absurdas desde el frontend). */
    public static final int MAX_OPERATIONS = 100;

    private final TaxLotRepository lots;
    private final TwoMonthRuleValidator twoMonthRule;
    private final IrpfSimulationService irpf;

    public AnnualTaxReportService(TaxLotRepository lots, TwoMonthRuleValidator twoMonthRule,
                                  IrpfSimulationService irpf) {
        this.lots = lots;
        this.twoMonthRule = twoMonthRule;
        this.irpf = irpf;
    }

    // ---------------------------------------------------------------- Tipos

    /** Una venta realizada en el ejercicio. */
    public record SaleLine(String isin, LocalDate saleDate, BigDecimal quantity, BigDecimal salePrice) {}

    /**
     * Resultado fiscal de una venta.
     *
     * @param quantityCovered       participaciones cubiertas por lotes (puede ser menor que quantity).
     * @param transmissionValue     participaciones cubiertas x precio de venta.
     * @param acquisitionValue      suma de (participaciones de cada lote x su precio de compra).
     * @param gainLoss              transmision - adquisicion (+ ganancia, - perdida).
     * @param firstAcquisitionDate  fecha del lote mas antiguo usado (null si no hay lotes).
     * @param lastAcquisitionDate   fecha del lote mas reciente usado (null si no hay lotes).
     * @param twoMonthRuleViolation la perdida queda diferida por la regla de los 2 meses.
     * @param computableGainLoss    lo que entra en el ejercicio (0 si la perdida se difiere).
     */
    public record OperationResult(String isin, LocalDate saleDate, BigDecimal quantity,
                                  BigDecimal quantityCovered, BigDecimal salePrice,
                                  BigDecimal transmissionValue, BigDecimal acquisitionValue,
                                  BigDecimal gainLoss, LocalDate firstAcquisitionDate,
                                  LocalDate lastAcquisitionDate, int lotsUsed,
                                  boolean twoMonthRuleViolation, BigDecimal computableGainLoss,
                                  String warning) {}

    /**
     * Resumen del ejercicio.
     *
     * @param totalGains     suma de ganancias computables.
     * @param totalLosses    suma de perdidas computables (valor positivo).
     * @param deferredLosses perdidas excluidas por la regla de los 2 meses (valor positivo).
     * @param result         saldo neto, compensacion, base del ahorro y cuota (IrpfSimulationService).
     */
    public record Report(String portfolioId, int fiscalYear, List<OperationResult> operations,
                         BigDecimal totalTransmissionValue, BigDecimal totalAcquisitionValue,
                         BigDecimal totalGains, BigDecimal totalLosses, BigDecimal deferredLosses,
                         IrpfSimulationService.Result result) {}

    // ---------------------------------------------------------------- Logica

    public Report build(String portfolioId, int fiscalYear, List<SaleLine> sales,
                        List<LossCarryForwardBucket> carryForward) {
        validate(portfolioId, fiscalYear, sales);

        // Por orden de fecha; con la misma fecha se respeta el orden de entrada (sort estable).
        List<SaleLine> ordered = sales.stream()
                .sorted(Comparator.comparing(SaleLine::saleDate))
                .toList();

        Map<String, List<TaxLotEntity>> working = new HashMap<>();
        List<OperationResult> operations = new ArrayList<>();
        BigDecimal transmissionTotal = BigDecimal.ZERO;
        BigDecimal acquisitionTotal = BigDecimal.ZERO;
        BigDecimal gains = BigDecimal.ZERO;
        BigDecimal losses = BigDecimal.ZERO;
        BigDecimal deferred = BigDecimal.ZERO;

        for (SaleLine sale : ordered) {
            List<TaxLotEntity> pool = working.computeIfAbsent(sale.isin(),
                    isin -> copyOfOpenLots(portfolioId, isin));

            BigDecimal remaining = sale.quantity();
            BigDecimal acquisition = BigDecimal.ZERO;
            LocalDate first = null;
            LocalDate last = null;
            int used = 0;

            for (TaxLotEntity lot : pool) {
                if (remaining.signum() <= 0) break;
                if (!lot.isOpen() || lot.getPurchaseDate().isAfter(sale.saleDate())) continue;
                BigDecimal take = lot.consume(remaining); // consume() nunca toma mas de lo disponible
                acquisition = acquisition.add(take.multiply(lot.getPurchasePrice()));
                if (first == null) first = lot.getPurchaseDate();
                last = lot.getPurchaseDate();
                used++;
                remaining = remaining.subtract(take);
            }

            BigDecimal covered = sale.quantity().subtract(remaining);
            BigDecimal transmission = covered.multiply(sale.salePrice());
            BigDecimal gainLoss = money(transmission.subtract(acquisition));

            boolean ruleApplies = gainLoss.signum() < 0
                    && twoMonthRule.validate(sale.isin(), sale.saleDate(), gainLoss).violated();
            BigDecimal computable = ruleApplies ? money(BigDecimal.ZERO) : gainLoss;

            String warning = null;
            if (ruleApplies) {
                warning = "Perdida diferida por la regla de los 2 meses: no computa en este ejercicio.";
            } else if (remaining.signum() > 0) {
                warning = "No hay lotes suficientes anteriores a la venta: solo se calcula la parte cubierta.";
            }

            operations.add(new OperationResult(sale.isin(), sale.saleDate(), sale.quantity(), covered,
                    sale.salePrice(), money(transmission), money(acquisition), gainLoss, first, last, used,
                    ruleApplies, computable, warning));

            transmissionTotal = transmissionTotal.add(transmission);
            acquisitionTotal = acquisitionTotal.add(acquisition);
            if (computable.signum() > 0) gains = gains.add(computable);
            if (computable.signum() < 0) losses = losses.add(computable.negate());
            if (ruleApplies) deferred = deferred.add(gainLoss.negate());
        }

        IrpfSimulationService.Result result = irpf.simulate(
                new IrpfSimulationService.Input(fiscalYear, gains, losses, carryForward));

        return new Report(portfolioId, fiscalYear, List.copyOf(operations),
                money(transmissionTotal), money(acquisitionTotal),
                money(gains), money(losses), money(deferred), result);
    }

    // ---------------------------------------------------------------- Helpers

    /** Copia de los lotes abiertos (ordenados FIFO): las ventas del resumen consumen la copia. */
    private List<TaxLotEntity> copyOfOpenLots(String portfolioId, String isin) {
        return lots.findOpenLotsFifo(portfolioId, isin).stream()
                .map(l -> new TaxLotEntity(l.getId(), l.getPortfolioId(), l.getAssetIsin(),
                        l.getPurchaseDate(), l.getPurchasePrice(), l.getQuantity(),
                        l.getAvailableQuantity(), l.getCurrency(), l.getBrokerRef()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static void validate(String portfolioId, int fiscalYear, List<SaleLine> sales) {
        if (portfolioId == null || portfolioId.isBlank())
            throw new IllegalArgumentException("portfolioId es obligatorio");
        if (fiscalYear < 2000 || fiscalYear > 2100)
            throw new IllegalArgumentException("fiscalYear fuera de rango: " + fiscalYear);
        if (sales == null || sales.isEmpty())
            throw new IllegalArgumentException("Hace falta al menos una venta");
        if (sales.size() > MAX_OPERATIONS)
            throw new IllegalArgumentException("Maximo " + MAX_OPERATIONS + " ventas por resumen");
        for (SaleLine s : sales) {
            if (s == null || s.isin() == null || s.isin().isBlank())
                throw new IllegalArgumentException("Cada venta necesita un isin");
            if (s.saleDate() == null)
                throw new IllegalArgumentException("Cada venta necesita fecha (" + s.isin() + ")");
            if (s.saleDate().getYear() != fiscalYear)
                throw new IllegalArgumentException("La venta de " + s.isin() + " del " + s.saleDate()
                        + " no es del ejercicio " + fiscalYear);
            if (s.quantity() == null || s.quantity().signum() <= 0)
                throw new IllegalArgumentException("quantity debe ser > 0 (" + s.isin() + ")");
            if (s.salePrice() == null || s.salePrice().signum() <= 0)
                throw new IllegalArgumentException("salePrice debe ser > 0 (" + s.isin() + ")");
        }
    }
}
