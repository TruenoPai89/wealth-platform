/**
 * irpf.ts — Tipos del Módulo 3 (simulador IRPF).
 * Espejo de los records de IrpfController.java / IrpfSimulationService.java.
 *
 * @module core-ui/types/irpf
 */

export interface CarryForward {
  originYear: number;
  amount: number;
}

export interface IrpfSituation {
  fiscalYear?: number;
  realizedGains: number;
  computableLosses: number;
  carryForward?: CarryForward[];
}

export interface IrpfSaleRequest {
  situation: IrpfSituation;
  isin: string;
  quantity: number;
  salePrice: number;
  /** ISO 8601 (yyyy-mm-dd). Opcional: por defecto, hoy. */
  saleDate?: string;
}

export interface IrpfResult {
  netBalance: number;
  carryForwardUsed: number;
  taxableBase: number;
  tax: number;
  averageRate: number;
  remainingCarryForward: { originYear: number; remaining: number }[];
}

export interface IrpfSaleResponse {
  fiscalYear: number;
  isin: string;
  quantity: number;
  salePrice: number;
  saleGainLoss: number;
  affectedLots: number;
  twoMonthRuleViolation: boolean;
  computableSaleResult: number;
  before: IrpfResult;
  after: IrpfResult;
  /** Negativo = la venta ahorra impuestos. */
  taxDelta: number;
  warning: string | null;
}

export interface IrpfExplanation {
  texto: string;
  fuente: "claude" | "plantilla";
  intentos: number;
}

// ─── Resumen fiscal del ejercicio (Modelo100ExportController / AnnualTaxReportService) ───

export interface AnnualSale {
  isin: string;
  /** ISO 8601 (yyyy-mm-dd). Debe ser del ejercicio. */
  saleDate: string;
  quantity: number;
  salePrice: number;
}

export interface AnnualReportRequest {
  fiscalYear: number;
  sales: AnnualSale[];
  carryForward?: CarryForward[];
}

export interface AnnualOperation {
  isin: string;
  saleDate: string;
  quantity: number;
  quantityCovered: number;
  salePrice: number;
  transmissionValue: number;
  acquisitionValue: number;
  /** + ganancia, - pérdida */
  gainLoss: number;
  firstAcquisitionDate: string | null;
  lastAcquisitionDate: string | null;
  lotsUsed: number;
  twoMonthRuleViolation: boolean;
  /** Lo que entra en el ejercicio (0 si la pérdida se difiere). */
  computableGainLoss: number;
  warning: string | null;
}

export interface AnnualReport {
  portfolioId: string;
  fiscalYear: number;
  operations: AnnualOperation[];
  totalTransmissionValue: number;
  totalAcquisitionValue: number;
  totalGains: number;
  totalLosses: number;
  deferredLosses: number;
  result: IrpfResult;
}
