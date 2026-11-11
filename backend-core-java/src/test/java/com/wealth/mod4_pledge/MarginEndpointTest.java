package com.wealth.mod4_pledge;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v1/margin/analyze sobre la cartera real del simulador (PF-001, 130.000 EUR). Sin efectos secundarios. */
@SpringBootTest
@AutoConfigureMockMvc
class MarginEndpointTest {

    private static final String ANALYZE = "/api/v1/margin/analyze";

    private static final String VALID = """
            {"portfolioId":"PF-001",
             "collateral":[{"instrumentId":"IE00B4L5Y983","amount":38000},{"instrumentId":"IE00B4WXJJ64","amount":29500}],
             "loanAmount":30000,"interestRate":0.04,"termYears":5,"marginCallLtv":0.70}""";

    @Autowired
    private MockMvc mvc;

    @Test
    void returnsTheDataEnvelopeWithNullError() throws Exception {
        mvc.perform(post(ANALYZE).contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.portfolioId").value("PF-001"))
                .andExpect(jsonPath("$.data.metrics.collateralValue").value(53150.00))
                .andExpect(jsonPath("$.data.metrics.ltv").value(0.5644))
                .andExpect(jsonPath("$.data.metrics.zone").value("OK"))
                .andExpect(jsonPath("$.data.securityLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.marginCallProbability", hasSize(3)))
                .andExpect(jsonPath("$.data.marginCallProbability[0].horizonDays").value(30))
                .andExpect(jsonPath("$.data.marginCallProbability[2].horizonDays").value(365))
                .andExpect(jsonPath("$.data.stressTest", hasSize(3)))
                .andExpect(jsonPath("$.data.funds", hasSize(2)));
    }

    @Test
    void portfolioIdIsOptionalAndDefaultsToPf001() throws Exception {
        String noPortfolio = VALID.replace("\"portfolioId\":\"PF-001\",", "");
        mvc.perform(post(ANALYZE).contentType(MediaType.APPLICATION_JSON).content(noPortfolio))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.portfolioId").value("PF-001"));
    }

    @Test
    void invalidConfigIs400WithTheErrorInTheEnvelope() throws Exception {
        mvc.perform(post(ANALYZE).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("\"marginCallLtv\":0.70", "\"marginCallLtv\":1.5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error").value(containsString("umbral")));
    }

    @Test
    void unknownFundIs400() throws Exception {
        mvc.perform(post(ANALYZE).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("IE00B4L5Y983", "XX0000000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("no está en la cartera")));
    }

    @Test
    void unknownPortfolioIs404() throws Exception {
        mvc.perform(post(ANALYZE).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID.replace("PF-001", "PF-999")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post(ANALYZE).contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void allowsTheFrontendOriginViaCors() throws Exception {
        mvc.perform(options(ANALYZE)
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void previousPledgeAndRebalanceEndpointsAreUntouched() throws Exception {
        mvc.perform(get("/api/v1/portfolios/PF-001/rebalance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions").exists());
        // Sin contrato guardado, /pledge sigue respondiendo su 404 con el formato antiguo { "error": ... }.
        mvc.perform(get("/api/v1/pledge/PF-001/contract"))
                .andExpect(status().is4xxClientError());
    }
}
