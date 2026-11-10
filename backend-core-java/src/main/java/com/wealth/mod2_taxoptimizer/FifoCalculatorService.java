package com.wealth.mod2_taxoptimizer;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.wealth.mod3_irpf.SavingsTaxScale;

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
 * <strong>Tramos IRPF ahorro (art. 66 LIRPF, escala 2025-2026):</strong> se delega en
 * {@link SavingsTaxScale} (módulo 3), que es la única fuente de tramos de la aplicación.
 * <ul>
 *   <li>Hasta 6.000 € → 19 %</li>
 *   <li>De 6.000 € a 50.000 € → 21 %</li>
 *   <li>De 50.000 € a 200.000 € → 23 %</li>
 *   <li>De 200.000 € a 300.000 € → 27 %</li>
 *   <li>Más de 300.000 € → 30 %</li>
 * </ul>
 */
@Service
public class FifoCalculatorService {

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
     * Impuesto estimado de una plusvalia aplicando la escala del ahorro vigente.
     * Delega en {@link SavingsTaxScale} (modulo 3): una unica fuente de tramos en toda la app.
     *
     * @param gainLoss plusvalia total (si es <= 0 devuelve 0).
     * @return impuesto estimado con 2 decimales.
     */
    public BigDecimal computeIrpfTax(BigDecimal gainLoss) {
        return SavingsTaxScale.COMMON_2025_2026.tax(gainLoss);
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