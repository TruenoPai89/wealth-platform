package com.wealth.mod2_taxoptimizer;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Motor FIFO del Módulo 2 (Optimizador Fiscal).
 * <p>
 * Dada una orden de venta teórica (ISIN + cantidad + precio de venta), determina
 * exactamente qué lotes históricos se verían consumidos siguiendo el criterio
 * FIFO estricto (el lote más antiguo primero, que es el obligatorio en España para
 * fondos de inversión y ETFs según el art. 37.2 LIRPF).
 * <p>
 * <strong>Precisión:</strong> todos los cálculos usan {@link BigDecimal}. Prohibido
 * {@code double}/{@code float}.
 * <p>
 * <strong>Tramos IRPF ahorro (art. 66 LIRPF, vigentes desde 2023):</strong>
 * <ul>
 *   <li>Hasta 6.000 € → 19 %</li>
 *   <li>De 6.000 € a 50.000 € → 21 %</li>
 *   <li>De 50.000 € a 200.000 € → 23 %</li>
 *   <li>Más de 200.000 € → 28 %</li>
 * </ul>
 */
@Service
public class FifoCalculatorService {

    // Tramos IRPF ahorro (en euros)
    private static final BigDecimal TRAMO_1_LIMITE = new BigDecimal("6000");
    private static final BigDecimal TRAMO_2_LIMITE = new BigDecimal("50000");
    private static final BigDecimal TRAMO_3_LIMITE = new BigDecimal("200000");

    private static final BigDecimal TIPO_TRAMO_1 = new BigDecimal("0.19");
    private static final BigDecimal TIPO_TRAMO_2 = new BigDecimal("0.21");
    private static final BigDecimal TIPO_TRAMO_3 = new BigDecimal("0.23");
    private static final BigDecimal TIPO_TRAMO_4 = new BigDecimal("0.28");

    private final TaxLotRepository repository;

    public FifoCalculatorService(TaxLotRepository repository) {
        this.repository = repository;
    }

    // =========================================================================
    // API pública
    // =========================================================================

    /**
     * Resultado del análisis FIFO de una venta teórica.
     *
     * @param portfolioId          cartera sobre la que se calcula.
     * @param assetIsin            ISIN del instrumento vendido.
     * @param saleDate             fecha de la venta teórica (para la regla de los 2 meses).
     * @param quantitySold         cantidad vendida.
     * @param salePrice            precio unitario de venta.
     * @param affectedLots         snapshot de los lotes consumidos (copia; no muta los originales).
     * @param totalGainLoss        plusvalía (+) o minusvalía (-) total estimada.
     * @param estimatedTax         impuesto estimado según tramos IRPF (0 si hay minusvalía).
     * @param quantityNotCovered   cantidad que no pudo cubrirse con lotes históricos
     *                             (> 0 si se intenta vender más de lo comprado).
     */
    public record FifoResult(
            String portfolioId,
            String assetIsin,
            LocalDate saleDate,
            BigDecimal quantitySold,
            BigDecimal salePrice,
            List<LotImpact> affectedLots,
            BigDecimal totalGainLoss,
            BigDecimal estimatedTax,
            BigDecimal quantityNotCovered) {

        /** Indica si la venta tiene plusvalía (impuesto positivo). */
        public boolean hasGain() { return totalGainLoss.signum() > 0; }

        /** Indica si la venta tiene minusvalía (pérdida fiscal). */
        public boolean hasLoss() { return totalGainLoss.signum() < 0; }

        /** Indica si todos los lotes necesarios estaban cubiertos. */
        public boolean isFullyCovered() { return quantityNotCovered.signum() == 0; }
    }

    /**
     * Impacto sobre un lote concreto en el contexto de una venta FIFO.
     *
     * @param lot           lote afectado (snapshot inmutable del estado PRE-venta).
     * @param quantityUsed  cantidad extraída de este lote.
     * @param gainLoss      plusvalía/minusvalía atribuida a esta fracción del lote.
     */
    public record LotImpact(TaxLotSnapshot lot, BigDecimal quantityUsed, BigDecimal gainLoss) {}

    /**
     * Snapshot inmutable de los datos relevantes de un lote en el momento del análisis.
     * Se usa en {@link LotImpact} para no exponer la entidad mutable.
     */
    public record TaxLotSnapshot(
            Long id,
            LocalDate purchaseDate,
            BigDecimal purchasePrice,
            BigDecimal availableQuantityBefore) {}

    // =========================================================================
    // Lógica principal
    // =========================================================================

    /**
     * Calcula el impacto FIFO de una venta teórica <strong>sin modificar</strong> el
     * estado de los lotes almacenados (simulación de solo lectura).
     * <p>
     * Para aplicar la venta realmente, usa {@link #applyFifoSale}.
     *
     * @param portfolioId  identificador de la cartera.
     * @param assetIsin    ISIN del activo a vender.
     * @param saleDate     fecha de la venta teórica.
     * @param quantity     cantidad a vender.
     * @param salePrice    precio unitario de venta.
     * @return resultado del análisis; nunca {@code null}.
     */
    public FifoResult estimateSale(String portfolioId, String assetIsin,
                                   LocalDate saleDate, BigDecimal quantity,
                                   BigDecimal salePrice) {
        validate(portfolioId, assetIsin, saleDate, quantity, salePrice);

        List<TaxLotEntity> openLots = repository.findOpenLotsFifo(portfolioId, assetIsin);
        return computeFifo(portfolioId, assetIsin, saleDate, quantity, salePrice, openLots);
    }

