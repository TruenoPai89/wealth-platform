package com.wealth.mod5_telephony;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoints de telefonia contra un ElevenLabs FALSO (servidor HTTP local en un puerto libre): no se llama al proveedor
 * real ni se gasta nada. Cartera PF-001 del snapshot: total 130.000 EUR, caja 9.500, MSCI World 38.000, S&P 500 31.500,
 * bonos 29.500 y oro 21.500 (renta variable + oro = 70,0 %).
 * Cada test usa un telefono distinto porque el freno anti-abuso (CallThrottle) es por numero.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TelephonyEndpointTest {

    private static final String SECRET = "test-secret";
    private static final String TRIGGER = "/api/v1/telephony/trigger-call";
    private static final String CONTEXT = "/api/v1/telephony/agent-context/";

    private static final HttpServer FAKE_ELEVENLABS = startFakeElevenLabs();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();
    private static final AtomicReference<String> lastApiKey = new AtomicReference<>();

    private static HttpServer startFakeElevenLabs() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/convai/twilio/outbound-call", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                lastBody.set(body);
                lastApiKey.set(exchange.getRequestHeaders().getFirst("xi-api-key"));
                // El telefono +34600000500 simula un rechazo del proveedor (clave invalida).
                boolean reject = body.contains("+34600000500");
                String json = reject
                        ? "{\"detail\":{\"status\":\"invalid_api_key\"}}"
                        : "{\"success\":true,\"message\":\"Success\",\"conversation_id\":\"conv_test123456\","
                        + "\"callSid\":\"CA123\"}";
                reply(exchange, reject ? 401 : 200, json);
            });
            server.createContext("/v1/convai/conversations/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/v1/convai/conversations/".length());
                String status = id.startsWith("conv_live") ? "in-progress" : id.startsWith("conv_done") ? "done" : "initiated";
                reply(exchange, 200, "{\"status\":\"" + status + "\",\"transcript\":[]}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("wealth.telephony.elevenlabs.base-url", () -> "http://127.0.0.1:" + FAKE_ELEVENLABS.getAddress().getPort());
        registry.add("wealth.telephony.elevenlabs.api-key", () -> "test-key-not-real");
        registry.add("wealth.telephony.elevenlabs.agent-id", () -> "agent_test");
        registry.add("wealth.telephony.elevenlabs.agent-phone-number-id", () -> "phnum_test");
        registry.add("wealth.telephony.webhook-secret", () -> SECRET);
    }

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void resetCapture() {
        lastBody.set(null);
        lastApiKey.set(null);
    }

    private static String callBody(String phone) {
        return "{\"phoneNumber\":\"" + phone + "\",\"userId\":\"CLI-001\"}";
    }

    // ── POST /trigger-call ──

    @Test
    void triggerCallStartsOutboundCallWithAgentAndUserContext() throws Exception {
        mvc.perform(post(TRIGGER).contentType(MediaType.APPLICATION_JSON).content(callBody("+34 600 000 101")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.conversationId").value("conv_test123456"))
                .andExpect(jsonPath("$.status").value("dialing"))
                .andExpect(jsonPath("$.callSid").doesNotExist());

        assertThat(lastApiKey.get()).isEqualTo("test-key-not-real");
        assertThat(lastBody.get())
                .contains("\"agent_id\":\"agent_test\"")
                .contains("\"agent_phone_number_id\":\"phnum_test\"")
                .contains("\"to_number\":\"+34600000101\"")      // normalizado a E.164
                .contains("\"user_id\":\"CLI-001\"")
                .contains("\"dynamic_variables\"");
    }

    @Test
    void invalidPhoneIs400AndProviderIsNotCalled() throws Exception {
        mvc.perform(post(TRIGGER).contentType(MediaType.APPLICATION_JSON).content(callBody("600123456")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("formato internacional")));

        assertThat(lastBody.get()).isNull();
    }

    @Test
    void unknownUserIs404AndProviderIsNotCalled() throws Exception {
        mvc.perform(post(TRIGGER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+34600000102\",\"userId\":\"NOPE-9\"}"))
                .andExpect(status().isNotFound());

        assertThat(lastBody.get()).isNull();
    }

    @Test
    void repeatedCallToSameNumberIsThrottled() throws Exception {
        mvc.perform(post(TRIGGER).contentType(MediaType.APPLICATION_JSON).content(callBody("+34600000103")))
                .andExpect(status().isAccepted());

        mvc.perform(post(TRIGGER).contentType(MediaType.APPLICATION_JSON).content(callBody("+34600000103")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(containsString("hace poco")));
    }

    @Test
    void providerRejectionIs502WithoutLeakingKeyOrBody() throws Exception {
        mvc.perform(post(TRIGGER).contentType(MediaType.APPLICATION_JSON).content(callBody("+34600000500")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(containsString("HTTP 401")))
                .andExpect(jsonPath("$.error").value(not(containsString("test-key-not-real"))))
                .andExpect(jsonPath("$.error").value(not(containsString("invalid_api_key"))));

        // La llamada no llego a sonar: el hueco se libera y el cliente puede reintentar sin esperar el enfriamiento.
        mvc.perform(post(TRIGGER).contentType(MediaType.APPLICATION_JSON).content(callBody("+34600000500")))
                .andExpect(status().isBadGateway());
    }

    // ── GET /calls/{id}/status ──

    @Test
    void callStatusMapsProviderStatesForTheUi() throws Exception {
        mvc.perform(get("/api/v1/telephony/calls/conv_initiated1/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("dialing"));
        mvc.perform(get("/api/v1/telephony/calls/conv_live00001/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("in_call"));
        mvc.perform(get("/api/v1/telephony/calls/conv_done00001/status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ended"));
    }

    @Test
    void callStatusRejectsMalformedId() throws Exception {
        mvc.perform(get("/api/v1/telephony/calls/x/status")).andExpect(status().isBadRequest());
    }

    // ── GET /agent-context/{userId} ──

    @Test
    void agentContextIsCompactAndComplete() throws Exception {
        String body = mvc.perform(get(CONTEXT + "CLI-001").header("X-Telephony-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.portfolioId").value("PF-001"))
                .andExpect(jsonPath("$.total").value(130000))
                .andExpect(jsonPath("$.cash").value(9500.0))
                .andExpect(jsonPath("$.cashPct").value(7.3))                 // 9 500 / 130 000
                .andExpect(jsonPath("$.riskLevel").value("agresivo"))        // (38 000 + 31 500 + 21 500) / 130 000 = 70,0 %
                .andExpect(jsonPath("$.riskAssetsPct").value(70.0))
                .andExpect(jsonPath("$.positions", hasSize(4)))
                .andExpect(jsonPath("$.positions[0].name").value("iShares Core MSCI World"))   // mayor a menor
                .andExpect(jsonPath("$.positions[0].pct").value(29.2))       // 38 000 / 130 000
                .andExpect(jsonPath("$.positions[0].eur").value(38000))
                .andExpect(jsonPath("$.baskets", hasSize(2)))
                .andExpect(jsonPath("$.baskets[0].name").value("Agrupación 1"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body.getBytes(StandardCharsets.UTF_8).length)
                .as("el webhook debe ser ultracompacto (menos de 1,5 KB)").isLessThan(1500);
        assertThat(body).doesNotContain("\n ").doesNotContain("costBasis").doesNotContain("isin");
    }

    @Test
    void agentContextAcceptsPortfolioIdAsUserId() throws Exception {
        mvc.perform(get(CONTEXT + "PF-001").header("X-Telephony-Secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolioId").value("PF-001"));
    }

    @Test
    void agentContextRequiresWebhookSecret() throws Exception {
        mvc.perform(get(CONTEXT + "CLI-001")).andExpect(status().isUnauthorized());
        mvc.perform(get(CONTEXT + "CLI-001").header("X-Telephony-Secret", "wrong")).andExpect(status().isUnauthorized());
    }

    @Test
    void agentContextUnknownUserIs404() throws Exception {
        mvc.perform(get(CONTEXT + "NOPE-9").header("X-Telephony-Secret", SECRET)).andExpect(status().isNotFound());
    }

    @Test
    void corsPreflightAllowsPostFromFrontend() throws Exception {
        mvc.perform(options(TRIGGER)
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }
}
