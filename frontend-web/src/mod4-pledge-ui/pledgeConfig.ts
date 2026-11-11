/**
 * pledgeConfig.ts — Estado de la configuración manual de pignoración y su conversión a petición del backend.
 *
 * @module mod4-pledge-ui/pledgeConfig
 */

import type { RebalancePlan } from "@/core-ui/types/rebalancePlan";
import type { PledgeAssetClass } from "@/core-ui/types/pledge";
import type { PledgeConfigRequest } from "@/core-ui/types/margin";

/** Fondo de la cartera que se puede aportar como garantía (el efectivo no). */
export interface FundOption {
  instrumentId: string;
  name: string;
  assetClass: PledgeAssetClass;
  /** Valor de mercado de la posición (EUR). */
  marketValue: number;
}

export interface PledgeConfigState {
  /** instrumentId → importe de mercado aportado (EUR). Un fondo ausente no está en garantía. */
  collateral: Record<string, number>;
  loanAmount: number;
  /** Fracción anual (0.04 = 4 %). */
  interestRate: number;
  termYears: number;
  /** LTV de margin call (0.70 = 70 %). */
  marginCallLtv: number;
}

export const DEFAULT_PORTFOLIO_ID = "PF-001";

/** Fondos de la cartera a partir del plan de rebalanceo (sin efectivo). */
export function fundsFromPlan(plan: RebalancePlan): FundOption[] {
  return plan.positions
    .filter((p) => p.assetClass !== "CASH")
    .map((p) => ({
      instrumentId: p.instrumentId,
      name: p.name,
      assetClass: p.assetClass as PledgeAssetClass,
      marketValue: p.marketValue,
    }));
}

const round2 = (n: number) => Math.round(n * 100) / 100;

/**
 * Configuración inicial razonable: renta variable global + renta fija completas en garantía, crédito del 45 %
 * del valor aportado, 4 % a 5 años y margin call al 70 %. Si la cartera no tiene esos fondos, los dos
 * primeros.
 */
export function defaultConfig(funds: FundOption[]): PledgeConfigState {
  const preferred = funds.filter((f) => f.assetClass === "EQUITY").slice(0, 1)
    .concat(funds.filter((f) => f.assetClass === "FIXED_INCOME").slice(0, 1));
  const chosen = preferred.length > 0 ? preferred : funds.slice(0, 2);

  const collateral: Record<string, number> = {};
  chosen.forEach((f) => {
    collateral[f.instrumentId] = round2(f.marketValue);
  });
  const pledged = Object.values(collateral).reduce((a, b) => a + b, 0);

  return {
    collateral,
    loanAmount: Math.round((pledged * 0.45) / 1000) * 1000,
    interestRate: 0.04,
    termYears: 5,
    marginCallLtv: 0.7,
  };
}

/** Máximo del slider del crédito: algo por encima del valor aportado para poder simular un exceso. */
export function loanSliderMax(config: PledgeConfigState): number {
  const pledged = Object.values(config.collateral).reduce((a, b) => a + b, 0);
  return Math.max(10000, Math.ceil((pledged * 1.2) / 1000) * 1000);
}

/** Selección → petición; null si todavía no hay ningún fondo con importe. */
export function toRequest(config: PledgeConfigState, portfolioId = DEFAULT_PORTFOLIO_ID): PledgeConfigRequest | null {
  const collateral = Object.entries(config.collateral)
    .filter(([, amount]) => amount > 0)
    .map(([instrumentId, amount]) => ({ instrumentId, amount: round2(amount) }));
  if (collateral.length === 0) return null;

  return {
    portfolioId,
    collateral,
    loanAmount: config.loanAmount,
    interestRate: config.interestRate,
    termYears: config.termYears,
    marginCallLtv: config.marginCallLtv,
  };
}
