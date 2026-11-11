package com.wealth.mod5_telephony;

import com.wealth.ledger.jev.JevLedgerAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WebVoiceServiceTest {

    private static TelephonyProperties props(String apiKey, String agentId) {
        return new TelephonyProperties(
                new TelephonyProperties.ElevenLabs("http://127.0.0.1:9", apiKey, agentId, "", 1000, 45),
                "", new TelephonyProperties.Limits(60, 20), Map.of("CLI-001", "PF-001"));
    }

    @Test
    void withoutCredentialsIs503AndLedgerIsNotTouched() {
        JevLedgerAdapter ledger = mock(JevLedgerAdapter.class);
        WebVoiceService service = new WebVoiceService(props("", "agent_x"), ledger, RestClient.builder());
        assertThatThrownBy(() -> service.startSession("CLI-001"))
                .isInstanceOf(TelephonyException.class)
                .extracting(e -> ((TelephonyException) e).status())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(ledger);
    }

    @Test
    void sessionsPerHourAreCapped() {
        JevLedgerAdapter ledger = mock(JevLedgerAdapter.class);
        // Proveedor inalcanzable (puerto 9): cada intento reserva hueco y falla con 502; al llegar al tope, 429.
        WebVoiceService service = new WebVoiceService(props("k", "agent_x"), ledger, RestClient.builder());
        for (int i = 0; i < WebVoiceService.MAX_SESSIONS_PER_HOUR; i++) {
            assertThatThrownBy(() -> service.startSession("CLI-001"))
                    .extracting(e -> ((TelephonyException) e).status())
                    .isEqualTo(HttpStatus.BAD_GATEWAY);
        }
        assertThatThrownBy(() -> service.startSession("CLI-001"))
                .extracting(e -> ((TelephonyException) e).status())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