    /**
     * Aplica una venta FIFO real: consume los lotes en el repositorio y devuelve el resultado.
     * <p>
     * <strong>Efecto secundario:</strong> modifica {@code availableQuantity} de los lotes afectados.
     *
     * @param portfolioId  identificador de la cartera.
     * @param assetIsin    ISIN del activo a vender.
     * @param saleDate     fecha de la venta.
     * @param quantity     cantidad a vender.
     * @param salePrice    precio unitario de venta.
     * @return resultado del análisis con los lotes ya consumidos.
     */
    public FifoResult applyFifoSale(String portfolioId, String assetIsin,
                                    LocalDate saleDate, BigDecimal quantity,
                                    BigDecimal salePrice) {
        validate(portfolioId, assetIsin, saleDate, quantity, salePrice);

        List<TaxLotEntity> openLots = repository.findOpenLotsFifo(portfolioId, assetIsin);
        FifoResult result = computeFifo(portfolioId, assetIsin, saleDate, quantity, salePrice, openLots);

        // Consumir la cantidad correspondiente de cada lote afectado
        BigDecimal remaining = quantity;
        for (TaxLotEntity lot : openLots) {
            if (remaining.signum() <= 0) break;
            remaining = remaining.subtract(lot.consume(remaining));
        }

        return result;
    }

    // =========================================================================
    // Lógica interna
    // =========================================================================

    private FifoResult computeFifo(String portfolioId, String assetIsin,
                                   LocalDate saleDate, BigDecimal quantity,
                                   BigDecimal salePrice, List<TaxLotEntity> openLots) {
        List<LotImpact> impacts = new ArrayList<>();
        BigDecimal remaining = quantity;
        BigDecimal totalGainLoss = BigDecimal.ZERO;

        for (TaxLotEntity lot : openLots) {
            if (remaining.signum() <= 0) break;

            BigDecimal available = lot.getAvailableQuantity();
            BigDecimal used = remaining.min(available);
            BigDecimal gainLoss = lot.computeGainLoss(used, salePrice);

            impacts.add(new LotImpact(
                    new TaxLotSnapshot(lot.getId(), lot.getPurchaseDate(),
                            lot.getPurchasePrice(), available),
                    used, gainLoss));

            totalGainLoss = totalGainLoss.add(gainLoss);
            remaining = remaining.subtract(used);
        }

        BigDecimal estimatedTax = totalGainLoss.signum() > 0
                ? computeIrpfTax(totalGainLoss)
                : BigDecimal.ZERO;

        return new FifoResult(
                portfolioId, assetIsin, saleDate,
                quantity.subtract(remaining),   // quantitySold = solicitada - no cubierta
                salePrice, List.copyOf(impacts),
                totalGainLoss.setScale(2, RoundingMode.HALF_UP),
                estimatedTax,
                remaining.max(BigDecimal.ZERO));
    }

    /**
     * Calcula el impuesto estimado aplicando los tramos de la base imponible del
     * ahorro del IRPF español (art. 66 LIRPF, en vigor desde ejercicio 2023).
     *
     * @param gainLoss plusvalía total (debe ser &gt; 0; si hay minusvalía no se llama).
     * @return impuesto estimado con 2 decimales.
     */
    public BigDecimal computeIrpfTax(BigDecimal gainLoss) {
        if (gainLoss.signum() <= 0) return BigDecimal.ZERO;

        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal base = gainLoss;

        // Tramo 1: 0 – 6.000 €  →  19 %
        if (base.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal inTramo = base.min(TRAMO_1_LIMITE);
            tax = tax.add(inTramo.multiply(TIPO_TRAMO_1));
            base = base.subtract(inTramo);
        }

        // Tramo 2: 6.000 – 50.000 €  →  21 %
        if (base.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal width = TRAMO_2_LIMITE.subtract(TRAMO_1_LIMITE);
            BigDecimal inTramo = base.min(width);
            tax = tax.add(inTramo.multiply(TIPO_TRAMO_2));
            base = base.subtract(inTramo);
        }

        // Tramo 3: 50.000 – 200.000 €  →  23 %
        if (base.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal width = TRAMO_3_LIMITE.subtract(TRAMO_2_LIMITE);
            BigDecimal inTramo = base.min(width);
            tax = tax.add(inTramo.multiply(TIPO_TRAMO_3));
            base = base.subtract(inTramo);
        }

        // Tramo 4: > 200.000 €  →  28 %
        if (base.compareTo(BigDecimal.ZERO) > 0) {
            tax = tax.add(base.multiply(TIPO_TRAMO_4));
        }

        return tax.setScale(2, RoundingMode.HALF_UP);
    }

    // =========================================================================
    // Validación de parámetros
    // =========================================================================

    private static void validate(String portfolioId, String assetIsin,
                                  LocalDate saleDate, BigDecimal quantity,
                                  BigDecimal salePrice) {
        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(assetIsin, "assetIsin");
        Objects.requireNonNull(saleDate, "saleDate");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(salePrice, "salePrice");
        if (quantity.signum() <= 0)
            throw new IllegalArgumentException("quantity must be > 0, got: " + quantity);
        if (salePrice.signum() <= 0)
            throw new IllegalArgumentException("salePrice must be > 0, got: " + salePrice);
    }
}
