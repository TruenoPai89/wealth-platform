package com.wealth.mod2_taxoptimizer;

import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Repositorio de lotes fiscales del Módulo 2.
 * <p>
 * <strong>Implementación actual:</strong> almacén en memoria ({@link ConcurrentHashMap}) que
 * permite desarrollar y testear la lógica FIFO y la regla de los 2 meses sin depender de
 * PostgreSQL. Cuando el equipo active la DataSource (quitando el {@code exclude} en
 * {@code application.yml}), esta clase se sustituirá por una implementación basada en
 * {@code JdbcClient} que usa la tabla {@code tax_lots} creada en
 * {@code V2__add_tax_lots_fifo.sql}.
 * <p>
 * La clave del mapa es {@code "portfolioId|assetIsin"} para soportar múltiples carteras.
 */
@Repository
public class TaxLotRepository {

    private final Map<String, List<TaxLotEntity>> store = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);

    // -------------------------------------------------------------------------
    // Escritura
    // -------------------------------------------------------------------------

    /**
     * Persiste un lote nuevo. Asigna un id secuencial y lo inserta en el almacén.
     *
     * @param lot lote sin id (creado con el constructor de creación de {@link TaxLotEntity}).
     * @return el mismo lote con el id asignado.
     */
    public TaxLotEntity save(TaxLotEntity lot) {
        lot.setId(sequence.getAndIncrement());
        String key = key(lot.getPortfolioId(), lot.getAssetIsin());
        store.computeIfAbsent(key, k -> new ArrayList<>()).add(lot);
        return lot;
    }

    /**
     * Persiste una lista de lotes de golpe (útil para carga inicial del CSV / seed de tests).
     *
     * @param lots lotes a persistir.
     */
    public void saveAll(List<TaxLotEntity> lots) {
        lots.forEach(this::save);
    }

    // -------------------------------------------------------------------------
    // Lectura
    // -------------------------------------------------------------------------

    /**
     * Devuelve todos los lotes con {@code availableQuantity > 0} para un instrumento y cartera,
     * <strong>ordenados por {@code purchaseDate} ASC</strong> (orden FIFO estricto).
     *
     * @param portfolioId identificador de la cartera.
     * @param assetIsin   ISIN del instrumento.
     * @return lista ordenada (nunca {@code null}, puede ser vacía).
     */
    public List<TaxLotEntity> findOpenLotsFifo(String portfolioId, String assetIsin) {
        return store.getOrDefault(key(portfolioId, assetIsin), List.of())
                .stream()
                .filter(TaxLotEntity::isOpen)
                .sorted(Comparator.comparing(TaxLotEntity::getPurchaseDate))
                .collect(Collectors.toList());
    }

    /**
     * Devuelve TODOS los lotes (abiertos y cerrados) de un instrumento y cartera,
     * ordenados por fecha de compra ASC. Usado por la regla de los 2 meses.
     */
    public List<TaxLotEntity> findAllLotsByIsin(String portfolioId, String assetIsin) {
        return store.getOrDefault(key(portfolioId, assetIsin), List.of())
                .stream()
                .sorted(Comparator.comparing(TaxLotEntity::getPurchaseDate))
                .collect(Collectors.toList());
    }

    /**
     * Devuelve todas las fechas de compra de un ISIN para cualquier cartera.
     * Usado por {@link TwoMonthRuleValidator} para detectar compras recientes homogéneas.
     */
    public List<LocalDate> findAllPurchaseDatesByIsin(String assetIsin) {
        return store.entrySet().stream()
                .filter(e -> e.getKey().endsWith("|" + assetIsin))
                .flatMap(e -> e.getValue().stream())
                .map(TaxLotEntity::getPurchaseDate)
                .sorted()
                .collect(Collectors.toList());
    }

    /** Elimina todos los lotes del almacén (uso exclusivo de tests). */
    public void clear() {
        store.clear();
        sequence.set(1);
    }

    /** Número total de lotes almacenados (todos los ISINs y carteras). */
    public long count() {
        return store.values().stream().mapToLong(List::size).sum();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String key(String portfolioId, String assetIsin) {
        return portfolioId + "|" + assetIsin;
    }

    /**
     * Calcula el precio medio ponderado de adquisición de los lotes abiertos.
     * Útil para reporting; no se usa en el FIFO estricto.
     *
     * @param portfolioId identificador de la cartera.
     * @param assetIsin   ISIN del instrumento.
     * @return precio medio ponderado, o {@link BigDecimal#ZERO} si no hay lotes abiertos.
     */
    public BigDecimal computeWeightedAvgCost(String portfolioId, String assetIsin) {
        List<TaxLotEntity> open = findOpenLotsFifo(portfolioId, assetIsin);
        if (open.isEmpty()) return BigDecimal.ZERO;
        BigDecimal totalCost = open.stream()
                .map(l -> l.getPurchasePrice().multiply(l.getAvailableQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalQty = open.stream()
                .map(TaxLotEntity::getAvailableQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return totalQty.signum() == 0 ? BigDecimal.ZERO : totalCost.divide(totalQty, 6, java.math.RoundingMode.HALF_UP);
    }
}
