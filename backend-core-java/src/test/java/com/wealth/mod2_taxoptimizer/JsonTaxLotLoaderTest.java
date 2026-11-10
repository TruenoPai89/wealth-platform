package com.wealth.mod2_taxoptimizer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Los tests se ejecutan desde backend-core-java/, igual que FifoCalculatorServiceTest. */
class JsonTaxLotLoaderTest {

    private static final String MOCK = "../bank-simulator-mocks/api-json-responses/historical_lots.json";
    private static final Path CSV =
            Path.of("../bank-simulator-mocks/broker-csv-extracts/historical_movements.csv");

    /** Igual que Spring con application.yml: decimales del JSON como BigDecimal. */
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private JsonTaxLotLoader loader(TaxLotRepository repo, boolean enabled, String file) {
        return new JsonTaxLotLoader(repo, mapper, enabled, file);
    }

    @Test
    void realMockHas16LotsWithPastSalesNetted() {
        List<TaxLotEntity> lots = loader(new TaxLotRepository(), true, MOCK).parse(Path.of(MOCK));
        assertThat(lots).hasSize(16);
        TaxLotEntity gold2020 = lots.stream()
                .filter(l -> "BRK-2020-051001".equals(l.getBrokerRef())).findFirst().orElseThrow();
        assertThat(gold2020.getQuantity()).isEqualByComparingTo("500");
        assertThat(gold2020.getAvailableQuantity()).isEqualByComparingTo("300");   // venta de 200 en 2022
        assertThat(gold2020.getPurchasePrice()).isEqualByComparingTo("140.20");
    }

    @Test
    void jsonMatchesTheBuysOfTheCsv() {
        List<TaxLotEntity> json = loader(new TaxLotRepository(), true, MOCK).parse(Path.of(MOCK));
        List<TaxLotEntity> csv = new TaxLotCsvLoader(new TaxLotRepository()).parseCsv(CSV, "PF-001");
        assertThat(json).hasSize(csv.size());
        for (int i = 0; i < csv.size(); i++) {
            assertThat(json.get(i).getAssetIsin()).isEqualTo(csv.get(i).getAssetIsin());
            assertThat(json.get(i).getPurchaseDate()).isEqualTo(csv.get(i).getPurchaseDate());
            assertThat(json.get(i).getPurchasePrice()).isEqualByComparingTo(csv.get(i).getPurchasePrice().toPlainString());
            assertThat(json.get(i).getQuantity()).isEqualByComparingTo(csv.get(i).getQuantity().toPlainString());
        }
    }

    @Test
    void loadsOnceAndDoesNotDuplicate() {
        TaxLotRepository repo = new TaxLotRepository();
        JsonTaxLotLoader l = loader(repo, true, MOCK);
        assertThat(l.loadIfEmpty()).isEqualTo(16);
        assertThat(l.loadIfEmpty()).isEqualTo(0);
        assertThat(repo.count()).isEqualTo(16L);
    }

    @Test
    void fifoSeesTheLoadedLotsOldestFirst() {
        TaxLotRepository repo = new TaxLotRepository();
        loader(repo, true, MOCK).loadIfEmpty();
        List<TaxLotEntity> gold = repo.findOpenLotsFifo("PF-001", "IE00B579F325");
        assertThat(gold).hasSize(3);
        assertThat(gold.get(0).getPurchaseDate()).isEqualTo(LocalDate.of(2020, 5, 10));
    }

    @Test
    void disabledLoadsNothing() {
        TaxLotRepository repo = new TaxLotRepository();
        assertThat(loader(repo, false, MOCK).loadIfEmpty()).isEqualTo(0);
        assertThat(repo.count()).isEqualTo(0L);
    }

    @Test
    void missingFileDoesNotBreakStartup() {
        TaxLotRepository repo = new TaxLotRepository();
        assertThat(loader(repo, true, "no/existe.json").loadIfEmpty()).isEqualTo(0);
        assertThat(repo.count()).isEqualTo(0L);
    }

    @Test
    void availableQuantityDefaultsToQuantity(@TempDir Path dir) throws IOException {
        Path f = write(dir, "{\"schemaVersion\":1,\"portfolioId\":\"PF-X\",\"lots\":[{\"isin\":\"ISIN1\","
                + "\"purchaseDate\":\"2024-01-15\",\"purchasePrice\":\"10.50\",\"quantity\":7}]}");
        List<TaxLotEntity> lots = loader(new TaxLotRepository(), true, f.toString()).parse(f);
        assertThat(lots).hasSize(1);
        assertThat(lots.get(0).getAvailableQuantity()).isEqualByComparingTo("7");
        assertThat(lots.get(0).getCurrency()).isEqualTo("EUR");
        assertThat(lots.get(0).getPortfolioId()).isEqualTo("PF-X");
    }

    @Test
    void wrongSchemaVersionFails(@TempDir Path dir) throws IOException {
        Path f = write(dir, "{\"schemaVersion\":2,\"portfolioId\":\"PF-X\",\"lots\":[]}");
        JsonTaxLotLoader l = loader(new TaxLotRepository(), true, f.toString());
        assertThatThrownBy(() -> l.parse(f)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void availableAboveQuantityFailsAndSaysWhere(@TempDir Path dir) throws IOException {
        Path f = write(dir, "{\"schemaVersion\":1,\"portfolioId\":\"PF-X\",\"lots\":[{\"isin\":\"ISIN1\","
                + "\"purchaseDate\":\"2024-01-15\",\"purchasePrice\":10,\"quantity\":5,\"availableQuantity\":6}]}");
        JsonTaxLotLoader l = loader(new TaxLotRepository(), true, f.toString());
        assertThatThrownBy(() -> l.parse(f))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lots[0]");
    }

    private static Path write(Path dir, String json) throws IOException {
        Path f = dir.resolve("lots.json");
        Files.writeString(f, json);
        return f;
    }
}
