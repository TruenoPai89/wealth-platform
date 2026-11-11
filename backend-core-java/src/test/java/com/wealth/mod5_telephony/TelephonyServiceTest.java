package com.wealth.mod5_telephony;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelephonyServiceTest {

    @Test
    void normalizesPhoneToE164() {
        assertThat(TelephonyService.normalizePhone("+34 600 123 456")).isEqualTo("+34600123456");
        assertThat(TelephonyService.normalizePhone("0034-600-123-456")).isEqualTo("+34600123456");
        assertThat(TelephonyService.normalizePhone("+1 (415) 555-2671")).isEqualTo("+14155552671");
    }

    @Test
    void rejectsPhonesThatAreNotInternational() {
        for (String bad : new String[]{null, "", "  ", "600123456", "+0123456789", "+34abc", "+34 12", "+3460012345678901"}) {
            assertThatThrownBy(() -> TelephonyService.normalizePhone(bad))
                    .as("telefono %s", bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void masksPhoneForLogs() {
        assertThat(TelephonyService.mask("+34600123456")).isEqualTo("+34******456");
        assertThat(TelephonyService.mask("+34600123456")).doesNotContain("600123");
    }

    @Test
    void mapsProviderStatusToUiStatus() {
        assertThat(TelephonyService.toUiStatus("initiated")).isEqualTo("dialing");
        assertThat(TelephonyService.toUiStatus("in-progress")).isEqualTo("in_call");
        assertThat(TelephonyService.toUiStatus("processing")).isEqualTo("ended");
        assertThat(TelephonyService.toUiStatus("done")).isEqualTo("ended");
        assertThat(TelephonyService.toUiStatus("failed")).isEqualTo("failed");
        assertThat(TelephonyService.toUiStatus("something-new")).isEqualTo("dialing");
    }

    @Test
    void derivesRiskLevelFromRiskyWeight() {
        assertThat(TelephonyService.riskLevel(new BigDecimal("10.0"))).isEqualTo("conservador");
        assertThat(TelephonyService.riskLevel(new BigDecimal("30.0"))).isEqualTo("moderado");
        assertThat(TelephonyService.riskLevel(new BigDecimal("50.0"))).isEqualTo("dinámico");
        assertThat(TelephonyService.riskLevel(new BigDecimal("65.8"))).isEqualTo("dinámico");
        assertThat(TelephonyService.riskLevel(new BigDecimal("70.0"))).isEqualTo("agresivo"); // el umbral es inclusivo
    }

    // ── CallThrottle con reloj controlable ──

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-11T10:00:00Z"));

        void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private static CallThrottle throttle(MutableClock clock, int cooldownSeconds, int maxPerHour) {
        TelephonyProperties props = new TelephonyProperties(
                new TelephonyProperties.ElevenLabs(null, null, null, null, 1000, 45), "",
                new TelephonyProperties.Limits(cooldownSeconds, maxPerHour), null);
        return new CallThrottle(clock, props);
    }

    @Test
    void throttleBlocksSameNumberUntilCooldownPasses() {
        MutableClock clock = new MutableClock();
        CallThrottle throttle = throttle(clock, 60, 100);

        throttle.reserve("+34600000001");
        assertThatThrownBy(() -> throttle.reserve("+34600000001"))
                .isInstanceOfSatisfying(TelephonyException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        throttle.reserve("+34600000002"); // otro numero no se ve afectado
        clock.advance(Duration.ofSeconds(61));
        throttle.reserve("+34600000001"); // pasado el enfriamiento, vuelve a poder
    }

    @Test
    void throttleCapsCallsPerHourAndRecovers() {
        MutableClock clock = new MutableClock();
        CallThrottle throttle = throttle(clock, 1, 3);

        for (int i = 0; i < 3; i++) {
            throttle.reserve("+3460000010" + i);
        }
        assertThatThrownBy(() -> throttle.reserve("+34600000199")).isInstanceOf(TelephonyException.class);

        clock.advance(Duration.ofMinutes(61));
        throttle.reserve("+34600000199");
    }

    @Test
    void releasedReservationAllowsImmediateRetry() {
        MutableClock clock = new MutableClock();
        CallThrottle throttle = throttle(clock, 60, 100);

        Instant reservation = throttle.reserve("+34600000003");
        throttle.release("+34600000003", reservation);

        throttle.reserve("+34600000003");
    }

    @Test
    void apiKeyNeverAppearsInToString() {
        TelephonyProperties.ElevenLabs config = new TelephonyProperties.ElevenLabs(
                "https://api.elevenlabs.io", "sk_super_secret", "agent", "phnum", 1000, 45);
        assertThat(config.toString()).doesNotContain("sk_super_secret").contains("<redacted>");
    }
}
