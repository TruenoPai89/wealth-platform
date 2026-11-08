package com.wealth.ledger.jev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealth.ledger.jev.JournalEntryModel.CashBalance;
import com.wealth.ledger.jev.JournalEntryModel.PositionBalance;
import com.wealth.ledger.jev.JournalEntryModel.PriceQuote;
import com.wealth.ledger.jev.JournalEntryModel.Snapshot;
import com.wealth.shared.domain.AssetClass;
import com.wealth.shared.domain.AssetPosition;
import com.wealth.shared.domain.Portfolio;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lee el estado actual de la cartera desde OpenJEV (solo lectura; el ledger es inmutable).
 * TODO(PostgreSQL): de momento carga el snapshot del mock JSON; al conectar la BBDD se
 * sustituye {@link #readSnapshot()} manteniendo el mapeo a {@link Portfolio}.
 */
@Component
public class JevLedgerAdapter {

    private final ObjectMapper objectMapper;
    private final Path snapshotFile;
    /**
     * Estado de cartera tras ejecutar rebalanceos (portfolioId -> cartera). Mientras el ledger se lee de un JSON
     * estatico, esto emula lo que haria el ledger real: deducir el estado sumando los asientos ya registrados.
     * TODO(PostgreSQL): desaparece al leer el estado de los asientos persistidos.
     */
    private final Map<String, Portfolio> postedState = new ConcurrentHashMap<>();

    public JevLedgerAdapter(ObjectMapper objectMapper, @Value("${wealth.ledger.mock-file}") String snapshotFile) {
        this.objectMapper = objectMapper;
        this.snapshotFile = Path.of(snapshotFile);
    }

    public Portfolio loadPortfolio(String portfolioId) {
        Snapshot snapshot = readSnapshot();
        if (!snapshot.client().portfolioId().equals(portfolioId)) {
            throw new NoSuchElementException("Portfolio not found in ledger snapshot: " + portfolioId);
        }
        Portfolio posted = postedState.get(portfolioId);
        if (posted != null) {
            return posted;
        }
        String base = snapshot.client().baseCurrency();

        Map<String, PriceQuote> prices = new HashMap<>();
        for (PriceQuote quote : snapshot.prices()) {
            prices.put(quote.instrumentId(), quote);
        }

        List<AssetPosition> positions = new ArrayList<>();
        for (PositionBalance balance : snapshot.positions()) {
            requireBaseCurrency(balance.currency(), base, balance.instrumentId());
            PriceQuote quote = prices.get(balance.instrumentId());
            if (quote == null) {
                throw new IllegalStateException("No price for instrument " + balance.instrumentId());
            }
            requireBaseCurrency(quote.currency(), base, balance.instrumentId());
            positions.add(new AssetPosition(balance.instrumentId(), balance.isin(), balance.name(),
                    AssetClass.valueOf(balance.assetClass()), balance.quantity(), quote.price(), balance.currency(),
                    costBasisOf(balance)));
        }

        // El efectivo es una posicion mas; siempre existe la linea en moneda base (aunque sea 0).
        BigDecimal cash = BigDecimal.ZERO;
        for (CashBalance balance : snapshot.cash()) {
            requireBaseCurrency(balance.currency(), base, "cash");
            cash = cash.add(balance.amount());
        }
        positions.add(AssetPosition.cash(base, cash));

        return new Portfolio(snapshot.client().portfolioId(), snapshot.client().clientId(), base,
                snapshot.asOf(), positions);
    }

    /** Publica el estado de la cartera tras ejecutar un rebalanceo: las siguientes lecturas ya lo reflejan. */
    public void applyPostedState(Portfolio portfolio) {
        postedState.put(portfolio.portfolioId(), portfolio);
    }

    /** Vuelve al estado del snapshot (para demos y tests). */
    public void resetPostedState(String portfolioId) {
        postedState.remove(portfolioId);
    }

    private Snapshot readSnapshot() {
        try {
            return objectMapper.readValue(Files.readString(snapshotFile), Snapshot.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read ledger snapshot " + snapshotFile.toAbsolutePath(), e);
        }
    }

    /**
     * Coste base TOTAL en moneda base: bookCostTotal (dato nativo del ledger) o acquisitionPrice * cantidad.
     * Si vienen ambos tienen que cuadrar; el precio unitario va redondeado a centimos, de ahi la tolerancia.
     */
    private static BigDecimal costBasisOf(PositionBalance balance) {
        BigDecimal fromUnitPrice = balance.acquisitionPrice() == null
                ? null : balance.acquisitionPrice().multiply(balance.quantity());
        BigDecimal total = balance.bookCostTotal();
        if (total == null && fromUnitPrice == null) {
            throw new IllegalStateException(
                    "No cost basis (bookCostTotal / acquisitionPrice) for instrument " + balance.instrumentId());
        }
        if (total == null) {
            return fromUnitPrice;
        }
        if (fromUnitPrice != null) {
            BigDecimal tolerance = balance.quantity().multiply(new BigDecimal("0.005")).add(new BigDecimal("0.01"));
            if (fromUnitPrice.subtract(total).abs().compareTo(tolerance) > 0) {
                throw new IllegalStateException("Inconsistent cost basis for " + balance.instrumentId()
                        + ": acquisitionPrice * quantity = " + fromUnitPrice + " but bookCostTotal = " + total);
            }
        }
        return total;
    }

    private static void requireBaseCurrency(String currency, String base, String what) {
        if (!base.equals(currency)) {
            throw new IllegalStateException(
                    "Multi-currency not supported yet (no FX): " + what + " is " + currency + ", base is " + base);
        }
    }
}
