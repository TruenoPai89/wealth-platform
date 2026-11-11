
/**
 * pledge.ts — Tipos del Módulo 4 (pignoración / LTV).
 * Espejo de pledge_terms.py (servicio Python)
 * y de PledgeController / LtvMonitorService (Java).
 */

export type PledgeAssetClass =
  | "EQUITY"
  | "FIXED_INCOME"
  | "COMMODITY"
  | "CASH";

// Servicio Python: extracción con Claude

export interface ExtractedHaircut {
  clase: PledgeAssetClass;
  haircut: number;
  cita: string;
}

export interface ExtractedTerms {
  ltv_maximo: number | null;
  ltv_margin_call: number | null;
  plazo_reposicion_dias_habiles: number | null;
  haircuts: ExtractedHaircut[];
  cita_ltv_maximo: string | null;
  cita_margin_call: string | null;
  cita_plazo: string | null;
}

export interface ExtractionResult {
  terminos: ExtractedTerms;
  intentos: number;
  desde_cache: boolean;
}

export interface Explanation {
  texto: string;
  fuente: "claude" | "plantilla";
  intentos: number;
}

// Backend Java: contrato y estado

export interface PledgeContractRequest {
  contractId?: string;
  loanId?: string;
  loanBalance: number;
  ltvMax: number;
  ltvMarginCall: number;
  cureDays: number | null;
  haircuts: Partial<Record<PledgeAssetClass, number>>;
}

export type PledgeZone =
  | "OK"
  | "LIMIT_EXCEEDED"
  | "MARGIN_CALL";

export interface SaleAction {
  marketValueToSell: number;
  available: number;
  feasible: boolean;
}

export interface PledgeStatus {
  portfolioId: string;
  contractId: string;
  loanId: string;
  equityShock: number;
  marketValue: number;
  collateralValue: number;
  loanBalance: number;
  ltv: number;
  ltvMax: number;
  ltvMarginCall: number;
  zone: PledgeZone;
  cureDays: number | null;
  collateralDropToMarginCall: number;

  /** null = ni con una caída del 100 % de la renta variable. */
  equityDropToMarginCall: number | null;

  actions: {
    repayCash: number;
    addCollateral: number;
    sellByClass: Partial<Record<PledgeAssetClass, SaleAction>>;
  };

  horizonDays: number;
  collateralVolatility: number;
  marginCallProbability: number;

  positions: {
    instrumentId: string;
    name: string;
    assetClass: PledgeAssetClass;
    marketValue: number;
    haircut: number;
    collateralValue: number;
  }[];
}

