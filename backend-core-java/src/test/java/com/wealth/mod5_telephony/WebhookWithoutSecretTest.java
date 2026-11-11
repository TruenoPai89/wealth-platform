package com.wealth.mod5_telephony;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sin TELEPHONY_WEBHOOK_SECRET los webhooks que exponemos por ngrok quedan cerrados (503), con o sin cabecera:
 * nunca devuelven la cartera. El secreto vacio se fuerza aqui aunque la variable exista en el entorno.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "wealth.telephony.webhook-secret=")
class WebhookWithoutSecretTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void agentContextIsClosedWhenSecretIsNotConfigured() throws Exception {
        mvc.perform(get("/api/v1/telephony/agent-context/CLI-001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value(containsString("TELEPHONY_WEBHOOK_SECRET")));
        mvc.perform(get("/api/v1/telephony/agent-context/CLI-001").header("X-Telephony-Secret", ""))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void fundDfiIsClosedWhenSecretIsNotConfigured() throws Exception {
        mvc.perform(get("/api/v1/telephony/fund-dfi").param("isin_or_name", "oro"))
                .andExpect(status().isServiceUnavailable());
    }
}
