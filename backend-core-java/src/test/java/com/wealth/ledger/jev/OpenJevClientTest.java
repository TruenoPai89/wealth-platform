package com.wealth.ledger.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Servidor HTTP local en un puerto libre: no depende de que OpenJEV este levantado en localhost:8080. */
class OpenJevClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> receivedBody = new AtomicReference<>();
    private final AtomicReference<String> receivedContentType = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/systemone", exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            receivedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = "{\"decision\":\"APPROVE\",\"score\":0.123456789}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/boom", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenJevClient client(String path) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + path;
        return new OpenJevClient(RestClient.builder(), url, 2000);
    }

    @Test
    void postsJsonAndDeserializesResponseToJsonNode() throws IOException {
        JsonNode response = client("/v1/systemone")
                .call(Map.of("portfolioId", "PF-001", "amount", new BigDecimal("7500.00")));

        assertThat(receivedContentType.get()).startsWith("application/json");
        JsonNode sent = mapper.readTree(receivedBody.get());
        assertThat(sent.get("portfolioId").asText()).isEqualTo("PF-001");
        assertThat(sent.get("amount").decimalValue()).isEqualByComparingTo("7500.00");
        assertThat(response.get("decision").asText()).isEqualTo("APPROVE");
    }

    @Test
    void deserializesResponseToTypedRecord() {
        Decision decision = client("/v1/systemone").call(Map.of("portfolioId", "PF-001"), Decision.class);

        assertThat(decision.decision()).isEqualTo("APPROVE");
        assertThat(decision.score()).isEqualByComparingTo("0.123456789");
    }

    @Test
    void wrapsHttpErrorsInOpenJevException() {
        assertThatThrownBy(() -> client("/boom").call(Map.of("x", 1)))
                .isInstanceOf(OpenJevClient.OpenJevException.class)
                .hasMessageContaining("OpenJEV System One call failed");
    }

    @Test
    void wrapsConnectionErrorsInOpenJevException() {
        OpenJevClient client = client("/v1/systemone");
        server.stop(0);

        assertThatThrownBy(() -> client.call(Map.of("x", 1)))
                .isInstanceOf(OpenJevClient.OpenJevException.class);
    }

    record Decision(String decision, BigDecimal score) {
    }
}
