
/**
 * pledgeService.ts
 * Servicio del Módulo 4 (pignoración / LTV).
 * Con NEXT_PUBLIC_USE_MOCK=true devuelve datos simulados sin backend.
 */

import { apiClient } from "@/core-ui/api/apiClient";
import { USE_MOCK } from "@/core-ui/services/portfolioService";

import type {
  Explanation,
  ExtractedTerms,
  ExtractionResult,
  PledgeContractRequest,
  PledgeStatus,
} from "@/core-ui/types/pledge";
import pledgeStatusMockData from "@/core-ui/mocks/pledge_status_mock.json";

const AI_BASE_URL =
  process.env["NEXT_PUBLIC_AI_BASE_URL"] ?? "http://localhost:8000";

// ─── Texto del contrato de ejemplo (mock inline) ──────────────────────────────
const MOCK_CONTRACT_TEXT = `CONTRATO DE PIGNORACIÓN DE VALORES — PRÉSTAMO LOMBARDO
Referencia: PLEDGE-2024-001 / LOAN-2024-087

Entre BANCA PRIVADA DEMO, S.A. (en adelante, "el Prestamista") y la Familia Mendoza Ruiz (en adelante, "el Prestatario").

CONDICIONES ECONÓMICAS

1. IMPORTE DEL PRÉSTAMO: 65.000,00 EUR

2. RATIO LOAN-TO-VALUE (LTV)
   El Préstamo no podrá superar en ningún momento el 65% (LTV máximo = 0,65) del valor de mercado de los activos pignorados.

3. MARGIN CALL
   Si el LTV alcanza o supera el 75% (LTV margin call = 0,75), el Prestamista podrá exigir la reposición de garantías.

4. PLAZO DE REPOSICIÓN
   El Prestatario dispondrá de 5 días hábiles desde la notificación de margin call.

5. HAIRCUTS APLICABLES POR CLASE DE ACTIVO
   - Renta Variable (EQUITY): 20% de descuento (haircut = 0,20)
   - Renta Fija (FIXED_INCOME): 10% de descuento (haircut = 0,10)
   - Materias Primas (COMMODITY): 30% de descuento (haircut = 0,30)
   - Efectivo (CASH): sin descuento (haircut = 0,00)

Firmado en Madrid, a 15 de enero de 2024.`;



/**
 * Realiza peticiones al servicio Python y gestiona sus errores.
 */
export async function aiRequest<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  let response: Response;

  try {
    response = await fetch(`${AI_BASE_URL}${path}`, {
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...init?.headers,
      },
    });
  } catch {
    throw new Error(
      `No se pudo conectar con el servicio de IA (${AI_BASE_URL}). ¿Está arrancado uvicorn en el puerto 8000?`,
    );
  }

  if (!response.ok) {
    let detail = `${response.status} ${response.statusText}`;

    try {
      const body = (await response.json()) as {
        detail?: { problemas?: string[] } | string;
      };

      if (typeof body.detail === "string") {
        detail = body.detail;
      } else if (body.detail?.problemas) {
        detail = body.detail.problemas.join("\n · ");
      }
    } catch {
      /* El cuerpo de la respuesta no era JSON */
    }

    throw new Error(`Servicio de IA: ${detail}`);
  }

  return response.json() as Promise<T>;
}


/**
 * Carga el contrato de ejemplo (desde Python o desde el mock inline).
 */
export async function loadSampleContract(): Promise<string> {
  if (USE_MOCK) {
    await new Promise((r) => setTimeout(r, 300));
    return MOCK_CONTRACT_TEXT;
  }
  const r = await aiRequest<{ texto: string }>("/contracts/pledge-sample");
  return r.texto;
}

/**
 * Envía el contrato a Python para extraer sus términos.
 * En mock devuelve los términos pre-extraídos del contrato demo.
 */
export async function extractTerms(texto: string): Promise<ExtractionResult> {
  if (USE_MOCK) {
    await new Promise((r) => setTimeout(r, 1200));
    const mockTerms: ExtractedTerms = {
      ltv_maximo: 0.65,
      ltv_margin_call: 0.75,
      plazo_reposicion_dias_habiles: 5,
      haircuts: [
        { clase: "EQUITY",       haircut: 0.20, cita: "Renta Variable (EQUITY): 20% de descuento (haircut = 0,20)" },
        { clase: "FIXED_INCOME", haircut: 0.10, cita: "Renta Fija (FIXED_INCOME): 10% de descuento (haircut = 0,10)" },
        { clase: "COMMODITY",    haircut: 0.30, cita: "Materias Primas (COMMODITY): 30% de descuento (haircut = 0,30)" },
        { clase: "CASH",         haircut: 0.00, cita: "Efectivo (CASH): sin descuento (haircut = 0,00)" },
      ],
      cita_ltv_maximo:    "El Préstamo no podrá superar en ningún momento el 65% (LTV máximo = 0,65)",
      cita_margin_call:   "Si el LTV alcanza o supera el 75% (LTV margin call = 0,75)",
      cita_plazo:         "El Prestatario dispondrá de 5 días hábiles desde la notificación",
    };
    return { terminos: mockTerms, intentos: 1, desde_cache: false };
  }
  return aiRequest<ExtractionResult>("/extract/pledge-contract", {
    method: "POST",
    body: JSON.stringify({ texto }),
  });
}

