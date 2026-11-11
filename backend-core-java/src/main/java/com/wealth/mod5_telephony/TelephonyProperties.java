package com.wealth.mod5_telephony;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Configuracion de la llamada de voz con el asesor de IA (wealth.telephony.* en application.yml).
 * <p>
 * Los secretos (API key de ElevenLabs, secreto del webhook) NUNCA van en el repositorio: application.yml solo
 * referencia variables de entorno (ELEVENLABS_API_KEY, TELEPHONY_WEBHOOK_SECRET...). Si faltan, la app arranca igual
 * y los endpoints de telefonia responden 503 con un mensaje claro.
 */
@ConfigurationProperties(prefix = "wealth.telephony")
public record TelephonyProperties(
        @DefaultValue ElevenLabs elevenlabs,
        @DefaultValue("") String webhookSecret,
        @DefaultValue Limits limits,
        Map<String, String> portfolioByUser) {

    public TelephonyProperties {
        webhookSecret = webhookSecret == null ? "" : webhookSecret;
        // userId (clientId del cliente) -> portfolioId. Si no hay entrada, el userId se interpreta como portfolioId.
        portfolioByUser = portfolioByUser == null ? Map.of() : Map.copyOf(portfolioByUser);
    }

    public boolean webhookSecretConfigured() {
        return !webhookSecret.isBlank();
    }

    /** API de ElevenLabs Conversational AI (llamadas salientes via Twilio). */
    public record ElevenLabs(
            @DefaultValue("https://api.elevenlabs.io") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String agentId,
            @DefaultValue("") String agentPhoneNumberId,
            @DefaultValue("10000") long timeoutMs,
            @DefaultValue("45") int ringingTimeoutSecs) {

        public ElevenLabs {
            apiKey = apiKey == null ? "" : apiKey;
            agentId = agentId == null ? "" : agentId;
            agentPhoneNumberId = agentPhoneNumberId == null ? "" : agentPhoneNumberId;
        }

        public boolean configured() {
            return !apiKey.isBlank() && !agentId.isBlank() && !agentPhoneNumberId.isBlank();
        }

        /** Nunca se imprime la clave (logs, actuator, excepciones). */
        @Override
        public String toString() {
            return "ElevenLabs[baseUrl=" + baseUrl + ", apiKey=" + (apiKey.isBlank() ? "<unset>" : "<redacted>")
                    + ", agentId=" + agentId + ", agentPhoneNumberId=" + agentPhoneNumberId + "]";
        }
    }

    /** Frenos contra abuso mientras la API no tiene autenticacion: cada llamada cuesta dinero y suena en un telefono. */
    public record Limits(
            @DefaultValue("60") int perNumberCooldownSeconds,
            @DefaultValue("20") int maxCallsPerHour) {
    }
}
