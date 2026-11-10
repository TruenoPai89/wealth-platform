package com.wealth.mod4_pledge;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Almacén en memoria de contratos de pignoración, uno por cartera.
 * <p>
 * Igual que el resto del backend mientras no haya PostgreSQL: se pierde al reiniciar.
 * (V3__add_pledge_contracts.sql existe en el repo pero está vacío: queda para cuando haya base de datos.)
 */
@Component
public class PledgeContractStore {

    private final Map<String, PledgeContractEntity> byPortfolio = new ConcurrentHashMap<>();

    public PledgeContractEntity save(PledgeContractEntity contract) {
        byPortfolio.put(contract.portfolioId(), contract);
        return contract;
    }

    public Optional<PledgeContractEntity> find(String portfolioId) {
        return Optional.ofNullable(byPortfolio.get(portfolioId));
    }
}