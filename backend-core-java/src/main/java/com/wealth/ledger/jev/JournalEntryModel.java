package com.wealth.ledger.jev;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Modelos del journal de la plataforma:
 * <ul>
 *   <li>{@link Snapshot}: "foto" de la cartera (saldos deducidos de los asientos, suma de debitos - creditos por cuenta
 *       ASSETS:CUSTODY:&lt;ISIN&gt; y ASSETS:CASH:&lt;moneda&gt;) mas precios de mercado, que llegan de fuente externa.
 *       Formato: bank-simulator-mocks/api-json-responses/client_portfolio_jev.json</li>
 *   <li>{@link JournalEntry}/{@link JournalBatch}: asientos de partida doble generados a partir de un plan de rebalanceo.</li>
 * </ul>
 * OpenJEV (System One) es el motor de decisiones y se consume por HTTP ({@link OpenJevClient}); no es la libreria contable.
 */
public final class JournalEntryModel {

    private JournalEntryModel() {
    }

    public record Snapshot(
            String schemaVersion,
            String source,
            Instant asOf,
            LedgerRef ledgerRef,
            Client client,
            List<CashBalance> cash,
            List<PositionBalance> positions,
            List<PriceQuote> prices) {
    }

    public record LedgerRef(String lastEntryId, long entryCount) {
    }

    public record Client(String clientId, String portfolioId, String baseCurrency) {
    }

    public record CashBalance(String currency, BigDecimal amount) {
    }

    public record PositionBalance(
            String instrumentId,
            String isin,
            String name,
            String assetClass,
            BigDecimal quantity,
            String currency,
            BigDecimal bookCostTotal,
            BigDecimal acquisitionPrice) {
    }

    public record PriceQuote(String instrumentId, BigDecimal price, String currency, LocalDate priceDate) {
    }

    // ---- Asientos de partida doble (salida del modulo 1 hacia el journal inmutable) ----

    /** CONTRIBUTION: ingreso de efectivo del cliente (aportacion); BUY / SELL: operaciones sobre fondos. */
    public enum EntryType { BUY, SELL, CONTRIBUTION }

    public enum LineSide { DEBIT, CREDIT }

    /** Una linea de asiento. quantity solo se informa en cuentas de custodia (participaciones); en caja es null. */
    public record JournalLine(String account, LineSide side, BigDecimal amount, BigDecimal quantity) {
    }

    /** Asiento balanceado (sum debitos = sum creditos). Inmutable: las correcciones son contra-asientos, nunca updates. */
    public record JournalEntry(
            String entryId,
            String reference,
            EntryType type,
            Instant bookedAt,
            String currency,
            List<JournalLine> lines) {

        public JournalEntry {
            lines = List.copyOf(lines);
        }

        public BigDecimal totalDebits() {
            return total(LineSide.DEBIT);
        }

        public BigDecimal totalCredits() {
            return total(LineSide.CREDIT);
        }

        public boolean isBalanced() {
            return totalDebits().compareTo(totalCredits()) == 0;
        }

        private BigDecimal total(LineSide side) {
            return lines.stream().filter(l -> l.side() == side)
                    .map(JournalLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** Conjunto de asientos generados por un plan de rebalanceo. netCashMovement = ventas - compras (cuenta de caja). */
    public record JournalBatch(
            String reference,
            String portfolioId,
            String currency,
            List<JournalEntry> entries,
            BigDecimal netCashMovement) {

        public JournalBatch {
            entries = List.copyOf(entries);
        }
    }
}
