package com.wealth.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CorsIntegrationTest {

    private static final String URL = "/api/v1/portfolios/PF-001/rebalance";
    private static final String FRONTEND = "http://localhost:3000";

    @Autowired
    private MockMvc mvc;

    @Test
    void allowsFrontendOriginOnRebalanceEndpoint() throws Exception {
        mvc.perform(get(URL).header("Origin", FRONTEND))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
                .andExpect(jsonPath("$.portfolioId").value("PF-001"));
    }

    @Test
    void answersPreflightForFrontendOrigin() throws Exception {
        mvc.perform(options(URL)
                        .header("Origin", FRONTEND)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("GET")));
    }

    @Test
    void rejectsUnknownOrigin() throws Exception {
        mvc.perform(get(URL).header("Origin", "http://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void errorResponsesAlsoCarryCorsHeaderSoTheBrowserCanReadThem() throws Exception {
        mvc.perform(get("/api/v1/portfolios/PF-999/rebalance").header("Origin", FRONTEND))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Access-Control-Allow-Origin", FRONTEND))
                .andExpect(jsonPath("$.error").exists());
    }
}
