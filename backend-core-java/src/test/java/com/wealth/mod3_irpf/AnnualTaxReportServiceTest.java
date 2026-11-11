package com.wealth.mod3_irpf;

import com.wealth.mod2_taxoptimizer.TaxLotEntity;
import com.wealth.mod2_taxoptimizer.TaxLotRepository;
import com.wealth.mod2_taxoptimizer.TwoMonthRuleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Resumen fiscal del ejercicio. Cifras calculadas a mano con los lotes de
 * bank-simulator-mocks/api-json-responses/historical_lots.json (mismos precios y fechas).
 */
class AnnualTaxReportServiceTest {

    private static final String PF = "PF-001";
    private static final String MSCI = "IE00B4L5Y983";
    private static final String SP500 = "IE00B3XXRP09";
    private static final String BOND = "IE00B4WXJJ64";
    private static final String GILTS = "IE00B3F81R35";

    private TaxLotRepository repo;
    private AnnualTaxReportService service;

    @BeforeEach
    void setUp() {
        repo = new TaxLotRepository();
        lot(MSCI, "2022-11-10", "61.20", "2000", "2000");
        lot(MSCI, "2023-06-15", "74.85", "3000", "3000");
        lot(SP500, "2021-03-05", "55.40", "1500", "1000");
        lot(SP500, "2022-08-18", "62.70", "800", "800");
        lot(SP500, "2024-11-20", "78.30", "1000", "1000");
        lot(BOND, "2023-06-01", "105.60", "1000", "1000");
        lot(GILTS, "2026-09-12", "9.85", "3100", "3100");
        service = new AnnualTaxReportService(repo, new TwoMonthRuleValidator(repo), new IrpfSimulationService());
    }

    private void lot(String isin, String date, String price, String qty, String available) {
        repo.save(new TaxLotEntity(null, PF, isin, LocalDate.parse(date), new BigDecimal(price),
                new BigDecimal(qty), new BigDecimal(available), "EUR", null));
    }

    private static AnnualTaxReportService.SaleLine sale(String isin, String date, String qty, String price) {
        return new AnnualTaxReportService.SaleLine(isin, LocalDate.parse(date), new BigDecimal(qty), new BigDecimal(price));
    }

    /** Las cinco ventas de ejemplo que trae precargadas la pantalla. */
    private static List<AnnualTaxReportService.SaleLine> demoSales() {
        return List.of(
                sale(MSCI, "2026-03-16", "300", "95.00"),
                sale(SP500, "2026-05-20", "600", "70.00"),
                sale(BOND, "2026-06-10", "400", "98.50"),
                sale(SP500, "2026-10-05", "600", "72.00"),
                sale(GILTS, "2026-10-20", "1000", "9.50"));
    }

    private static final List<LossCarryForwardBucket> CARRY_2023 =
            List.of(new LossCarryForwardBucket(2023, new BigDecimal("1500")));

    @Test
    @DisplayName("Ejemplo completo: 27.400 de ganancias, 2.840 de perdidas, 1.500 compensados, cuota 4.722,60")
    void demoScenario() {
        AnnualTaxReportService.Report r = service.build(PF, 2026, demoSales(), CARRY_2023);

        assertThat(r.operations()).hasSize(5);
        assertThat(r.totalTransmissionValue()).isEqualByComparingTo("162600.00");
        assertThat(r.totalAcquisitionValue()).isEqualByComparingTo("138390.00");
        assertThat(r.totalGains()).isEqualByComparingTo("27400.00");   // 10.140 + 8.760 + 8.500
        assertThat(r.totalLosses()).isEqualByComparingTo("2840.00");
        assertThat(r.deferredLosses()).isEqualByComparingTo("350.00");
        assertThat(r.result().netBalance()).isEqualByComparingTo("24560.00");
        assertThat(r.result().carryForwardUsed()).isEqualByComparingTo("1500.00");
        assertThat(r.result().taxableBase()).isEqualByComparingTo("23060.00");
        // 6.000 x 19 % + 17.060 x 21 % = 1.140 + 3.582,60
        assertThat(r.result().tax()).isEqualByComparingTo("4722.60");
    }

    @Test
    @DisplayName("Una venta: valor de transmision, de adquisicion y ganancia")
    void singleOperationValues() {
        AnnualTaxReportService.OperationResult op =
                service.build(PF, 2026, List.of(sale(MSCI, "2026-03-16", "300", "95.00")), List.of()).operations().get(0);

        assertThat(op.transmissionValue()).isEqualByComparingTo("28500.00");  // 300 x 95
        assertThat(op.acquisitionValue()).isEqualByComparingTo("18360.00");   // 300 x 61,20
        assertThat(op.gainLoss()).isEqualByComparingTo("10140.00");
        assertThat(op.firstAcquisitionDate()).isEqualTo(LocalDate.parse("2022-11-10"));
        assertThat(op.lotsUsed()).isEqualTo(1);
        assertThat(op.warning()).isNull();
    }

