package com.wealth.mod1_rebalancer;

import com.wealth.ledger.jev.JevLedgerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agrupaciones e inversion de una aportacion (cash-inflow rebalancing). El contexto se comparte: se limpia el estado. */
@SpringBootTest
@AutoConfigureMockMvc
class ContributionTest {

    private static final String BASE = "/api/v1/portfolios/PF-001";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String MSCI = "IE00B4L5Y983";
    private static final String SP500 = "IE00B3XXRP09";
    private static final String BOND = "IE00B4WXJJ64";
    private static final String GOLD = "IE00B579F325";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TransferService transferService;
    @Autowired
    private JevLedgerAdapter ledger;
    @Autowired
    private TargetAllocationService targets;

    @BeforeEach
    @AfterEach
    void cleanState() {
        transferService.clear();
        ledger.resetPostedState("PF-001");
        targets.reset("PF-001");
    }

    private static String amount(String value) {
        return "{\"amount\":" + value + "}";
    }

    @Test
    void listsTheTwoBasketsWithNormalisedWeights() throws Exception {
        mvc.perform(get(BASE + "/baskets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("agrupacion-1"))
                .andExpect(jsonPath("$[0].name").value("Agrupación 1"))
                .andExpect(jsonPath("$[0].weights", org.hamcrest.Matchers.aMapWithSize(3)))
                .andExpect(jsonPath("$[0].weights." + MSCI).value(closeTo(0.333333, 0.000001)))
                .andExpect(jsonPath("$[1].id").value("agrupacion-2"))
                .andExpect(jsonPath("$[1].weights", org.hamcrest.Matchers.aMapWithSize(4)))
                .andExpect(jsonPath("$[1].weights." + MSCI).value(closeTo(0.4, 0.000001)))
                .andExpect(jsonPath("$[1].weights." + GOLD).value(closeTo(0.1, 0.000001)));
    }

    @Test
    void previewOnlyBuysAndInvestsTheWholeAmountWithinRoundingCents() throws Exception {
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution/preview").contentType(JSON).content(amount("5000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[*].side", everyItem(is("BUY"))))
                .andExpect(jsonPath("$.amount").value(5000.00))
                .andExpect(jsonPath("$.invested", closeTo(5000.00, 0.5)))
                .andExpect(jsonPath("$.unallocated", closeTo(0.0, 0.5)))
                .andExpect(jsonPath("$.unallocated", greaterThanOrEqualTo(0.0)));
    }

    @Test
    void previewFillsTheMostUnderweightFundFirst() throws Exception {
        // Valores 38.000 / 31.500 / 29.500 con pesos iguales: la renta fija (la mas baja) recibe mas que el MSCI
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution/preview").contentType(JSON).content(amount("5000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.funds[?(@.instrumentId=='" + BOND + "')].buyAmount",
                        hasItem(org.hamcrest.Matchers.greaterThan(1666.67))))
                .andExpect(jsonPath("$.funds[?(@.instrumentId=='" + MSCI + "')].buyAmount",
                        hasItem(org.hamcrest.Matchers.lessThan(1666.67))));
    }

