package com.wealth.mod2_taxoptimizer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Lote fiscal de compra de un instrumento financiero (modelo de dominio del Módulo 2).
 * <p>
 * Representa una adquisición concreta que quedará registrada como fila en la tabla
 * {@code tax_lots} (ver {@code V2__add_tax_lots_fifo.sql}). Cuando se ejecuta una venta
 * el {@link FifoCalculatorService} consume los lotes más antiguos primero (FIFO).
 * <p>
 * Convenciones de precisión:
 * <ul>
 *   <li>{@code purchasePrice} y cantidades: {@link BigDecimal} siempre. Prohibido {@code double}/{@code float}.</li>
 *   <li>{@code availableQuantity} empieza igual que {@code quantity} y solo decrece; nunca negativo.</li>
 * </ul>
 * <p>
 * <strong>Nota de arquitectura:</strong> esta clase es un POJO puro sin anotaciones JPA.
 * El mapeo a BD se realiza mediante JDBC (Spring {@code JdbcClient}) en {@link TaxLotRepository},
 * siguiendo el patrón del resto del proyecto (que usa {@code spring-boot-starter-jdbc} sin Hibernate).
 */
public final class TaxLotEntity {

    private Long id;                        // null cuando aún no está persistido
    private String portfolioId;
    private String assetIsin;
    private LocalDate purchaseDate;
    private BigDecimal purchasePrice;       // precio unitario de adquisición
    private BigDecimal quantity;            // cantidad total comprada en este lote
    private BigDecimal availableQuantity;   // cantidad pendiente de asignar a ventas
    private String currency;
    private String brokerRef;               // referencia de bróker (trazabilidad)

    /** Constructor completo para reconstrucción desde base de datos o carga del CSV. */
    public TaxLotEntity(Long id,
                        String portfolioId,
                        String assetIsin,
                        LocalDate purchaseDate,
                        BigDecimal purchasePrice,
                        BigDecimal quantity,
                        BigDecimal availableQuantity,
                        String currency,
                        String brokerRef) {
        this.id = id;
        this.portfolioId = Objects.requireNonNull(portfolioId, "portfolioId");
        this.assetIsin = Objects.requireNonNull(assetIsin, "assetIsin");
        this.purchaseDate = Objects.requireNonNull(purchaseDate, "purchaseDate");
        this.purchasePrice = Objects.requireNonNull(purchasePrice, "purchasePrice");
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        this.availableQuantity = Objects.requireNonNull(availableQuantity, "availableQuantity");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.brokerRef = brokerRef;

        validateInvariants();
    }

    /** Constructor de creación (sin id asignado todavía). availableQuantity = quantity. */
    public TaxLotEntity(String portfolioId,
                        String assetIsin,
                        LocalDate purchaseDate,
                        BigDecimal purchasePrice,
                        BigDecimal quantity,
                        String currency,
                        String brokerRef) {
        this(null, portfolioId, assetIsin, purchaseDate, purchasePrice, quantity, quantity, currency, brokerRef);
    }

    // -------------------------------------------------------------------------
    // Validaciones
    // -------------------------------------------------------------------------

    private void validateInvariants() {
        if (purchasePrice.signum() <= 0)
            throw new IllegalArgumentException("purchasePrice must be > 0: " + purchasePrice);
        if (quantity.signum() <= 0)
            throw new IllegalArgumentException("quantity must be > 0: " + quantity);
        if (availableQuantity.signum() < 0)
            throw new IllegalArgumentException("availableQuantity must be >= 0: " + availableQuantity);
        if (availableQuantity.compareTo(quantity) > 0)
            throw new IllegalArgumentException(
                    "availableQuantity (" + availableQuantity + ") cannot exceed quantity (" + quantity + ")");
    }

    // -------------------------------------------------------------------------
    // Comportamiento de dominio
    // -------------------------------------------------------------------------

    /** Indica si este lote todavía tiene unidades disponibles para ser vendidas. */
    public boolean isOpen() {
        return availableQuantity.signum() > 0;
    }

    /**
     * Consume hasta {@code requested} unidades de este lote (FIFO parcial o total).
     *
     * @param requested cantidad a consumir; debe ser > 0 y ≤ {@link #availableQuantity}.
     * @return cantidad realmente consumida (puede ser menor si el lote se agota).
     */
    public BigDecimal consume(BigDecimal requested) {
        if (requested.signum() <= 0)
            throw new IllegalArgumentException("requested must be > 0, got: " + requested);
        BigDecimal consumed = requested.min(availableQuantity);
        availableQuantity = availableQuantity.subtract(consumed);
        return consumed;
    }

    /**
     * Calcula la plusvalía o minusvalía de vender {@code soldQuantity} unidades de este lote.
     * Plusvalía (+) si el precio de venta es mayor que el de compra.
     * Minusvalía (-) en caso contrario.
     *
     * @param soldQuantity  unidades vendidas (debe ser ≤ {@code quantity}).
     * @param salePrice     precio de venta unitario.
     * @return diferencial de valor: (salePrice - purchasePrice) × soldQuantity.
     */
    public BigDecimal computeGainLoss(BigDecimal soldQuantity, BigDecimal salePrice) {
        return salePrice.subtract(purchasePrice).multiply(soldQuantity);
    }

    // -------------------------------------------------------------------------
    // Getters (sin setters: la mutación de availableQuantity se hace via consume())
    // -------------------------------------------------------------------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }   // solo para el repositorio al hacer INSERT
    public String getPortfolioId() { return portfolioId; }
    public String getAssetIsin() { return assetIsin; }
    public LocalDate getPurchaseDate() { return purchaseDate; }
    public BigDecimal getPurchasePrice() { return purchasePrice; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getAvailableQuantity() { return availableQuantity; }
    public String getCurrency() { return currency; }
    public String getBrokerRef() { return brokerRef; }

    @Override
    public String toString() {
        return "TaxLotEntity{id=" + id +
               ", isin='" + assetIsin + '\'' +
               ", date=" + purchaseDate +
               ", price=" + purchasePrice +
               ", qty=" + quantity +
               ", avail=" + availableQuantity + '}';
    }
}
