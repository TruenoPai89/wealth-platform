package com.wealth.mod5_telephony;

import com.wealth.ledger.jev.JevLedgerAdapter;
import com.wealth.mod1_rebalancer.Basket;
import com.wealth.mod1_rebalancer.BasketService;
import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Llamada de voz con el asesor de IA: lanza la llamada saliente (ElevenLabs + Twilio), consulta su estado y construye
 * el contexto ultracompacto de la cartera que el agente pide antes de hablar.
 */
@Service
public class TelephonyService {

    private static final Logger log = LoggerFactory.getLogger(TelephonyService.class);

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern CONVERSATION_ID = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Resultado de lanzar la llamada: la UI consulta el estado con {@code conversationId}. */
    public record CallStarted(String conversationId, String status) {
    }

    /** Estado normalizado para la UI: dialing | in_call | ended | failed. */
    public record CallStatus(String conversationId, String status) {
    }

    private final TelephonyProperties properties;
    private final ElevenLabsClient elevenLabs;
    private final CallThrottle throttle;
    private final JevLedgerAdapter ledger;
    private final BasketService baskets;

    public TelephonyService(TelephonyProperties properties, ElevenLabsClient elevenLabs, CallThrottle throttle,
                            JevLedgerAdapter ledger, BasketService baskets) {
        this.properties = properties;
        this.elevenLabs = elevenLabs;
        this.throttle = throttle;
        this.ledger = ledger;
        this.baskets = baskets;
    }

    // ── Llamada saliente ──

    public CallStarted triggerCall(String rawPhone, String userId) {
        requireProviderConfigured();
        String phone = normalizePhone(rawPhone);
        String portfolioId = resolvePortfolioId(userId);
        ledger.loadPortfolio(portfolioId); // 404 si el usuario no existe: no se llama a nadie por un usuario inventado

        Instant reservation = throttle.reserve(phone);
        try {
            ElevenLabsClient.OutboundCallResponse response = elevenLabs.startOutboundCall(phone, userId);
            log.info("Llamada de voz solicitada: conversationId={}, numero={}", response.conversationId(), mask(phone));
            return new CallStarted(response.conversationId(), "dialing");
        } catch (RuntimeException e) {
            throttle.release(phone, reservation); // no llego a sonar: el cliente puede reintentar
            throw e;
        }
    }

    public CallStatus callStatus(String conversationId) {
        requireProviderConfigured();
        if (conversationId == null || !CONVERSATION_ID.matcher(conversationId).matches()) {
            throw new IllegalArgumentException("Identificador de llamada no válido.");
        }
        return new CallStatus(conversationId, toUiStatus(elevenLabs.conversationStatus(conversationId)));
    }

    /** initiated -> dialing; in-progress -> in_call; processing/done -> ended; failed -> failed. */
    static String toUiStatus(String providerStatus) {
        return switch (providerStatus) {
            case "in-progress" -> "in_call";
            case "processing", "done" -> "ended";
            case "failed" -> "failed";
            default -> "dialing"; // initiated y cualquier estado futuro desconocido
        };
    }

    // ── Contexto para el agente de voz ──

    /** Comprueba la cabecera secreta del webhook. Sin secreto configurado se permite (desarrollo local). */
    public void verifyWebhookSecret(String provided) {
        if (!properties.webhookSecretConfigured()) {
            return;
        }
        byte[] expected = properties.webhookSecret().getBytes(StandardCharsets.UTF_8);
        byte[] actual = (provided == null ? "" : provided).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) { // comparacion en tiempo constante
            throw new TelephonyException(HttpStatus.UNAUTHORIZED, "Webhook no autorizado.");
        }
    }

    public AgentContext agentContext(String userId) {
        Portfolio portfolio = ledger.loadPortfolio(resolvePortfolioId(userId));
        BigDecimal total = portfolio.totalValue();

        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal riskValue = BigDecimal.ZERO;
        for (AssetPosition p : portfolio.positions()) {
            if (p.isCash()) {
                cash = cash.add(p.marketValue());
            } else if (p.assetClass() == AssetClass.EQUITY || p.assetClass() == AssetClass.COMMODITY) {
                riskValue = riskValue.add(p.marketValue());
            }
        }
        BigDecimal riskPct = pct(riskValue, total);

        List<AgentContext.PositionLine> positions = portfolio.positions().stream()
                .filter(p -> !p.isCash())
                .sorted(Comparator.comparing(AssetPosition::marketValue).reversed())
                .map(p -> new AgentContext.PositionLine(p.name(), pct(p.marketValue(), total), eur(p.marketValue())))
                .toList();

        Map<String, String> nameOf = portfolio.positions().stream()
                .collect(Collectors.toMap(AssetPosition::instrumentId, AssetPosition::name));
        List<AgentContext.BasketLine> basketLines = baskets.list(portfolio.portfolioId()).stream()
                .map(b -> new AgentContext.BasketLine(b.id(), b.name(), mix(b, nameOf)))
                .toList();

        return new AgentContext(userId, portfolio.portfolioId(), portfolio.baseCurrency(), portfolio.asOf().toString(),
                eur(total), cash.setScale(2, RoundingMode.HALF_UP), pct(cash, total),
                riskLevel(riskPct), riskPct, positions, basketLines);
    }

    /** Indicador derivado del peso de renta variable + materias primas. NO es el perfil MiFID del cliente. */
    static String riskLevel(BigDecimal riskAssetsPct) {
        double v = riskAssetsPct.doubleValue();
        if (v < 20) return "conservador";
        if (v < 45) return "moderado";
        if (v < 70) return "dinámico";
        return "agresivo";
    }

    // ── Utilidades ──

    private String resolvePortfolioId(String userId) {
        if (userId == null || !SAFE_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("Identificador de usuario no válido.");
        }
        return properties.portfolioByUser().getOrDefault(userId, userId);
    }

    private void requireProviderConfigured() {
        if (!properties.elevenlabs().configured()) {
            throw new TelephonyException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La llamada de voz no está configurada en el servidor (faltan las credenciales de ElevenLabs).");
        }
    }

    static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Indica un número de teléfono.");
        }
        String s = raw.replaceAll("[\\s().-]", "");
        if (s.startsWith("00")) {
            s = "+" + s.substring(2);
        }
        if (!E164.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "El teléfono debe estar en formato internacional, por ejemplo +34600123456.");
        }
        return s;
    }

    /** +34600123456 -> +34*******456: el numero completo no se escribe en los logs. */
    static String mask(String phone) {
        return phone.length() <= 6 ? "***" : phone.substring(0, 3) + "*".repeat(phone.length() - 6)
                + phone.substring(phone.length() - 3);
    }

    private static String mix(Basket basket, Map<String, String> nameOf) {
        return basket.weights().entrySet().stream()
                .map(e -> nameOf.getOrDefault(e.getKey(), e.getKey()) + " "
                        + e.getValue().multiply(HUNDRED).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros()
                        .toPlainString() + "%")
                .collect(Collectors.joining(", "));
    }

    private static BigDecimal pct(BigDecimal part, BigDecimal total) {
        return total.signum() <= 0 ? BigDecimal.ZERO.setScale(1)
                : part.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal eur(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP);
    }
}
