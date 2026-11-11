package com.wealth.mod4_pledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Volatilidad anual por fondo y correlaciones entre fondos, para el análisis de margin call.
 * <p>
 * IMPORTANTE: son SUPUESTOS (fichero {@code fund_risk_profile.json}), no estimaciones con series históricas: el
 * simulador solo trae un precio por fondo. Cuando haya una fuente de precios históricos, este es el único punto
 * que hay que sustituir.
 * <p>
 * Si un fondo no está en el fichero (o el fichero falta) se usa una volatilidad por clase de activo y una
 * correlación genérica (0,90 dentro de la misma clase, 0,10 entre clases distintas, 0 con el efectivo).
 */
@Component
public class FundRiskProfile {

    private static final Logger LOG = LoggerFactory.getLogger(FundRiskProfile.class);

    /** Volatilidad por clase de activo cuando no hay dato del fondo (mismas hipótesis que LtvMonitorService). */
    private static final Map<AssetClass, Double> CLASS_VOLATILITY = LtvMonitorService.ANNUAL_VOLATILITY;

    private static final double SAME_CLASS_CORRELATION = 0.90;
    private static final double CROSS_CLASS_CORRELATION = 0.10;

    private final Map<String, Double> volatilities;
    private final Map<String, Double> correlations;

    @Autowired
    public FundRiskProfile(ObjectMapper objectMapper,
                           @Value("${wealth.margin.risk-profile-file:}") String file) {
        Map<String, Double> vols = new HashMap<>();
        Map<String, Double> corr = new HashMap<>();
        if (file != null && !file.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(Files.readString(Path.of(file)));
                root.path("funds").fields().forEachRemaining(e ->
                        vols.put(e.getKey(), e.getValue().path("annualVolatility").asDouble()));
                root.path("correlations").forEach(n ->
                        corr.put(key(n.path("a").asText(), n.path("b").asText()), n.path("rho").asDouble()));
            } catch (IOException | RuntimeException e) {
                LOG.warn("No se pudo leer {}: se usaran volatilidades por clase de activo ({})", file, e.getMessage());
                vols.clear();
                corr.clear();
            }
        }
        this.volatilities = Map.copyOf(vols);
        this.correlations = Map.copyOf(corr);
    }

    private FundRiskProfile(Map<String, Double> volatilities, Map<String, Double> correlations) {
        this.volatilities = Map.copyOf(volatilities);
        this.correlations = Map.copyOf(correlations);
    }

    /** Perfil explícito (tests). Las claves de correlación se pasan con {@link #key(String, String)}. */
    static FundRiskProfile of(Map<String, Double> volatilities, Map<String, Double> correlations) {
        return new FundRiskProfile(volatilities, correlations);
    }

    static String key(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** true si hay dato específico del fondo (no se usa el valor genérico de su clase). */
    public boolean hasFundData(AssetPosition position) {
        return volatilities.containsKey(position.instrumentId());
    }

    public double volatility(AssetPosition position) {
        if (position.isCash()) return 0.0;
        Double v = volatilities.get(position.instrumentId());
        return v != null ? v : CLASS_VOLATILITY.getOrDefault(position.assetClass(), 0.0);
    }

    public double correlation(AssetPosition a, AssetPosition b) {
        if (a.instrumentId().equals(b.instrumentId())) return 1.0;
        if (a.isCash() || b.isCash()) return 0.0;
        Double rho = correlations.get(key(a.instrumentId(), b.instrumentId()));
        if (rho != null) return rho;
        return a.assetClass() == b.assetClass() ? SAME_CLASS_CORRELATION : CROSS_CLASS_CORRELATION;
    }
}
