package com.wealth.mod1_rebalancer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Pesos objetivo por cartera (instrumentId -> peso, suma 1). El efectivo es un peso mas
 * con clave CASH:&lt;moneda&gt;. De momento se leen de application.yml (wealth.rebalancer.targets).
 */
@Service
public class TargetAllocationService {

    @ConfigurationProperties(prefix = "wealth.rebalancer")
    public record Properties(Map<String, Map<String, BigDecimal>> targets) {
        public Properties {
            targets = targets == null ? Map.of() : targets;
        }
    }

    private final Properties properties;

    public TargetAllocationService(Properties properties) {
        this.properties = properties;
    }

    public Map<String, BigDecimal> targetsFor(String portfolioId) {
        Map<String, BigDecimal> weights = properties.targets().get(portfolioId);
        if (weights == null || weights.isEmpty()) {
            throw new NoSuchElementException("No target allocation configured for portfolio " + portfolioId);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(weights));
    }
}