    @Test
    @DisplayName("Dos ventas del mismo fondo: la segunda sigue el FIFO donde acabo la primera")
    void sequentialFifo() {
        AnnualTaxReportService.Report r = service.build(PF, 2026, demoSales(), List.of());
        AnnualTaxReportService.OperationResult second = r.operations().get(3); // S&P del 5 de octubre

        // Quedan 400 del lote de 2021 (55,40) y se toman 200 del de 2022 (62,70)
        assertThat(second.lotsUsed()).isEqualTo(2);
        assertThat(second.acquisitionValue()).isEqualByComparingTo("34700.00"); // 22.160 + 12.540
        assertThat(second.gainLoss()).isEqualByComparingTo("8500.00");
        assertThat(second.firstAcquisitionDate()).isEqualTo(LocalDate.parse("2021-03-05"));
        assertThat(second.lastAcquisitionDate()).isEqualTo(LocalDate.parse("2022-08-18"));
    }

    @Test
    @DisplayName("Las ventas se ordenan por fecha aunque lleguen desordenadas")
    void salesAreSortedByDate() {
        AnnualTaxReportService.Report r = service.build(PF, 2026, List.of(
                sale(SP500, "2026-10-05", "600", "72.00"),
                sale(SP500, "2026-05-20", "600", "70.00")), List.of());

        assertThat(r.operations().get(0).saleDate()).isEqualTo(LocalDate.parse("2026-05-20"));
        assertThat(r.operations().get(1).gainLoss()).isEqualByComparingTo("8500.00");
    }

    @Test
    @DisplayName("El resumen no modifica los lotes reales")
    void doesNotMutateRepository() {
        service.build(PF, 2026, demoSales(), List.of());

        assertThat(repo.findOpenLotsFifo(PF, SP500).get(0).getAvailableQuantity()).isEqualByComparingTo("1000");
        assertThat(repo.findOpenLotsFifo(PF, MSCI).get(0).getAvailableQuantity()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("Regla de los 2 meses: la perdida se excluye del ejercicio")
    void twoMonthRuleDefersLoss() {
        AnnualTaxReportService.Report r = service.build(PF, 2026,
                List.of(sale(GILTS, "2026-10-20", "1000", "9.50")), List.of());
        AnnualTaxReportService.OperationResult op = r.operations().get(0);

        assertThat(op.gainLoss()).isEqualByComparingTo("-350.00");
        assertThat(op.twoMonthRuleViolation()).isTrue();
        assertThat(op.computableGainLoss()).isEqualByComparingTo("0");
        assertThat(r.totalLosses()).isEqualByComparingTo("0");
        assertThat(r.deferredLosses()).isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("Un lote comprado despues de la venta no se usa")
    void lotAfterSaleDateIsIgnored() {
        AnnualTaxReportService.OperationResult op = service.build(PF, 2026,
                List.of(sale(GILTS, "2026-05-01", "100", "10.00")), List.of()).operations().get(0);

        assertThat(op.quantityCovered()).isEqualByComparingTo("0");
        assertThat(op.lotsUsed()).isEqualTo(0);
        assertThat(op.warning()).contains("No hay lotes suficientes");
    }

    @Test
    @DisplayName("Saldo neto negativo: no hay cuota y nace un saldo a compensar en 4 anos")
    void negativeNetBalanceCreatesCarryForward() {
        AnnualTaxReportService.Report r = service.build(PF, 2026,
                List.of(sale(BOND, "2026-06-10", "400", "98.50")), List.of());

        assertThat(r.result().netBalance()).isEqualByComparingTo("-2840.00");
        assertThat(r.result().tax()).isEqualByComparingTo("0");
        assertThat(r.result().remainingCarryForward()).hasSize(1);
        assertThat(r.result().remainingCarryForward().get(0).remaining()).isEqualByComparingTo("2840.00");
    }

    @Test
    @DisplayName("Una venta de otro ejercicio se rechaza")
    void rejectsSaleFromAnotherYear() {
        assertThatThrownBy(() -> service.build(PF, 2026,
                List.of(sale(MSCI, "2025-12-30", "10", "90.00")), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no es del ejercicio 2026");
    }

    @Test
    @DisplayName("Sin ventas se rechaza")
    void rejectsEmptySales() {
        assertThatThrownBy(() -> service.build(PF, 2026, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
