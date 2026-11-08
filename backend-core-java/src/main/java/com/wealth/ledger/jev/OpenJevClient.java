package com.wealth.ledger.jev;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Cliente HTTP del motor de decisiones OpenJEV (System One): POST {base-url} (por defecto http://localhost:8080/v1/systemone).
 * Usa el RestClient de Spring Boot sobre el HttpClient nativo de Java 21; la (de)serializacion la hace el
 * ObjectMapper de Spring (Jackson), ya configurado con BigDecimal para decimales.
 * <p>
 * El esquema concreto de peticion/respuesta de /v1/systemone no esta definido en el repo (ni en /contracts), por eso
 * el cliente es generico: acepta cualquier objeto serializable por Jackson y devuelve el tipo pedido o un JsonNode.
 */
@Component
public class OpenJevClient {

    private final RestClient restClient;

    public OpenJevClient(RestClient.Builder builder,
                         @Value("${wealth.openjev.base-url}") String baseUrl,
                         @Value("${wealth.openjev.timeout-ms:5000}") long timeoutMs) {
        Duration timeout = Duration.ofMillis(timeoutMs);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /** POST de la peticion como JSON y deserializacion de la respuesta al tipo indicado. */
    public <R> R call(Object request, Class<R> responseType) {
        try {
            R body = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(responseType);
            if (body == null) {
                throw new OpenJevException("OpenJEV System One returned an empty body", null);
            }
            return body;
        } catch (RestClientException e) {
            throw new OpenJevException("OpenJEV System One call failed: " + e.getMessage(), e);
        }
    }

    /** Igual que {@link #call(Object, Class)} pero devolviendo el arbol JSON sin tipar. */
    public JsonNode call(Object request) {
        return call(request, JsonNode.class);
    }

    public static class OpenJevException extends RuntimeException {
        public OpenJevException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
