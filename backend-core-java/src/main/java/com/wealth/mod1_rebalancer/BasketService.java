package com.wealth.mod1_rebalancer;

import com.wealth.ledger.jev.JevLedgerAdapter;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Agrupaciones de fondos (cestas / model portfolios) de cada cartera. Se definen en application.yml
 * (wealth.baskets.by-portfolio.&lt;portfolioId&gt;.&lt;basketId&gt;): nombre, descripcion y pesos RELATIVOS por fondo,
 * que aqui se normalizan a suma 1 ("1, 1, 1" = tres fondos al 33,3 %). Solo lectura: la definicion de cestas no la
 * edita el cliente. Los fondos de una cesta deben existir en la cartera (y no puede incluir el efectivo).
 * TODO(PostgreSQL): mover la definicion a una tabla por cartera al activar la BBDD.
 */
@Service
public class BasketService {

    private static final int WEIGHT_SCALE = 6;

    @ConfigurationProperties(prefix = "wealth.baskets")
    public record Properties(Map<String, Map<String, Definition>> byPortfolio) {
        public Properties {
            byPortfolio = byPortfolio == null ? Map.of() : byPortfolio;
        }
    }

    /** Definicion tal cual esta en la configuracion (pesos relativos sin normalizar). */
    public record Definition(String name, String description, Map<String, BigDecimal> weights) {
    }

    private final Properties properties;
    private final JevLedgerAdapter ledger;

    public BasketService(Properties properties, JevLedgerAdapter ledger) {
        this.properties = properties;
        this.ledger = ledger;
    }

    /** Cestas de la cartera, en el orden de la configuracion. 404 si la cartera no existe. */
    public List<Basket> list(String portfolioId) {
        Portfolio portfolio = ledger.loadPortfolio(portfolioId);
        Map<String, Definition> defs = properties.byPortfolio().getOrDefault(portfolioId, Map.of());
        List<Basket> baskets = new ArrayList<>();
        defs.forEach((id, def) -> baskets.add(toBasket(portfolio, id, def)));
        return baskets;
    }

    /** Una cesta concreta (404 si no existe en esa cartera). */
    public Basket find(String portfolioId, String basketId) {
        return list(portfolioId).stream()
                .filter(b -> b.id().equals(basketId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "Basket not found: " + basketId + " (portfolio " + portfolioId + ")"));
    }

    private static Basket toBasket(Portfolio portfolio, String id, Definition def) {
        if (def.weights() == null || def.weights().isEmpty()) {
            throw new IllegalStateException("Basket " + id + " has no funds");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : def.weights().entrySet()) {
            AssetPosition position = portfolio.positions().stream()
                    .filter(p -> p.instrumentId().equals(e.getKey())).findFirst().orElseThrow(() ->
                            new IllegalStateException("Basket " + id + " references " + e.getKey() + ", not in the portfolio"));
            if (position.isCash()) {
                throw new IllegalStateException("Basket " + id + " cannot include cash (" + e.getKey() + ")");
            }
            if (e.getValue() == null || e.getValue().signum() < 0) {
                throw new IllegalStateException("Basket " + id + " has an invalid weight for " + e.getKey());
            }
            sum = sum.add(e.getValue());
        }
        if (sum.signum() <= 0) {
            throw new IllegalStateException("Basket " + id + " weights must not all be zero");
        }
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : def.weights().entrySet()) {
            normalized.put(e.getKey(), e.getValue().divide(sum, WEIGHT_SCALE, RoundingMode.HALF_EVEN));
        }
        return new Basket(id, def.name() == null ? id : def.name(), def.description() == null ? "" : def.description(),
                normalized);
    }
}
