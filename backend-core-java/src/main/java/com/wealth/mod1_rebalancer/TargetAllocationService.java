package com.wealth.mod1_rebalancer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pesos objetivo por cartera (instrumentId -> peso, suma 1). El efectivo es un peso mas
 * con clave CASH:&lt;moneda&gt;.
 * <p>
 * Los valores por defecto vienen de application.yml (wealth.rebalancer.targets). El cliente puede
 * sobrescribirlos con {@link #update}; la sobrescritura vive EN MEMORIA (se pierde al reiniciar).
 * TODO(PostgreSQL): persistir las metas por cartera en una tabla (migracion Flyway nueva) al activar la BBDD.
 */
@Service
public class TargetAllocationService {

    private static final BigDecimal SUM_TOLERANCE = new BigDecimal("0.000001");

    @ConfigurationProperties(prefix = "wealth.rebalancer")
    public record Properties(Map<String, Map<String, BigDecimal>> targets) {
        public Properties {
            targets = targets == null ? Map.of() : targets;
        }
    }

    private final Properties properties;
    private final Map<String, Map<String, BigDecimal>> overrides = new ConcurrentHashMap<>();

    public TargetAllocationService(Properties properties) {
        this.properties = properties;
    }

    /** Meta vigente: la del cliente si la ha modificado, si no la configurada por defecto. */
    public Map<String, BigDecimal> targetsFor(String portfolioId) {
        Map<String, BigDecimal> weights = overrides.get(portfolioId);
        if (weights == null) {
            weights = defaultsFor(portfolioId);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(weights));
    }

    /**
     * Guarda la nueva meta. Debe cubrir exactamente los mismos instrumentos que la meta por defecto
     * (no se pueden anadir instrumentos desde aqui), con pesos &gt;= 0 que sumen 1.
     */
    public Map<String, BigDecimal> update(String portfolioId, Map<String, BigDecimal> newWeights) {
        Map<String, BigDecimal> defaults = defaultsFor(portfolioId);
        if (newWeights == null || !newWeights.keySet().equals(defaults.keySet())) {
            throw new IllegalArgumentException("Target must define exactly these instruments: " + defaults.keySet());
        }
        BigDecimal sum = BigDecimal.ZERO;
        Map<String, BigDecimal> ordered = new LinkedHashMap<>();
        for (String instrumentId : defaults.keySet()) { // conserva el orden de la configuracion
            BigDecimal weight = newWeights.get(instrumentId);
            if (weight == null || weight.signum() < 0 || weight.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Weight for " + instrumentId + " must be between 0 and 1: " + weight);
            }
            sum = sum.add(weight);
            ordered.put(instrumentId, weight);
        }
        if (sum.subtract(BigDecimal.ONE).abs().compareTo(SUM_TOLERANCE) > 0) {
            throw new IllegalArgumentException("Target weights must sum to 1 but sum to " + sum);
        }
        overrides.put(portfolioId, ordered);
        return targetsFor(portfolioId);
    }

    /** Vuelve a la meta por defecto de application.yml. */
    public Map<String, BigDecimal> reset(String portfolioId) {
        overrides.remove(portfolioId);
        return targetsFor(portfolioId);
    }

    private Map<String, BigDecimal> defaultsFor(String portfolioId) {
        Map<String, BigDecimal> weights = properties.targets().get(portfolioId);
        if (weights == null || weights.isEmpty()) {
            throw new NoSuchElementException("No target allocation configured for portfolio " + portfolioId);
        }
        return weights;
    }
}
