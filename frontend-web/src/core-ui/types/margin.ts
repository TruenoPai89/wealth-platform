/**
 * margin.ts — Tipos del análisis de margin call (Módulo 4).
 * Espejo de PledgeConfigDto / MarginRiskAnalysisDto (backend Java) y de POST /api/v1/margin/analyze.
 *
 * Todas las proporciones son FRACCIONES (0.045 = 4,5 %); los importes, EUR.
 */

import type { PledgeAssetClass, PledgeZone } from "@/core-ui/types/pledge";

/** Envoltorio de respuesta del endpoint: { data, error }. */
export interface ApiEnvelope<T> {
  data: T | null;
  error: string | null;
}

// ─── Petición ─────────────────────────────────────────────────────────────────

export interface CollateralItem {
  instrumentId: string;
  /** Valor de MERCADO aportado como garantía (EUR). */
  amount: number;
}

export interface PledgeConfigRequest {
  /** Si falta, el backend usa PF-001. */
  portfolioId?: string;
  collateral: CollateralItem[];
  loanAmount: number;
  /** 0.045 = 4,5 % anual. */
  interestRate: number;
  termYears: number;
  /** LTV que dispara el margin call (0.70 = 70 %). */
  marginCallLtv: number;
  /** Opcional: descuento por clase. Si falta, el backend usa los del contrato de ejemplo. */
  haircuts?: Partial<Record<PledgeAssetClass, number>>;
}

// ─── Respuesta ────────────────────────────────────────────────────────────────

export type SecurityLevel = "LOW" | "MEDIUM" | "HIGH";

export interface MarginMetrics {
  loanAmount: number;
  pledgedMarketValue: number;
  /** Garantía computable (tras descuentos). */
  collateralValue: number;
  /** Garantía mínima para no estar en margin call (deuda / umbral). */
  requiredCollateral: number;
  /** collateralValue - requiredCollateral (negativo si ya hay margin call). */
  collateralSurplus: number;
  ltv: number;
  marginCallLtv: number;
  /** Umbral - LTV, en puntos de LTV (0.18 = 18 puntos). */
  ltvHeadroom: number;
  /** Caída de la garantía que dispara el margin call (negativa si ya se superó). */
  distanceToMarginCall: number;
  zone: PledgeZone;
  collateralVolatility: number;
  firstYearInterest: number;
  totalInterestOverTerm: number;
}

export interface FundBreakdown {
  instrumentId: string;
  name: string;
  assetClass: PledgeAssetClass;
  pledgedAmount: number;
  positionValue: number;
  haircut: number;
  collateralValue: number;
  /** Peso en la garantía computable. */
  weight: number;
  annualVolatility: number;
  /** Parte de la varianza de la garantía (suman 1). */
  riskContribution: number;
  oneSigmaLoss: number;
  /** Caída de ESTE fondo (solo él) que dispara el margin call; null si ni al 100 %. */
  standaloneDropToMarginCall: number | null;
}

export interface HorizonProbability {
  horizonDays: number;
  probability: number;
  projectedLoan: number;
}

export interface StressScenario {
  drop: number;
  collateralValue: number;
  ltv: number;
  zone: PledgeZone;
  topUpNeeded: number;
  repayNeeded: number;
}

export interface CurvePoint {
  drop: number;
  collateralValue: number;
  ltv: number;
}

export type RecommendationCode =
  | "CURE_MARGIN_CALL"
  | "RESTORE_BUFFER"
  | "CONCENTRATION"
  | "ADD_LOW_VOLATILITY_FUND"
  | "NO_ACTION_NEEDED";

export interface Recommendation {
  code: RecommendationCode;
  message: string;
  figures: Record<string, number>;
}

export interface MarginRiskAnalysis {
  portfolioId: string;
  metrics: MarginMetrics;
  funds: FundBreakdown[];
  marginCallProbability: HorizonProbability[];
  stressTest: StressScenario[];
  drawdownCurve: CurvePoint[];
  securityLevel: SecurityLevel;
  recommendations: Recommendation[];
  assumptions: string[];
}
