package com.wealth.mod1_rebalancer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El contexto de Spring se comparte entre tests: cada test deja la meta de PF-001 como estaba. */
@SpringBootTest
@AutoConfigureMockMvc
class TargetsEndpointTest {

    private static final String TARGETS = "/api/v1/portfolios/PF-001/targets";
    private static final String REBALANCE = "/api/v1/portfolios/PF-001/rebalance";

    /** MSCI 40 / S&P 15 / bonos 30 / oro 10 / caja 5. */
    private static final String NEW_TARGET = """
            {"IE00B4L5Y983":0.40,"IE00B3XXRP09":0.15,"IE00B4WXJJ64":0.30,"IE00B579F325":0.10,"CASH:EUR":0.05}""";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TargetAllocationService targetService;

    @AfterEach
    void restoreDefaults() {
        targetService.reset("PF-001");
    }

    @Test
    void returnsConfiguredTargetByDefault() throws Exception {
        mvc.perform(get(TARGETS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['IE00B4L5Y983']").value(0.35))
                .andExpect(jsonPath("$.['CASH:EUR']").value(0.05));
    }

    @Test
    void updatedTargetDrivesTheNextRebalance() throws Exception {
        mvc.perform(put(TARGETS).contentType(MediaType.APPLICATION_JSON).content(NEW_TARGET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['IE00B3XXRP09']").value(0.15));

        // Total 130.000: S&P objetivo 19.500 vs 31.500 -> vender 12.000/105 = 114,2857 uds;
        // MSCI objetivo 52.000 vs 38.000 -> comprar 14.000/95 = 147,3684 uds.
        mvc.perform(get(REBALANCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[?(@.instrumentId=='IE00B3XXRP09')].side").value("SELL"))
                .andExpect(jsonPath("$.orders[?(@.instrumentId=='IE00B3XXRP09')].quantity").value(114.2857))
                .andExpect(jsonPath("$.orders[?(@.instrumentId=='IE00B4L5Y983')].side").value("BUY"))
                .andExpect(jsonPath("$.orders[?(@.instrumentId=='IE00B4L5Y983')].quantity").value(147.3684))
                .andExpect(jsonPath("$.positions[?(@.instrumentId=='IE00B4L5Y983')].targetWeight").value(0.4));
    }

    @Test
    void rejectsWeightsThatDoNotSumToOne() throws Exception {
        String bad = NEW_TARGET.replace("\"CASH:EUR\":0.05", "\"CASH:EUR\":0.10");

        mvc.perform(put(TARGETS).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("sum to 1")));
        mvc.perform(get(TARGETS)).andExpect(jsonPath("$.['IE00B4L5Y983']").value(0.35)); // sin cambios
    }

    @Test
    void rejectsNegativeWeight() throws Exception {
        String bad = """
                {"IE00B4L5Y983":1.10,"IE00B3XXRP09":-0.10,"IE00B4WXJJ64":0,"IE00B579F325":0,"CASH:EUR":0}""";

        mvc.perform(put(TARGETS).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownOrMissingInstruments() throws Exception {
        mvc.perform(put(TARGETS).contentType(MediaType.APPLICATION_JSON).content("{\"IE00B4L5Y983\":1.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("exactly these instruments")));
    }

    @Test
    void unknownPortfolioIs404() throws Exception {
        mvc.perform(put("/api/v1/portfolios/PF-999/targets").contentType(MediaType.APPLICATION_JSON).content(NEW_TARGET))
                .andExpect(status().isNotFound());
    }

    @Test
    void corsPreflightAllowsPutFromFrontend() throws Exception {
        mvc.perform(options(TARGETS)
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("PUT")));
    }

    @Test
    void orderListCoversEveryInstrumentThatMoves() throws Exception {
        mvc.perform(put(TARGETS).contentType(MediaType.APPLICATION_JSON).content(NEW_TARGET)).andExpect(status().isOk());

        mvc.perform(get(REBALANCE))
                .andExpect(jsonPath("$.orders", hasSize(4)));
    }
}
