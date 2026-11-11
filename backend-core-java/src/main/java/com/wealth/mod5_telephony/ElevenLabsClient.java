package com.wealth.mod5_telephony;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Cliente de la API de ElevenLabs Conversational AI (llamadas salientes via Twilio):
 * <ul>
 *   <li>POST /v1/convai/twilio/outbound-call: ElevenLabs llama al telefono con el agente de voz.</li>
 *   <li>GET  /v1/convai/conversations/{id}: estado (initiated, in-progress, processing, done, failed).</li>
 * </ul>
 * La API key viaja en la cabecera {@code xi-api-key}; no se escribe en logs ni en mensajes de error. Misma base tecnica
 * que {@code OpenJevClient}: RestClient sobre el HttpClient nativo de Java 21, con timeouts.
 */
@Component
public class ElevenLabsClient {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsClient.class);

    private final RestClient restClient;
    private final TelephonyProperties.ElevenLabs config;

    public ElevenLabsClient(RestClient.Builder builder, TelephonyProperties properties) {
        this.config = properties.elevenlabs();
        Duration timeout = Duration.ofMillis(config.timeoutMs());
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = builder
                .baseUrl(config.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // ── Cuerpos de la API (snake_case segun la documentacion de ElevenLabs) ──

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OutboundCallRequest(
            @JsonProperty("agent_id") String agentId,
            @JsonProperty("agent_phone_number_id") String agentPhoneNumberId,
            @JsonProperty("to_number") String toNumber,
            @JsonProperty("conversation_initiation_client_data") ClientData clientData,
            @JsonProperty("telephony_call_config") CallConfig callConfig) {
    }

    /** user_id identifica al cliente; la variable dinamica user_id permite al agente pedir /agent-context/{userId}. */
    record ClientData(
            @JsonProperty("user_id") String userId,
            @JsonProperty("dynamic_variables") Map<String, Object> dynamicVariables) {
    }

    record CallConfig(@JsonProperty("ringing_timeout_secs") int ringingTimeoutSecs) {
    }

    /** Respuesta de outbound-call. Ojo: {@code callSid} viene en camelCase y el resto en snake_case. */
    public record OutboundCallResponse(
            boolean success,
            String message,
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("callSid") String callSid) {
    }

    /** Solo interesa el estado; el resto de campos de la conversacion se ignoran. */
    record ConversationDetails(String status) {
    }

    // ── Llamadas ──

    /** Pide a ElevenLabs que llame a {@code toNumber} (E.164) con el agente configurado. */
    public OutboundCallResponse startOutboundCall(String toNumber, String userId) {
        OutboundCallRequest request = new OutboundCallRequest(
                config.agentId(),
                config.agentPhoneNumberId(),
                toNumber,
                new ClientData(userId, Map.of("user_id", userId)),
                new CallConfig(config.ringingTimeoutSecs()));
        try {
            OutboundCallResponse body = restClient.post()
                    .uri("/v1/convai/twilio/outbound-call")
                    .header("xi-api-key", config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OutboundCallResponse.class);
            if (body == null || !body.success() || body.conversationId() == null) {
                log.warn("ElevenLabs no inicio la llamada (success={})", body == null ? null : body.success());
                throw new TelephonyException(HttpStatus.BAD_GATEWAY,
                        "El proveedor de voz no pudo iniciar la llamada. Inténtalo de nuevo en unos minutos.");
            }
            return body;
        } catch (RestClientResponseException e) {
            throw providerError("iniciar la llamada", e);
        } catch (RestClientException e) {
            log.warn("No se pudo contactar con ElevenLabs al iniciar la llamada: {}", e.getClass().getSimpleName());
            throw new TelephonyException(HttpStatus.BAD_GATEWAY, "No se pudo contactar con el proveedor de voz.");
        }
    }

    /** Estado crudo de la conversacion (initiated, in-progress, processing, done, failed). */
    public String conversationStatus(String conversationId) {
        try {
            ConversationDetails body = restClient.get()
                    .uri("/v1/convai/conversations/{id}", conversationId)
                    .header("xi-api-key", config.apiKey())
                    .retrieve()
                    .body(ConversationDetails.class);
            if (body == null || body.status() == null) {
                throw new TelephonyException(HttpStatus.BAD_GATEWAY,
                        "El proveedor de voz no devolvió el estado de la llamada.");
            }
            return body.status();
        } catch (RestClientResponseException e) {
            throw providerError("consultar la llamada", e);
        } catch (RestClientException e) {
            log.warn("No se pudo contactar con ElevenLabs al consultar la llamada: {}", e.getClass().getSimpleName());
            throw new TelephonyException(HttpStatus.BAD_GATEWAY, "No se pudo contactar con el proveedor de voz.");
        }
    }

    /**
     * Traduce un error HTTP del proveedor a un 502 propio. Al cliente solo le llega el codigo (nunca la clave ni el
     * cuerpo del proveedor, que puede contener datos del telefono); el detalle queda en DEBUG.
     */
    private TelephonyException providerError(String action, RestClientResponseException e) {
        int code = e.getStatusCode().value();
        log.warn("ElevenLabs respondio HTTP {} al {}", code, action);
        log.debug("Cuerpo de la respuesta de ElevenLabs: {}", e.getResponseBodyAsString());
        String hint = code == 401 || code == 403
                ? "Las credenciales del proveedor de voz no son válidas."
                : "Revisa el número y la configuración del agente.";
        return new TelephonyException(HttpStatus.BAD_GATEWAY,
                "El proveedor de voz rechazó la solicitud al " + action + " (HTTP " + code + "). " + hint);
    }
}
