package com.wealth.mod5_telephony;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Freno contra abuso de POST /trigger-call: cada llamada cuesta dinero y hace sonar un telefono real, y la API aun no
 * tiene autenticacion. Dos limites EN MEMORIA (se pierden al reiniciar):
 * <ul>
 *   <li>enfriamiento por numero (por defecto 60 s): evita repetir llamadas al mismo telefono;</li>
 *   <li>tope global por hora (por defecto 20): acota el gasto si alguien automatiza la peticion.</li>
 * </ul>
 * TODO(auth): al activar autenticacion, limitar tambien por usuario y exigir que el telefono sea el del propio cliente.
 */
@Component
public class CallThrottle {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Duration cooldown;
    private final int maxPerHour;
    private final Map<String, Instant> lastByNumber = new HashMap<>();
    private final Deque<Instant> window = new ArrayDeque<>();

    public CallThrottle(Clock clock, TelephonyProperties properties) {
        this.clock = clock;
        this.cooldown = Duration.ofSeconds(properties.limits().perNumberCooldownSeconds());
        this.maxPerHour = properties.limits().maxCallsPerHour();
    }

    /** Reserva un hueco para llamar a {@code number}. Devuelve la marca de la reserva (para {@link #release}). */
    public synchronized Instant reserve(String number) {
        Instant now = clock.instant();
        purge(now);

        Instant last = lastByNumber.get(number);
        if (last != null) {
            Duration remaining = cooldown.minus(Duration.between(last, now));
            if (!remaining.isNegative() && !remaining.isZero()) {
                throw new TelephonyException(HttpStatus.TOO_MANY_REQUESTS,
                        "Ya se ha solicitado una llamada a este número hace poco. Inténtalo de nuevo en "
                                + (remaining.toSeconds() + 1) + " s.");
            }
        }
        if (window.size() >= maxPerHour) {
            throw new TelephonyException(HttpStatus.TOO_MANY_REQUESTS,
                    "Se ha alcanzado el máximo de llamadas por hora. Inténtalo más tarde.");
        }
        lastByNumber.put(number, now);
        window.addLast(now);
        return now;
    }

    /** Devuelve la reserva si la llamada no llego a lanzarse (error del proveedor): el cliente puede reintentar. */
    public synchronized void release(String number, Instant reservation) {
        if (reservation.equals(lastByNumber.get(number))) {
            lastByNumber.remove(number);
        }
        window.removeLastOccurrence(reservation);
    }

    private void purge(Instant now) {
        while (!window.isEmpty() && Duration.between(window.peekFirst(), now).compareTo(WINDOW) >= 0) {
            window.pollFirst();
        }
        lastByNumber.values().removeIf(t -> Duration.between(t, now).compareTo(cooldown) >= 0);
    }
}