/**
 * Solicita a Python una explicación del estado de la pignoración.
 * Si falla la explicación, el resto de la pantalla puede seguir funcionando.
 */
export async function explainStatus(
  status: PledgeStatus,
): Promise<Explanation | null> {
  if (USE_MOCK) {
    await new Promise((r) => setTimeout(r, 400));
    const ltvPct = (status.ltv * 100).toFixed(1);
    const zone = status.zone === "OK"
      ? "dentro del límite permitido"
      : status.zone === "LIMIT_EXCEEDED"
        ? "por encima del límite máximo"
        : "en zona de margin call";
    return {
      texto: `La cartera pignorada tiene un LTV actual del ${ltvPct}%, ${zone}. El préstamo lombardo de ${status.loanBalance.toFixed(2)} € está respaldado por activos con un valor colateral de ${status.collateralValue.toFixed(2)} €. ${status.zone === "OK" ? `Hay un margen de seguridad amplio: el colateral debería caer ${status.collateralDropToMarginCall.toFixed(2)} € para activar un margin call.` : "Se recomienda revisar urgentemente la situación con su asesor."}`,
      fuente: "plantilla",
      intentos: 1,
    };
  }
  try {
    return await aiRequest<Explanation>("/explain/pledge-status", {
      method: "POST",
      body: JSON.stringify(status),
    });
  } catch {
    return null;
  }
}



/**
 * Convierte lo extraído por Claude (y revisado por el cliente)
 * en la petición que necesita el backend Java.
 */
export function toContractRequest(
  terms: ExtractedTerms,
  loanBalance: number,
): PledgeContractRequest {
  if (
    terms.ltv_maximo === null ||
    terms.ltv_margin_call === null
  ) {
    throw new Error(
      "El contrato no define el límite o el umbral de margin call: no se puede calcular.",
    );
  }

  const haircuts: PledgeContractRequest["haircuts"] = {};

  for (const h of terms.haircuts) {
    haircuts[h.clase] = h.haircut;
  }

  return {
    loanBalance,
    ltvMax: terms.ltv_maximo,
    ltvMarginCall: terms.ltv_margin_call,
    cureDays: terms.plazo_reposicion_dias_habiles,
    haircuts,
  };
}


/**
 * Guarda el contrato de pignoración en el backend Java.
 * En mock simula éxito sin llamar al servidor.
 */
export async function saveContract(
  portfolioId: string,
  request: PledgeContractRequest,
): Promise<void> {
  if (USE_MOCK) {
    await new Promise((r) => setTimeout(r, 500));
    return; // simulamos guardado exitoso
  }
  await apiClient.post(
    `/pledge/${encodeURIComponent(portfolioId)}/contract`,
    request,
  );
}

/**
 * Obtiene el estado actualizado de la pignoración.
 * En mock aplica el shock de renta variable al LTV del mock.
 */
export async function fetchStatus(
  portfolioId: string,
  equityShock: number,
  horizonDays = 30,
): Promise<PledgeStatus> {
  if (USE_MOCK) {
    await new Promise((r) => setTimeout(r, 600));
    const base = pledgeStatusMockData as PledgeStatus;
    if (equityShock === 0) return { ...base, horizonDays };
    // Simula la caída: reduce el valor de mercado de RV en el % del shock
    const shockFactor = 1 - Math.abs(equityShock);
    const newMarketValue = base.marketValue * (1 - Math.abs(equityShock) * 0.68); // 68% de RV en la cartera
    const newCollateralValue = base.collateralValue * shockFactor;
    const newLtv = base.loanBalance / newCollateralValue;
    const zone: PledgeStatus["zone"] =
      newLtv >= base.ltvMarginCall ? "MARGIN_CALL"
      : newLtv >= base.ltvMax ? "LIMIT_EXCEEDED"
      : "OK";
    return {
      ...base,
      equityShock,
      marketValue: Math.round(newMarketValue * 100) / 100,
      collateralValue: Math.round(newCollateralValue * 100) / 100,
      ltv: Math.round(newLtv * 10000) / 10000,
      zone,
      horizonDays,
      collateralDropToMarginCall: Math.max(
        0,
        Math.round((newCollateralValue - base.loanBalance / base.ltvMarginCall) * 100) / 100,
      ),
    };
  }
  const params = new URLSearchParams({
    equityShock: equityShock.toString(),
    horizonDays: horizonDays.toString(),
  });
  return apiClient.get<PledgeStatus>(
    `/pledge/${encodeURIComponent(portfolioId)}/status?${params.toString()}`,
  );
}
