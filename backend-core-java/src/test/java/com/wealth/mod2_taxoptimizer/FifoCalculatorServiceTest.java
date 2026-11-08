package com.wealth.mod2_taxoptimizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests unitarios del motor FIFO (Módulo 2 — Optimizador Fiscal).
 *
 * <p>Estrategia de test:
 * <ul>
 *   <li>No usa Spring ni base de datos: todo in-memory con {@link TaxLotRepository}.</li>
 *   <li>El CSV de movimientos históricos se carga desde el mock del bróker para el
 *       escenario de integración CSV→FIFO.</li>
 *   <li>Todos los cálculos numéricos se verifican con {@link BigDecimal#compareTo}
 *       (semántica de valor, no de escala) vía {@code isEqualByComparingTo}.</li>
 * </ul>
 */
class FifoCalculatorServiceTest {

    // ISINs del mock CSV / portfolio PF-001
    private static final String MSCI_WORLD   = "IE00B4L5Y983";
    private static final String SP500        = "IE00B3XXRP09";
    private static final String EURO_BOND    = "IE00B4WXJJ64";
    private static final String GOLD         = "IE00B579F325";
    private static final String MSCI_MIN_VOL = "LU0323578657";
    private static final String UK_GILTS     = "IE00B3F81R35";

    private static final String PORTFOLIO    = "PF-001";

    /** Ruta del CSV relativa al working-dir de Maven (backend-core-java/). */
    private static final Path CSV_PATH = Path.of(
            "../bank-simulator-mocks/broker-csv-extracts/historical_movements.csv");

    private TaxLotRepository   repository;
    private FifoCalculatorService fifo;
    private TaxLotCsvLoader    csvLoader;

    @BeforeEach
    void setUp() {
        repository = new TaxLotRepository();
        fifo       = new FifoCalculatorService(repository);
        csvLoader  = new TaxLotCsvLoader(repository);
    }

    // =========================================================================
    // 1. CARGA DEL CSV
    // =========================================================================

    @Nested
    @DisplayName("1. Carga del CSV de movimientos históricos")
    class CsvLoadingTests {

        @Test
        @DisplayName("El CSV se parsea correctamente: solo se cargan las filas BUY")
        void csvLoadsOnlyBuyRows() {
            List<TaxLotEntity> lots = csvLoader.loadFromCsv(CSV_PATH);

            // El CSV tiene 18 filas de datos: 15 BUY + 3 SELL (ignorados)
            // ISINs con BUY: MSCI(4) + SP500(3) + EuroBond(3) + Gold(3) + MSCIMinVol(2) + UKGilts(1) = 16
            assertThat(lots).hasSize(16);
        }

        @Test
        @DisplayName("Los lotes se asignan al portfolioId correcto")
        void lotsAssignedToCorrectPortfolio() {
            csvLoader.loadFromCsv(CSV_PATH);
            List<TaxLotEntity> msciLots = repository.findOpenLotsFifo(PORTFOLIO, MSCI_WORLD);
            assertThat(msciLots).isNotEmpty();
            assertThat(msciLots).allMatch(l -> PORTFOLIO.equals(l.getPortfolioId()));
        }

        @Test
        @DisplayName("Los lotes de IE00B4L5Y983 (MSCI World) se cargan en orden FIFO (fecha ASC)")
        void msciLotsOrderedByDateAsc() {
            csvLoader.loadFromCsv(CSV_PATH);
            List<TaxLotEntity> lots = repository.findOpenLotsFifo(PORTFOLIO, MSCI_WORLD);

            // 4 compras en el CSV: 2022-11-10, 2023-06-15, 2024-04-20, 2025-01-30
            assertThat(lots).hasSize(4);
            assertThat(lots.get(0).getPurchaseDate()).isEqualTo(LocalDate.of(2022, 11, 10));
            assertThat(lots.get(1).getPurchaseDate()).isEqualTo(LocalDate.of(2023,  6, 15));
            assertThat(lots.get(2).getPurchaseDate()).isEqualTo(LocalDate.of(2024,  4, 20));
            assertThat(lots.get(3).getPurchaseDate()).isEqualTo(LocalDate.of(2025,  1, 30));
        }

        @Test
        @DisplayName("Los precios de compra son BigDecimal sin pérdida de precisión")
        void purchasePricesAreBigDecimal() {
            csvLoader.loadFromCsv(CSV_PATH);
            List<TaxLotEntity> lots = repository.findOpenLotsFifo(PORTFOLIO, MSCI_WORLD);

            // Primer lote: precio 61.20 exacto
            assertThat(lots.get(0).getPurchasePrice()).isEqualByComparingTo("61.20");
            // Tercer lote: precio 88.30 exacto
            assertThat(lots.get(2).getPurchasePrice()).isEqualByComparingTo("88.30");
        }

        @Test
        @DisplayName("IE00B3XXRP09 (S&P 500): 3 BUY cargadas (hay 1 SELL ignorada en el CSV)")
        void sp500HasThreeBuyLots() {
            csvLoader.loadFromCsv(CSV_PATH);
            List<TaxLotEntity> lots = repository.findOpenLotsFifo(PORTFOLIO, SP500);
            assertThat(lots).hasSize(3);
        }

        @Test
        @DisplayName("IE00B579F325 (Oro): 3 BUY cargadas (hay 1 SELL ignorada en el CSV)")
        void goldHasThreeBuyLots() {
            csvLoader.loadFromCsv(CSV_PATH);
            List<TaxLotEntity> lots = repository.findOpenLotsFifo(PORTFOLIO, GOLD);
            assertThat(lots).hasSize(3);
        }
    }

    // =========================================================================
    // 2. ALGORITMO FIFO — ESCENARIO COMPLETO CON CSV
    // =========================================================================

    @Nested
    @DisplayName("2. Algoritmo FIFO con datos del CSV")
    class FifoWithCsvTests {

        @BeforeEach
        void loadCsv() {
            csvLoader.loadFromCsv(CSV_PATH);
        }

        @Test
        @DisplayName("Venta que consume exactamente el primer lote (MSCI World 2000 uds)")
        void saleConsumesExactlyFirstLot() {
            // Lote 1: 2000 uds a 61.20 €. Vendemos 2000 uds a 97.65 €.
            // Plusvalía = (97.65 - 61.20) × 2000 = 36.45 × 2000 = 72.900 €
            BigDecimal qty   = new BigDecimal("2000");
            BigDecimal price = new BigDecimal("97.65");

            FifoCalculatorService.FifoResult result = fifo.estimateSale(
                    PORTFOLIO, MSCI_WORLD, LocalDate.of(2026, 11, 8), qty, price);

            assertThat(result.affectedLots()).hasSize(1);
            assertThat(result.affectedLots().get(0).quantityUsed()).isEqualByComparingTo("2000");
            assertThat(result.affectedLots().get(0).lot().purchaseDate())
                    .isEqualTo(LocalDate.of(2022, 11, 10));
            assertThat(result.totalGainLoss()).isEqualByComparingTo("72900.00");
            assertThat(result.quantityNotCovered()).isEqualByComparingTo("0");
            assertThat(result.isFullyCovered()).isTrue();
        }

        @Test
        @DisplayName("Venta que consume parcialmente el primer lote (MSCI World 500 uds)")
        void saleConsumesPartialFirstLot() {
            // Lote 1: 2000 uds a 61.20 €. Vendemos solo 500 uds a 97.65 €.
            // Plusvalía = (97.65 - 61.20) × 500 = 36.45 × 500 = 18.225 €
            BigDecimal qty   = new BigDecimal("500");
            BigDecimal price = new BigDecimal("97.65");

            FifoCalculatorService.FifoResult result = fifo.estimateSale(
                    PORTFOLIO, MSCI_WORLD, LocalDate.of(2026, 11, 8), qty, price);

            assertThat(result.affectedLots()).hasSize(1);
            assertThat(result.affectedLots().get(0).quantityUsed()).isEqualByComparingTo("500");
            assertThat(result.totalGainLoss()).isEqualByComparingTo("18225.00");
            assertThat(result.quantityNotCovered()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Venta que atraviesa dos lotes FIFO (MSCI World 2500 uds)")
        void saleCrossesTwoLots() {
            // Lote 1: 2000 uds a 61.20 → usa 2000, ganancia = 36.45 × 2000 = 72.900 €
            // Lote 2: 3000 uds a 74.85 → usa  500, ganancia = 22.80 × 500  = 11.400 €
            // Total PV: 84.300 €
            BigDecimal qty   = new BigDecimal("2500");
            BigDecimal price = new BigDecimal("97.65");

            FifoCalculatorService.FifoResult result = fifo.estimateSale(
                    PORTFOLIO, MSCI_WORLD, LocalDate.of(2026, 11, 8), qty, price);

            assertThat(result.affectedLots()).hasSize(2);
            assertThat(result.affectedLots().get(0).quantityUsed()).isEqualByComparingTo("2000");
            assertThat(result.affectedLots().get(1).quantityUsed()).isEqualByComparingTo("500");
            assertThat(result.totalGainLoss()).isEqualByComparingTo("84300.00");
            assertThat(result.quantityNotCovered()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("Venta que atraviesa los 4 lotes de MSCI World (8200 uds = total disponible)")
        void saleConsumesAllFourLots() {
            // Lote 1: 2000 × (97.65 - 61.20) = 2000 × 36.45 =  72.900
            // Lote 2: 3000 × (97.65 - 74.85) = 3000 × 22.80 =  68.400
            // Lote 3: 2000 × (97.65 - 88.30) = 2000 ×  9.35 =  18.700
            // Lote 4: 1200 × (97.65 - 91.50) = 1200 ×  6.15 =   7.380
            // Total plusvalía: 167.380 €
            BigDecimal qty   = new BigDecimal("8200");
            BigDecimal price = new BigDecimal("97.65");

            FifoCalculatorService.FifoResult result = fifo.estimateSale(
                    PORTFOLIO, MSCI_WORLD, LocalDate.of(2026, 11, 8), qty, price);

            assertThat(result.affectedLots()).hasSize(4);
            assertThat(result.totalGainLoss()).isEqualByComparingTo("167380.00");
            assertThat(result.quantityNotCovered()).isEqualByComparingTo("0");
            assertThat(result.hasGain()).isTrue();
            assertThat(result.hasLoss()).isFalse();
        }

        @Test
        @DisplayName("Venta mayor que el disponible → quantityNotCovered > 0")
        void saleExceedsAvailable() {
            // Solo hay 8200 uds de MSCI World; pedimos vender 10000
            BigDecimal qty   = new BigDecimal("10000");
            BigDecimal price = new BigDecimal("97.65");

            FifoCalculatorService.FifoResult result = fifo.estimateSale(
                    PORTFOLIO, MSCI_WORLD, LocalDate.of(2026, 11, 8), qty, price);

            assertThat(result.quantityNotCovered()).isEqualByComparingTo("1800");
            assertThat(result.isFullyCovered()).isFalse();
            assertThat(result.affectedLots()).hasSize(4); // consume todos los lotes
        }

        @Test
        @DisplayName("estimateSale NO muta los lotes (es una simulación de solo lectura)")
        void estimateSaleDoesNotMutateLots() {
            BigDecimal qty   = new BigDecimal("3000");
            BigDecimal price = new BigDecimal("97.65");

            // Guardamos el estado disponible antes de la estimación
            List<TaxLotEntity> before = repository.findOpenLotsFifo(PORTFOLIO, MSCI_WORLD);
            BigDecimal availBefore = before.get(0).getAvailableQuantity();

            fifo.estimateSale(PORTFOLIO, MSCI_WORLD, LocalDate.of(2026, 11, 8), qty, price);

            // El lote no debe haber cambiado
            List<TaxLotEntity> after = repository.findOpenLotsFifo(PORTFOLIO, MSCI_WORLD);
            assertThat(after.get(0).getAvailableQuantity()).isEqualByComparingTo(availBefore);
        }

        @Test
        @DisplayName("applyFifoSale SÍ muta los lotes (consume la cantidad vendida)")
        void applyFifoSaleMutatesLots() {
            BigDecimal qty   = new BigDecimal("2500");
            BigDecimal price = new BigDecimal("97.65");

            fifo.applyFifoSale(PORTFOLIO, MSCI_WORLD, LocalDate.of(2026, 11, 8), qty, price);

            List<TaxLotEntity> after = repository.findOpenLotsFifo(PORTFOLIO, MSCI_WORLD);
            // Lote 1 (2000 uds) debe quedar agotado → no aparece en findOpenLotsFifo
            // Lote 2 (3000 uds) debe quedar con 2500 uds disponibles
            assertThat(after.get(0).getPurchaseDate()).isEqualTo(LocalDate.of(2023, 6, 15));
            assertThat(after.get(0).getAvailableQuantity()).isEqualByComparingTo("2500");
        }

        @Test
        @DisplayName("Instrumento sin historial → resultado vacío y sin impuesto")
        void noHistoryReturnsZeroResult() {
            FifoCalculatorService.FifoResult result = fifo.estimateSale(
                    PORTFOLIO, "ISIN_UNKNOWN_XYZ", LocalDate.of(2026, 11, 8),
                    new BigDecimal("100"), new BigDecimal("50.00"));

            assertThat(result.affectedLots()).isEmpty();
            assertThat(result.totalGainLoss()).isEqualByComparingTo("0");
            assertThat(result.estimatedTax()).isEqualByComparingTo("0");
            assertThat(result.quantityNotCovered()).isEqualByComparingTo("100");
        }
    }

    // =========================================================================
    // 3. CÁLCULO DE TRAMOS IRPF
    // =========================================================================

    @Nested
    @DisplayName("3. Tramos IRPF ahorro (art. 66 LIRPF)")
    class IrpfTaxBracketsTests {

        @Test
        @DisplayName("Plusvalía 0 € → impuesto 0 €")
        void zeroPlusvalia() {
            assertThat(fifo.computeIrpfTax(BigDecimal.ZERO)).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("Plusvalía negativa → impuesto 0 € (minusvalía no tributa)")
        void negativePlusvalia() {
            assertThat(fifo.computeIrpfTax(new BigDecimal("-500")))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("Tramo 1 completo: 6.000 € × 19 % = 1.140 €")
        void tramo1Complete() {
            // 6.000 × 0.19 = 1.140,00 €
            assertThat(fifo.computeIrpfTax(new BigDecimal("6000")))
                    .isEqualByComparingTo("1140.00");
        }

        @Test
        @DisplayName("Tramo 1 parcial: 3.000 € × 19 % = 570 €")
        void tramo1Partial() {
            assertThat(fifo.computeIrpfTax(new BigDecimal("3000")))
                    .isEqualByComparingTo("570.00");
        }

        @Test
        @DisplayName("Tramo 2: 20.000 € → 1.140 (T1) + 2.940 (T2) = 4.080 €")
        void tramo2() {
            // T1: 6.000 × 19% = 1.140
            // T2: 14.000 × 21% = 2.940
            // Total: 4.080 €
            assertThat(fifo.computeIrpfTax(new BigDecimal("20000")))
                    .isEqualByComparingTo("4080.00");
        }

        @Test
        @DisplayName("Tramo 2 límite: 50.000 € → 1.140 + 9.240 = 10.380 €")
        void tramo2Limit() {
            // T1: 6.000 × 19% = 1.140
            // T2: 44.000 × 21% = 9.240
            assertThat(fifo.computeIrpfTax(new BigDecimal("50000")))
                    .isEqualByComparingTo("10380.00");
        }

        @Test
        @DisplayName("Tramo 3: 100.000 € → 1.140 + 9.240 + 11.500 = 21.880 €")
        void tramo3() {
            // T1: 6.000  × 19% =  1.140
            // T2: 44.000 × 21% =  9.240
            // T3: 50.000 × 23% = 11.500
            assertThat(fifo.computeIrpfTax(new BigDecimal("100000")))
                    .isEqualByComparingTo("21880.00");
        }

        @Test
        @DisplayName("Tramo 4: 300.000 € → 1.140 + 9.240 + 34.500 + 28.000 = 72.880 €")
        void tramo4() {
            // T1:   6.000 × 19% =  1.140
            // T2:  44.000 × 21% =  9.240
            // T3: 150.000 × 23% = 34.500
            // T4: 100.000 × 28% = 28.000
            assertThat(fifo.computeIrpfTax(new BigDecimal("300000")))
                    .isEqualByComparingTo("72880.00");
        }

        @Test
        @DisplayName("El impuesto se calcula correctamente sobre la plusvalía del CSV (MSCI World 8200 uds)")
        void irpfOnMsciFullSale() {
            csvLoader.loadFromCsv(CSV_PATH);

            FifoCalculatorService.FifoResult result = fifo.estimateSale(
                    PORTFOLIO, MSCI_WORLD, LocalDate.of(2026, 11, 8),
                    new BigDecimal("8200"), new BigDecimal("97.65"));

            // Plusvalía total: 167.380 €
            // T1:   6.000 × 19% =  1.140,00
            // T2:  44.000 × 21% =  9.240,00
            // T3: 117.380 × 23% = 26.997,40
            // Total impuesto: 37.377,40 €
            assertThat(result.totalGainLoss()).isEqualByComparingTo("167380.00");
            assertThat(result.estimatedTax()).isEqualByComparingTo("37377.40");
        }
    }

    // =========================================================================
    // 4. REGLA DE LOS 2 MESES (AEAT)
    // =========================================================================

    @Nested
    @DisplayName("4. Regla de los 2 meses (art. 33.5 LIRPF)")
    class TwoMonthRuleTests {

        private TwoMonthRuleValidator validator;

        @BeforeEach
        void setUp() {
            validator = new TwoMonthRuleValidator(repository);
            csvLoader.loadFromCsv(CSV_PATH);
        }

        @Test
        @DisplayName("Plusvalía: la regla no aplica (nunca hay violación con ganancias)")
        void gainDoesNotTriggerRule() {
            TwoMonthRuleValidator.ValidationResult result = validator.validate(
                    MSCI_WORLD,
                    LocalDate.of(2026, 11, 8),
                    new BigDecimal("5000.00"));  // plusvalía

            assertThat(result.violated()).isFalse();
            assertThat(result.conflictingDates()).isEmpty();
        }

        @Test
        @DisplayName("Minusvalía sin compras en ventana ±60 días → sin violación")
        void lossWithNoNearbyBuy() {
            // Venta hipotética el 2021-01-01, minusvalía -1000 €.
            // La compra de SP500 más cercana es 2021-03-05 (63 días después → fuera de los 60)
            TwoMonthRuleValidator.ValidationResult result = validator.validate(
                    SP500,
                    LocalDate.of(2021, 1, 1),
                    new BigDecimal("-1000.00"));

            assertThat(result.violated()).isFalse();
        }

        @Test
        @DisplayName("Minusvalía con compra dentro de la ventana de 60 días → violación detectada")
        void lossWithBuyInsideWindow() {
            // UK Gilts: comprada el 2026-09-12. Si vendemos el 2026-11-08 con minusvalía,
            // la compra está a 57 días antes → dentro de la ventana de 60 días.
            TwoMonthRuleValidator.ValidationResult result = validator.validate(
                    UK_GILTS,
                    LocalDate.of(2026, 11, 8),
                    new BigDecimal("-800.00"));

            assertThat(result.violated()).isTrue();
            assertThat(result.conflictingDates()).contains(LocalDate.of(2026, 9, 12));
            assertThat(result.warning()).contains("REGLA 2 MESES");
            assertThat(result.warning()).contains(UK_GILTS);
        }

        @Test
        @DisplayName("Minusvalía con compra en el límite exacto de 60 días → dentro de la ventana")
        void lossWithBuyAtExactBoundary() {
            // Fecha de venta: 2026-11-08. Límite inferior = 2026-09-09 (60 días antes).
            // La compra de UK Gilts (2026-09-12) está a 57 días antes → DENTRO.
            LocalDate saleDate = LocalDate.of(2026, 11, 8);
            LocalDate boundaryBuy = saleDate.minusDays(60); // 2026-09-09

            // Añadimos un lote exactamente en el límite
            repository.save(new TaxLotEntity(PORTFOLIO, UK_GILTS, boundaryBuy,
                    new BigDecimal("9.50"), new BigDecimal("100"), "EUR", "TEST-BOUNDARY"));

            TwoMonthRuleValidator.ValidationResult result = validator.validate(
                    UK_GILTS, saleDate, new BigDecimal("-200.00"));

            assertThat(result.violated()).isTrue();
            assertThat(result.conflictingDates()).contains(boundaryBuy);
        }

        @Test
        @DisplayName("Minusvalía con compra en día 61 → fuera de la ventana → sin violación")
        void lossWithBuyJustOutsideWindow() {
            LocalDate saleDate   = LocalDate.of(2026, 11, 8);
            LocalDate outsideBuy = saleDate.minusDays(61); // un día fuera del límite

            repository.save(new TaxLotEntity(PORTFOLIO, EURO_BOND, outsideBuy,
                    new BigDecimal("100.00"), new BigDecimal("50"), "EUR", "TEST-OUTSIDE"));

            TwoMonthRuleValidator.ValidationResult result = validator.validate(
                    EURO_BOND, saleDate, new BigDecimal("-300.00"));

            // La compra de EURO_BOND más cercana al CSV (2025-07-14) está a >> 60 días de 2026-11-08
            // y el lote añadido (día -61) también está fuera
            assertThat(result.violated()).isFalse();
        }

        @Test
        @DisplayName("validate(FifoResult) delega correctamente en validate(isin, date, gainLoss)")
        void validateFifoResultDelegate() {
            FifoCalculatorService.FifoResult fakeResult = new FifoCalculatorService.FifoResult(
                    PORTFOLIO, UK_GILTS, LocalDate.of(2026, 11, 8),
                    new BigDecimal("500"), new BigDecimal("9.50"),
                    List.of(), new BigDecimal("-250.00"),
                    BigDecimal.ZERO, BigDecimal.ZERO);

            TwoMonthRuleValidator.ValidationResult result = validator.validate(fakeResult);

            // UK Gilts comprado el 2026-09-12, 57 días antes de la venta → violación
            assertThat(result.violated()).isTrue();
        }
    }

    // =========================================================================
    // 5. ADAPTER — TaxEvaluatorPort
    // =========================================================================

    @Nested
    @DisplayName("5. TaxEvaluatorAdapter (implementación del SPI TaxEvaluatorPort)")
    class TaxEvaluatorAdapterTests {

        private TaxEvaluatorAdapter adapter;

        /**
         * Stub mínimo de TaxClassificationService para tests del adapter.
         * Devuelve siempre el fallback conservador (no hace llamadas HTTP).
         * La lógica de clasificación completa se prueba en TaxClassificationServiceTest.
         */
        private static TaxClassificationService stubClassificationService() {
            // TaxJevGateway es @FunctionalInterface → stub trivial sin Spring ni HTTP
            TaxJevGateway stubGateway = req -> {
                throw new com.wealth.ledger.jev.OpenJevClient.OpenJevException(
                        "Stub: no HTTP in unit tests", null);
            };
            // TaxClassificationService captura la excepción y devuelve fallback conservador
            return new TaxClassificationService(stubGateway);
        }

        @BeforeEach
        void setUp() {
            TwoMonthRuleValidator validator = new TwoMonthRuleValidator(repository);
            adapter = new TaxEvaluatorAdapter(fifo, validator, stubClassificationService());
            csvLoader.loadFromCsv(CSV_PATH);
        }

        @Test
        @DisplayName("Orden BUY → FrictionCost.ZERO (las compras no tributan)")
        void buyOrderReturnsZeroFriction() {
            com.wealth.shared.domain.RebalanceOrder buyOrder = new com.wealth.shared.domain.RebalanceOrder(
                    MSCI_WORLD, "iShares Core MSCI World",
                    com.wealth.shared.domain.RebalanceOrder.Side.BUY,
                    new BigDecimal("100"), new BigDecimal("97.65"),
                    new BigDecimal("9765.00"), "EUR");

            com.wealth.shared.spi.TaxEvaluatorPort.FrictionCost cost =
                    adapter.estimateFrictionCost(buyOrder);

            assertThat(cost.estimatedTaxCost()).isEqualByComparingTo("0.00");
            assertThat(cost.taxableGainLoss()).isEqualByComparingTo("0.00");
            assertThat(cost.affectedLots()).isZero();
            assertThat(cost.hasTaxFriction()).isFalse();
        }

        @Test
        @DisplayName("Orden SELL con plusvalía → impuesto positivo, hasTaxFriction() = true")
        void sellOrderWithGainHasTaxFriction() {
            // Vender 1000 uds de MSCI World a 97.65 € cuando el primer lote fue a 61.20 €
            // Plusvalía ≈ (97.65 - 61.20) × 1000 = 36.450 €
            com.wealth.shared.domain.RebalanceOrder sellOrder = new com.wealth.shared.domain.RebalanceOrder(
                    MSCI_WORLD, "iShares Core MSCI World",
                    com.wealth.shared.domain.RebalanceOrder.Side.SELL,
                    new BigDecimal("1000"), new BigDecimal("97.65"),
                    new BigDecimal("97650.00"), "EUR");

            com.wealth.shared.spi.TaxEvaluatorPort.FrictionCost cost =
                    adapter.estimateFrictionCost(sellOrder);

            assertThat(cost.taxableGainLoss()).isEqualByComparingTo("36450.00");
            assertThat(cost.estimatedTaxCost()).isPositive();
            assertThat(cost.hasTaxFriction()).isTrue();
            assertThat(cost.affectedLots()).isEqualTo(1);
            assertThat(cost.twoMonthRuleViolation()).isFalse();
        }

        @Test
        @DisplayName("Orden SELL con minusvalía → impuesto 0, hasLoss en el FIFO")
        void sellOrderWithLossHasZeroTax() {
            // Usamos un ISIN propio del test (sin lotes históricos del CSV)
            // para que el FIFO solo vea el lote caro que añadimos aquí.
            String testIsin = "TEST_LOSS_ISIN_001";
            repository.save(new TaxLotEntity(PORTFOLIO, testIsin,
                    LocalDate.of(2026, 10, 1),
                    new BigDecimal("300.00"),   // precio de compra muy alto
                    new BigDecimal("50"), "EUR", "TEST-LOSS"));

            // Vendemos a 215 €, compra a 300 € → minusvalía = (215-300)×50 = -4.250 €
            com.wealth.shared.domain.RebalanceOrder sellOrder = new com.wealth.shared.domain.RebalanceOrder(
                    testIsin, "Instrumento de test con minusvalía",
                    com.wealth.shared.domain.RebalanceOrder.Side.SELL,
                    new BigDecimal("50"), new BigDecimal("215.00"),
                    new BigDecimal("10750.00"), "EUR");

            com.wealth.shared.spi.TaxEvaluatorPort.FrictionCost cost =
                    adapter.estimateFrictionCost(sellOrder);

            // La minusvalía no tributa
            assertThat(cost.estimatedTaxCost()).isEqualByComparingTo("0.00");
            assertThat(cost.taxableGainLoss()).isNegative();
            assertThat(cost.hasTaxFriction()).isFalse();
        }

        @Test
        @DisplayName("Orden nula → FrictionCost.ZERO (defensa contra NPE)")
        void nullOrderReturnsZeroFriction() {
            com.wealth.shared.spi.TaxEvaluatorPort.FrictionCost cost =
                    adapter.estimateFrictionCost(null);

            assertThat(cost).isEqualTo(com.wealth.shared.spi.TaxEvaluatorPort.FrictionCost.ZERO);
        }
    }

    // =========================================================================
    // 6. ENTIDAD Y REPOSITORIO
    // =========================================================================

    @Nested
    @DisplayName("6. TaxLotEntity y TaxLotRepository")
    class EntityAndRepositoryTests {

        @Test
        @DisplayName("TaxLotEntity.consume() reduce availableQuantity correctamente")
        void consumeReducesAvailableQuantity() {
            TaxLotEntity lot = new TaxLotEntity(
                    PORTFOLIO, MSCI_WORLD, LocalDate.of(2024, 1, 1),
                    new BigDecimal("80.00"), new BigDecimal("1000"), "EUR", null);

            BigDecimal consumed = lot.consume(new BigDecimal("600"));

            assertThat(consumed).isEqualByComparingTo("600");
            assertThat(lot.getAvailableQuantity()).isEqualByComparingTo("400");
            assertThat(lot.isOpen()).isTrue();
        }

        @Test
        @DisplayName("TaxLotEntity.consume() agota el lote si se pide más de lo disponible")
        void consumeExhaustsLotWhenRequestedExceedsAvailable() {
            TaxLotEntity lot = new TaxLotEntity(
                    PORTFOLIO, MSCI_WORLD, LocalDate.of(2024, 1, 1),
                    new BigDecimal("80.00"), new BigDecimal("100"), "EUR", null);

            BigDecimal consumed = lot.consume(new BigDecimal("999"));

            assertThat(consumed).isEqualByComparingTo("100"); // solo se consume lo disponible
            assertThat(lot.getAvailableQuantity()).isEqualByComparingTo("0");
            assertThat(lot.isOpen()).isFalse();
        }

        @Test
        @DisplayName("TaxLotEntity.computeGainLoss() calcula correctamente plusvalía y minusvalía")
        void computeGainLossIsCorrect() {
            TaxLotEntity lot = new TaxLotEntity(
                    PORTFOLIO, GOLD, LocalDate.of(2020, 5, 10),
                    new BigDecimal("140.20"), new BigDecimal("500"), "EUR", null);

            BigDecimal plusvalia  = lot.computeGainLoss(new BigDecimal("100"), new BigDecimal("215.00"));
            BigDecimal minusvalia = lot.computeGainLoss(new BigDecimal("100"), new BigDecimal("120.00"));

            // (215.00 - 140.20) × 100 = 74.80 × 100 = 7.480
            assertThat(plusvalia).isEqualByComparingTo("7480.00");
            // (120.00 - 140.20) × 100 = -20.20 × 100 = -2.020
            assertThat(minusvalia).isEqualByComparingTo("-2020.00");
        }

        @Test
        @DisplayName("TaxLotRepository.computeWeightedAvgCost() calcula el precio medio correcto")
        void weightedAvgCostIsCorrect() {
            csvLoader.loadFromCsv(CSV_PATH);
            // MSCI World: 4 lotes BUY
            // 2000×61.20 + 3000×74.85 + 2000×88.30 + 1200×91.50
            // = 122.400 + 224.550 + 176.600 + 109.800 = 633.350 total coste
            // / 8200 uds = 77.237804... ≈ 77.237805
            BigDecimal avg = repository.computeWeightedAvgCost(PORTFOLIO, MSCI_WORLD);
            assertThat(avg).isBetween(new BigDecimal("77.23"), new BigDecimal("77.25"));
        }

        @Test
        @DisplayName("TaxLotRepository.clear() elimina todos los lotes")
        void clearEmptiesRepository() {
            csvLoader.loadFromCsv(CSV_PATH);
            assertThat(repository.count()).isGreaterThan(0);
            repository.clear();
            assertThat(repository.count()).isZero();
        }

        @Test
        @DisplayName("TaxLotEntity rechaza precio de compra negativo o cero")
        void entityRejectsInvalidPrice() {
            assertThatThrownBy(() -> new TaxLotEntity(
                    PORTFOLIO, GOLD, LocalDate.of(2024, 1, 1),
                    BigDecimal.ZERO, new BigDecimal("100"), "EUR", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("purchasePrice");
        }

        @Test
        @DisplayName("TaxLotEntity rechaza availableQuantity mayor que quantity")
        void entityRejectsAvailableExceedingQuantity() {
            assertThatThrownBy(() -> new TaxLotEntity(
                    null, PORTFOLIO, GOLD, LocalDate.of(2024, 1, 1),
                    new BigDecimal("80.00"), new BigDecimal("100"),
                    new BigDecimal("101"),  // > quantity
                    "EUR", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // 7. VALIDACIÓN DE PARÁMETROS
    // =========================================================================

    @Nested
    @DisplayName("7. Validación de parámetros de entrada")
    class ParameterValidationTests {

        @Test
        @DisplayName("estimateSale rechaza cantidad cero")
        void rejectsZeroQuantity() {
            assertThatThrownBy(() -> fifo.estimateSale(
                    PORTFOLIO, MSCI_WORLD, LocalDate.now(),
                    BigDecimal.ZERO, new BigDecimal("97.65")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quantity");
        }

        @Test
        @DisplayName("estimateSale rechaza precio negativo")
        void rejectsNegativePrice() {
            assertThatThrownBy(() -> fifo.estimateSale(
                    PORTFOLIO, MSCI_WORLD, LocalDate.now(),
                    new BigDecimal("100"), new BigDecimal("-10.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("salePrice");
        }

        @Test
        @DisplayName("estimateSale rechaza portfolioId nulo")
        void rejectsNullPortfolioId() {
            assertThatThrownBy(() -> fifo.estimateSale(
                    null, MSCI_WORLD, LocalDate.now(),
                    new BigDecimal("100"), new BigDecimal("97.65")))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