    @Test
    void previewHasNoSideEffects() throws Exception {
        mvc.perform(post(BASE + "/baskets/agrupacion-2/contribution/preview").contentType(JSON).content(amount("5000")))
                .andExpect(status().isOk());

        mvc.perform(get(BASE + "/transfers")).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get(BASE + "/rebalance"))
                .andExpect(jsonPath("$.positions[?(@.instrumentId=='CASH:EUR')].marketValue", hasItem(9500.00)));
    }

    @Test
    void quantitiesHaveAtMostFourDecimals() throws Exception {
        String body = mvc.perform(post(BASE + "/baskets/agrupacion-2/contribution/preview").contentType(JSON)
                        .content(amount("5000")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"quantity\":([0-9.]+)").matcher(body);
        int found = 0;
        while (m.find()) {
            String q = m.group(1);
            int dot = q.indexOf('.');
            org.assertj.core.api.Assertions.assertThat(dot < 0 ? 0 : q.length() - dot - 1)
                    .as("decimales de %s", q).isLessThanOrEqualTo(4);
            found++;
        }
        org.assertj.core.api.Assertions.assertThat(found).isGreaterThan(0);
    }

    @Test
    void executeRecordsContributionAndOneBuyPerFundAndUpdatesThePortfolio() throws Exception {
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution").contentType(JSON).content(amount("5000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(containsString("CON-PF-001-")))
                .andExpect(jsonPath("$.basketName").value("Agrupación 1"))
                .andExpect(jsonPath("$.transfers[?(@.type=='CONTRIBUTION')]", hasSize(1)))
                .andExpect(jsonPath("$.transfers[?(@.type=='CONTRIBUTION')].amount", hasItem(5000.00)))
                // Valores 38.000 / 31.500 / 29.500 con pesos iguales: el nivel final es 33.000 por fondo. El MSCI ya lo
                // supera y NO recibe nada (no se vende ni se compra de mas): solo compran el bono (3.500) y el S&P (1.500).
                .andExpect(jsonPath("$.transfers[?(@.type=='BUY')]", hasSize(2)))
                .andExpect(jsonPath("$.transfers[?(@.type=='BUY' && @.instrumentId=='" + BOND + "')].amount", hasItem(3500.00)))
                .andExpect(jsonPath("$.transfers[?(@.type=='BUY' && @.instrumentId=='" + SP500 + "')].amount", hasItem(1500.00)))
                .andExpect(jsonPath("$.transfers[?(@.instrumentId=='" + MSCI + "')]", hasSize(0)))
                .andExpect(jsonPath("$.transfers[?(@.type=='SELL')]", hasSize(0)));

        // El historial lo refleja y la cartera ya tiene el dinero invertido (valor total = 130.000 + 5.000)
        mvc.perform(get(BASE + "/transfers")).andExpect(jsonPath("$", hasSize(3)));
        mvc.perform(get(BASE + "/rebalance"))
                .andExpect(jsonPath("$.totalValue", closeTo(135000.00, 0.05)));
    }

    @Test
    void theContributionIsSettledImmediatelyAndBuysFollowTheNormalSettlement() throws Exception {
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution").contentType(JSON).content(amount("1000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers[?(@.type=='CONTRIBUTION')].status", hasItem("COMPLETED")))
                .andExpect(jsonPath("$.transfers[?(@.type=='BUY')].status", everyItem(is("IN_PROGRESS"))));
    }

    @Test
    void everyMovementPointsToItsJournalEntry() throws Exception {
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution").contentType(JSON).content(amount("1000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transfers[?(@.type=='CONTRIBUTION')].journalRef", hasItem(endsWith("-001"))))
                .andExpect(jsonPath("$.transfers[?(@.type=='BUY')].journalRef", everyItem(containsString("CON-PF-001-"))));
    }

    private static org.hamcrest.Matcher<String> endsWith(String s) {
        return org.hamcrest.Matchers.endsWith(s);
    }

    @Test
    void aSecondContributionStacksOnTopOfTheFirst() throws Exception {
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution").contentType(JSON).content(amount("5000")))
                .andExpect(status().isOk());
        mvc.perform(post(BASE + "/baskets/agrupacion-2/contribution").contentType(JSON).content(amount("2000")))
                .andExpect(status().isOk());

        // 1a: aportacion + bono + S&P (3). 2a (Agrupacion 2, 40/30/20/10): solo el MSCI esta por debajo de su nivel,
        // asi que aportacion + MSCI (2). Total 5, con dos aportaciones.
        mvc.perform(get(BASE + "/transfers"))
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[?(@.type=='CONTRIBUTION')]", hasSize(2)))
                .andExpect(jsonPath("$[?(@.type=='SELL')]", hasSize(0)));
        mvc.perform(get(BASE + "/rebalance")).andExpect(jsonPath("$.totalValue", closeTo(137000.00, 0.1)));
    }

    @Test
    void aContributionAndARebalanceCoexist() throws Exception {
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution").contentType(JSON).content(amount("5000")))
                .andExpect(status().isOk());

        mvc.perform(post(BASE + "/rebalance/execute")).andExpect(status().isOk());

        mvc.perform(get(BASE + "/transfers"))
                .andExpect(jsonPath("$[?(@.type=='CONTRIBUTION')]", hasSize(1)));
    }

    @Test
    void rejectsInvalidAmounts() throws Exception {
        for (String bad : new String[]{"0", "-100", "0.001", "null"}) {
            mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution/preview").contentType(JSON).content(amount(bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").exists());
        }
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution/preview").contentType(JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution/preview").contentType(JSON)
                        .content(amount("999999999999")))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/transfers")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void theSmallestAmountStillBuysAndNeverSpendsMoreThanContributed() throws Exception {
        // 0,01 EUR al precio del bono (118) son 0,0000847 uds: HALF_UP a 4 decimales da 0,0001 uds, que cuestan 0,01
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution/preview").contentType(JSON).content(amount("0.01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders", hasSize(1)))
                .andExpect(jsonPath("$.orders[0].quantity").value(0.0001))
                .andExpect(jsonPath("$.invested", closeTo(0.01, 0.000001)));
    }

    /**
     * Invariante: la suma de las compras redondeadas NUNCA supera la aportacion. Redondear cada cantidad a 4
     * decimales HALF_UP puede, en teoria, pasarse unos centimos; el servicio lo corrige quitando 0,0001 uds a la
     * mayor orden. Se barren 301 importes consecutivos por cesta y se exige 200, invertido <= aportado y sin ventas.
     * (No se ha observado ningun importe que dispare esa correccion: el test fija la garantia, no un fallo concreto.)
     */
    @Test
    void roundingNeverMakesTheOrdersExceedTheContribution() throws Exception {
        for (String basket : new String[]{"agrupacion-1", "agrupacion-2"}) {
            for (int cents = 100_000; cents <= 100_300; cents++) {
                String value = new java.math.BigDecimal(cents).movePointLeft(2).toPlainString();
                String body = mvc.perform(post(BASE + "/baskets/" + basket + "/contribution/preview")
                                .contentType(JSON).content(amount(value)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.orders[*].side", everyItem(is("BUY"))))
                        .andReturn().getResponse().getContentAsString();
                java.math.BigDecimal invested = new java.math.BigDecimal(
                        com.jayway.jsonpath.JsonPath.read(body, "$.invested").toString());
                org.assertj.core.api.Assertions.assertThat(invested)
                        .as("%s con %s EUR", basket, value)
                        .isLessThanOrEqualTo(new java.math.BigDecimal(value));
            }
        }
    }

    @Test
    void unknownBasketOrPortfolioIs404() throws Exception {
        mvc.perform(post(BASE + "/baskets/no-existe/contribution").contentType(JSON).content(amount("100")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/portfolios/PF-999/baskets/agrupacion-1/contribution").contentType(JSON)
                        .content(amount("100")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/portfolios/PF-999/baskets")).andExpect(status().isNotFound());
    }

    @Test
    void theProjectedPortfolioComesBackAgainstThePortfolioTarget() throws Exception {
        mvc.perform(post(BASE + "/baskets/agrupacion-1/contribution/preview").contentType(JSON).content(amount("5000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectedPositions", hasSize(5)))
                .andExpect(jsonPath("$.projectedPositions[?(@.instrumentId=='" + SP500 + "')].targetWeight",
                        hasItem(0.2)));
    }
}
