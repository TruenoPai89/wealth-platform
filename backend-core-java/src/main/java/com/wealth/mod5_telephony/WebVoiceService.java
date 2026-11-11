package com.wealth.mod5_telephony;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wealth.ledger.jev.JevLedgerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * Voz en el navegador (WebRTC) con el agente de ElevenLabs, sin telefono ni Twilio.
 * <p>
 * El agente es PRIVADO: el navegador nunca ve la API key. El frontend pide aqui un token de conversacion de un solo
 * uso; este servicio lo obtiene de ElevenLabs ({@code GET /v1/convai/conversation/token?agent_id=}) con la cabecera
 * {@code xi-api-key} y se lo devuelve. Con ese token el SDK {@code @elevenlabs/react} abre la sesion de voz.
 * <p>
 * Solo necesita ELEVENLABS_API_KEY y ELEVENLABS_AGENT_ID (no el numero de telefono). Cada sesion consume creditos y
 * el backend se expone por ngrok, asi que hay un tope global de sesiones por hora.
 */
@Service
public class WebVoiceService {

    private static final Logger log = LoggerFactory.getLogger(WebVoiceService.class);
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    static final int MAX_SESSIONS_PER_HOUR = 30;

    public record WebSession(String conversationToken, String agentId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(String token) {
    }

    private final TelephonyProperties properties;
    private final JevLedgerAdapter ledger;
    private final RestClient restClient;
    private final Clock clock;
    private final Deque<Instant> issued = new ArrayDeque<>();

    /** Constructor que usa Spring: el unico que ve el contenedor (@Autowired lo deja explicito). */
    @Autowired
    public WebVoiceService(TelephonyProperties properties, JevLedgerAdapter ledger, RestClient.Builder builder) {
        this(properties, ledger, builder, Clock.systemUTC());
    }

    /** Constructor para tests: permite inyectar un reloj controlado. */
    WebVoiceService(TelephonyProperties properties, JevLedgerAdapter ledger, RestClient.Builder builder, Clock clock) {
        this.properties = properties;
        this.ledger = ledger;
        this.clock = clock;
        TelephonyProperties.ElevenLabs config = properties.elevenlabs();
        Duration timeout = Duration.ofMillis(config.timeoutMs());
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.restClient = builder.clone()
                .baseUrl(config.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public WebSession startSession(String userId) {
        TelephonyProperties.ElevenLabs config = properties.elevenlabs();
        if (config.apiKey().isBlank() || config.agentId().isBlank()) {
            throw new TelephonyException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La voz no está configurada en el servidor (faltan ELEVENLABS_API_KEY o ELEVENLABS_AGENT_ID).");
        }
        if (userId == null || !SAFE_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("Identificador de usuario no válido.");
        }
        // 404 si el cliente no existe: no se abren sesiones (que cuestan creditos) para usuarios inventados.
        ledger.loadPortfolio(properties.portfolioByUser().getOrDefault(userId, userId));
        reserveSlot();

        try {
            TokenResponse body = restClient.get()
                    .uri(uri -> uri.path("/v1/convai/conversation/token").queryParam("agent_id", config.agentId()).build())
                    .header("xi-api-key", config.apiKey())
                    .retrieve()
                    .body(TokenResponse.class);
            if (body == null || body.token() == null || body.token().isBlank()) {
                throw new TelephonyException(HttpStatus.BAD_GATEWAY, "El proveedor de voz no devolvió un token.");
            }
            log.info("Sesion de voz web concedida para userId={}", userId);
            return new WebSession(body.token(), config.agentId());
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            log.warn("ElevenLabs respondio HTTP {} al pedir el token de voz", code);
            String hint = code == 401 || code == 403
                    ? "Las credenciales del proveedor de voz no son válidas."
                    : "Revisa el ID del agente.";
            throw new TelephonyException(HttpStatus.BAD_GATEWAY,
                    "El proveedor de voz rechazó la sesión (HTTP " + code + "). " + hint);
        } catch (RestClientException e) {
            log.warn("No se pudo contactar con ElevenLabs al pedir el token de voz: {}", e.getClass().getSimpleName());
            throw new TelephonyException(HttpStatus.BAD_GATEWAY, "No se pudo contactar con el proveedor de voz.");
        }
    }

    /** Ventana deslizante de una hora: como mucho MAX_SESSIONS_PER_HOUR tokens, sea quien sea quien los pida. */
    private synchronized void reserveSlot() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofHours(1));
        while (!issued.isEmpty() && issued.peekFirst().isBefore(cutoff)) {
            issued.pollFirst();
        }
        if (issued.size() >= MAX_SESSIONS_PER_HOUR) {
            throw new TelephonyException(HttpStatus.TOO_MANY_REQUESTS,
                    "Se ha alcanzado el máximo de sesiones de voz por hora. Inténtalo más tarde.");
        }
        issued.addLast(now);
    }
}
