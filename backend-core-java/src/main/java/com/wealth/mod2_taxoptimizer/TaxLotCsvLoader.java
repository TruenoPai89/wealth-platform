package com.wealth.mod2_taxoptimizer;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Cargador de lotes fiscales desde el CSV de movimientos históricos del bróker.
 * <p>
 * Lee el archivo {@code bank-simulator-mocks/broker-csv-extracts/historical_movements.csv}
 * y lo convierte en {@link TaxLotEntity}s que se persisten en el {@link TaxLotRepository}.
 * <p>
 * Formato esperado del CSV (cabecera obligatoria):
 * <pre>
 * isin,movement_type,trade_date,settlement_date,quantity,unit_price_eur,total_amount_eur,currency,broker_ref
 * </pre>
 * Solo se procesan las filas con {@code movement_type = BUY}. Las ventas del historial
 * se ignoran porque los lotes ya están netados en {@code available_quantity}.
 * <p>
 * <strong>Nota:</strong> este componente es parte del setup de desarrollo / tests.
 * En producción, los lotes se cargarán desde la BBDD ({@code tax_lots}) a través de
 * {@link TaxLotRepository} con la implementación JDBC real.
 */
@Component
public class TaxLotCsvLoader {

    /** Cartera por defecto para los datos del mock CSV. */
    static final String DEFAULT_PORTFOLIO_ID = "PF-001";

    private static final int COL_ISIN = 0;
    private static final int COL_TYPE = 1;
    private static final int COL_TRADE_DATE = 2;
    private static final int COL_QUANTITY = 4;
    private static final int COL_UNIT_PRICE = 5;
    private static final int COL_CURRENCY = 7;
    private static final int COL_BROKER_REF = 8;

    private final TaxLotRepository repository;

    public TaxLotCsvLoader(TaxLotRepository repository) {
        this.repository = repository;
    }

    // =========================================================================
    // API pública
    // =========================================================================

    /**
     * Carga el CSV de movimientos históricos y persiste los lotes de compra.
     *
     * @param csvPath ruta al archivo CSV.
     * @param portfolioId identificador de la cartera a la que se asignan los lotes.
     * @return lista de lotes persistidos (solo compras).
     */
    public List<TaxLotEntity> loadFromCsv(Path csvPath, String portfolioId) {
        List<TaxLotEntity> loaded = parseCsv(csvPath, portfolioId);
        repository.saveAll(loaded);
        return loaded;
    }

    /**
     * Variante con la cartera por defecto ({@value DEFAULT_PORTFOLIO_ID}).
     *
     * @param csvPath ruta al archivo CSV.
     * @return lista de lotes persistidos.
     */
    public List<TaxLotEntity> loadFromCsv(Path csvPath) {
        return loadFromCsv(csvPath, DEFAULT_PORTFOLIO_ID);
    }

    /**
     * Parsea el CSV sin persistir. Útil para validaciones y tests de parsing.
     *
     * @param csvPath     ruta al archivo CSV.
     * @param portfolioId identificador de la cartera.
     * @return lotes parseados (sin id asignado).
     */
    public List<TaxLotEntity> parseCsv(Path csvPath, String portfolioId) {
        List<TaxLotEntity> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) return result; // archivo vacío

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    TaxLotEntity lot = parseLine(line, portfolioId);
                    if (lot != null) result.add(lot); // null = fila SELL (ignorada)
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Error parsing CSV line " + lineNumber + ": [" + line + "]", e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read CSV: " + csvPath, e);
        }
        return result;
    }

    // =========================================================================
    // Lógica interna
    // =========================================================================

    /**
     * Parsea una fila CSV y devuelve un {@link TaxLotEntity} si es una compra (BUY),
     * o {@code null} si es una venta (SELL) que debe ignorarse.
     */
    private TaxLotEntity parseLine(String line, String portfolioId) {
        String[] cols = line.split(",", -1);

        String movType = cols[COL_TYPE].trim().toUpperCase();
        if (!"BUY".equals(movType)) return null; // ignorar SELL del historial

        String isin = cols[COL_ISIN].trim();
        LocalDate tradeDate = LocalDate.parse(cols[COL_TRADE_DATE].trim());
        BigDecimal quantity = new BigDecimal(cols[COL_QUANTITY].trim());
        BigDecimal unitPrice = new BigDecimal(cols[COL_UNIT_PRICE].trim());
        String currency = cols[COL_CURRENCY].trim();
        String brokerRef = cols.length > COL_BROKER_REF ? cols[COL_BROKER_REF].trim() : null;

        return new TaxLotEntity(portfolioId, isin, tradeDate, unitPrice, quantity, currency, brokerRef);
    }
}
