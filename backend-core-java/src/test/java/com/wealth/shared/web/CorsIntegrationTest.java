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
    void allowsLoopbackIpAndOtherLocalPorts() throws Exception {
        // Abrir el frontend por 127.0.0.1, o que Next arranque en 3001 si el 3000 esta ocupado, no debe romper la API
        for (String origin : new String[]{"http://127.0.0.1:3000", "http://localhost:3001"}) {
            mvc.perform(get(URL).header("Origin", origin))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
            mvc.perform(options(URL)
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }

    @Test
    void stillRejectsRemoteOriginsAndLookalikeHosts() throws Exception {
        for (String origin : new String[]{"http://evil.example", "http://localhost.evil.example:3000",
                "http://192.168.1.10:3000"}) {
            mvc.perform(get(URL).header("Origin", origin)).andExpect(status().isForbidden());
        }
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
