-- =============================================================================
-- V2 — Lotes Fiscales FIFO (Módulo 2: Optimizador Fiscal)
-- Cada fila representa un lote de compra de un instrumento financiero.
-- La columna available_quantity empieza igual que quantity y se va reduciendo
-- conforme se venden unidades (algoritmo FIFO en FifoCalculatorService).
-- =============================================================================

CREATE TABLE IF NOT EXISTS tax_lots (
    id                  BIGSERIAL       PRIMARY KEY,
    portfolio_id        VARCHAR(32)     NOT NULL,
    asset_isin          VARCHAR(12)     NOT NULL,
    purchase_date       DATE            NOT NULL,
    purchase_price      NUMERIC(19, 6)  NOT NULL CHECK (purchase_price > 0),
    quantity            NUMERIC(19, 6)  NOT NULL CHECK (quantity > 0),
    available_quantity  NUMERIC(19, 6)  NOT NULL CHECK (available_quantity >= 0),
    currency            VARCHAR(3)      NOT NULL DEFAULT 'EUR',
    broker_ref          VARCHAR(64),
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_available_lte_quantity
        CHECK (available_quantity <= quantity)
);

-- Índice principal para búsqueda FIFO por ISIN ordenada cronológicamente
CREATE INDEX IF NOT EXISTS idx_tax_lots_portfolio_isin_date
    ON tax_lots (portfolio_id, asset_isin, purchase_date ASC);

-- Índice parcial para lotes con saldo disponible (evita escanear lotes agotados)
CREATE INDEX IF NOT EXISTS idx_tax_lots_open
    ON tax_lots (portfolio_id, asset_isin, purchase_date ASC)
    WHERE available_quantity > 0;

COMMENT ON TABLE  tax_lots IS 'Lotes de compra para el cálculo FIFO de plusvalías/minusvalías (Módulo 2).';
COMMENT ON COLUMN tax_lots.available_quantity IS 'Cantidad pendiente de vender; se decrementa en cada venta FIFO parcial o total.';
COMMENT ON COLUMN tax_lots.purchase_price IS 'Precio unitario de adquisición en la moneda indicada.';
