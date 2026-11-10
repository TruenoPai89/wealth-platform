package com.wealth.mod2_taxoptimizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Carga al arrancar los lotes fiscales que "envia el broker" en JSON
 * ({@code bank-simulator-mocks/api-json-responses/historical_lots.json}) y los guarda en el
 * {@link TaxLotRepository}. Sin esto el repositorio arranca vacio y FIFO no tiene nada que calcular.
 * <p>
 * Formato (schemaVersion 1):
 * <pre>
 * { "source": "BROKER_MOCK", "schemaVersion": 1, "asOf": "2026-11-09", "portfolioId": "PF-001",
 *   "lots": [ { "brokerRef": "BRK-2020-051001", "isin": "IE00B579F325", "purchaseDate": "2020-05-10",
 *               "purchasePrice": 140.20, "quantity": 500, "availableQuantity": 300, "currency": "EUR" } ] }
 * </pre>
 * {@code availableQuantity} ya descuenta las ventas pasadas (FIFO); si falta, se toma {@code quantity}.
 * <p>
 * Reglas: no carga si esta desactivado o si el repositorio ya tiene lotes (no duplica);
 * si el fichero no existe avisa y sigue; si el JSON esta mal, falla al arrancar con el motivo.
 */
@Component
public class JsonTaxLotLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JsonTaxLotLoader.class);
    static final int SUPPORTED_SCHEMA = 1;

    private final TaxLotRepository repository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String mockFile;

    public JsonTaxLotLoader(TaxLotRepository repository,
                            ObjectMapper objectMapper,
                            @Value("${wealth.tax-lots.enabled:true}") boolean enabled,
                            @Value("${wealth.tax-lots.mock-file:}") String mockFile) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.mockFile = mockFile;
    }

    @Override
    public void run(ApplicationArguments args) {
        loadIfEmpty();
    }

    /** @return numero de lotes cargados (0 si no se cargo nada). */
    public int loadIfEmpty() {
        if (!enabled || mockFile == null || mockFile.isBlank()) {
            log.info("Carga de lotes fiscales desactivada (wealth.tax-lots).");
            return 0;
        }
        if (repository.count() > 0) {
            log.info("El repositorio ya tiene {} lotes; no se carga {}.", repository.count(), mockFile);
            return 0;
        }
        Path path = Path.of(mockFile);
        if (!Files.exists(path)) {
            log.warn("No existe el fichero de lotes {} (ruta absoluta: {}). El repositorio queda vacio.",
                    mockFile, path.toAbsolutePath());
            return 0;
        }
        List<TaxLotEntity> lots = parse(path);
        repository.saveAll(lots);
        log.info("Cargados {} lotes fiscales desde {}.", lots.size(), mockFile);
        return lots.size();
    }

    /** Lee y valida el JSON sin guardar nada. */
    public List<TaxLotEntity> parse(Path path) {
        JsonNode root;
        try {
            root = objectMapper.readTree(path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("No se puede leer el JSON de lotes " + path, e);
        }
        int schema = root.path("schemaVersion").asInt(-1);
        if (schema != SUPPORTED_SCHEMA) {
            throw new IllegalStateException("schemaVersion no soportado en " + path + ": " + schema);
        }
        String portfolioId = text(root, "portfolioId", "raiz");
        JsonNode lotsNode = root.path("lots");
        if (!lotsNode.isArray()) {
            throw new IllegalStateException("Falta el array 'lots' en " + path);
        }
        List<TaxLotEntity> result = new ArrayList<>();
        int i = 0;
        for (JsonNode n : lotsNode) {
            String where = "lots[" + i++ + "]";
            try {
                BigDecimal quantity = decimal(n, "quantity", where);
                BigDecimal available = n.hasNonNull("availableQuantity")
                        ? decimal(n, "availableQuantity", where) : quantity;
                result.add(new TaxLotEntity(
                        null,
                        portfolioId,
                        text(n, "isin", where),
                        LocalDate.parse(text(n, "purchaseDate", where)),
                        decimal(n, "purchasePrice", where),
                        quantity,
                        available,
                        n.hasNonNull("currency") ? n.get("currency").asText() : "EUR",
                        n.hasNonNull("brokerRef") ? n.get("brokerRef").asText() : null));
            } catch (RuntimeException e) {
                throw new IllegalStateException("Lote invalido en " + path + " " + where + ": " + e.getMessage(), e);
            }
        }
        return result;
    }

    private static String text(JsonNode n, String field, String where) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new IllegalArgumentException("falta '" + field + "' en " + where);
        }
        return v.asText().trim();
    }

    private static BigDecimal decimal(JsonNode n, String field, String where) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            throw new IllegalArgumentException("falta '" + field + "' en " + where);
        }
        // decimalValue() es exacto con use-big-decimal-for-floats (application.yml); asText cubre "140.20".
        return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asText().trim());
    }
}
