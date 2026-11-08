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

/**
 * Lee el estado actual de la cartera desde OpenJEV (solo lectura; el ledger es inmutable).
 * TODO(PostgreSQL): de momento carga el snapshot del mock JSON; al conectar la BBDD se
 * sustituye {@link #readSnapshot()} manteniendo el mapeo a {@link Portfolio}.
 */
@Component
public class JevLedgerAdapter {

    private final ObjectMapper objectMapper;
    private final Path snapshotFile;

    public JevLedgerAdapter(ObjectMapper objectMapper, @Value("${wealth.ledger.mock-file}") String snapshotFile) {
        this.objectMapper = objectMapper;
        this.snapshotFile = Path.of(snapshotFile);
    }

    public Portfolio loadPortfolio(String portfolioId) {
        Snapshot snapshot = readSnapshot();
        if (!snapshot.client().portfolioId().equals(portfolioId)) {
            throw new NoSuchElementException("Portfolio not found in ledger snapshot: " + portfolioId);
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
                    AssetClass.valueOf(balance.assetClass()), balance.quantity(), quote.price(), balance.currency()));
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

    private Snapshot readSnapshot() {
        try {
            return objectMapper.readValue(Files.readString(snapshotFile), Snapshot.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read ledger snapshot " + snapshotFile.toAbsolutePath(), e);
        }
    }

    private static void requireBaseCurrency(String currency, String base, String what) {
        if (!base.equals(currency)) {
            throw new IllegalStateException(
                    "Multi-currency not supported yet (no FX): " + what + " is " + currency + ", base is " + base);
        }
    }
}
