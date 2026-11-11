package com.wealth.mod5_telephony;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v1/voice/web-session contra un ElevenLabs FALSO (servidor HTTP local): no se gasta nada. */
@SpringBootTest
@AutoConfigureMockMvc
class WebVoiceEndpointTest {

    private static final String SESSION = "/api/v1/voice/web-session";
    private static final AtomicReference<String> lastQuery = new AtomicReference<>();
    private static final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private static final AtomicInteger calls = new AtomicInteger();
    private static final HttpServer FAKE = startFake();

    private static HttpServer startFake() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/convai/conversation/token", exchange -> {
                calls.incrementAndGet();
                lastQuery.set(exchange.getRequestURI().getQuery());
                lastApiKey.set(exchange.getRequestHeaders().getFirst("xi-api-key"));
                reply(exchange, 200, "{\"token\":\"tok_test_abc\"}");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("wealth.telephony.elevenlabs.base-url", () -> "http://127.0.0.1:" + FAKE.getAddress().getPort());
        registry.add("wealth.telephony.elevenlabs.api-key", () -> "test-key-not-real");
        registry.add("wealth.telephony.elevenlabs.agent-id", () -> "agent_test");
        // Sin numero de telefono: la voz web no lo necesita.
        registry.add("wealth.telephony.elevenlabs.agent-phone-number-id", () -> "");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void returnsTokenAndNeverLeaksApiKey() throws Exception {
        mvc.perform(get(SESSION).param("userId", "CLI-001"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.conversationToken").value("tok_test_abc"))
                .andExpect(jsonPath("$.agentId").value("agent_test"))
                .andExpect(content().string(not(containsString("test-key-not-real"))));
        assertThat(lastQuery.get()).isEqualTo("agent_id=agent_test");
        assertThat(lastApiKey.get()).isEqualTo("test-key-not-real");
    }

    @Test
    void unknownUserIs404AndProviderIsNotCalled() throws Exception {
        int before = calls.get();
        mvc.perform(get(SESSION).param("userId", "NOPE-9")).andExpect(status().isNotFound());
        assertThat(calls.get()).isEqualTo(before);
    }

    @Test
    void invalidUserIdIs400() throws Exception {
        mvc.perform(get(SESSION).param("userId", "../etc")).andExpect(status().isBadRequest());
    }
}
