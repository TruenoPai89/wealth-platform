
/**
 * pledgeService.ts
 * Servicio del Módulo 4 (pignoración / LTV).
 */

import { apiClient } from "@/core-ui/api/apiClient";

import type {
  Explanation,
  ExtractedTerms,
  ExtractionResult,
  PledgeContractRequest,
  PledgeStatus,
} from "@/core-ui/types/pledge";

const AI_BASE_URL =
  process.env["NEXT_PUBLIC_AI_BASE_URL"] ?? "http://localhost:8000";



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
 * Carga el contrato de ejemplo desde Python.
 */
export async function loadSampleContract(): Promise<string> {
  const r = await aiRequest<{ texto: string }>(
    "/contracts/pledge-sample",
  );

  return r.texto;
}

/**
 * Envía el contrato a Python para extraer sus términos.
 */
export async function extractTerms(
  texto: string,
): Promise<ExtractionResult> {
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
 */
export async function saveContract(
  portfolioId: string,
  request: PledgeContractRequest,
): Promise<void> {
  await apiClient.post(
    `/pledge/${encodeURIComponent(portfolioId)}/contract`,
    request,
  );
}

/**
 * Obtiene el estado actualizado de la pignoración.
 */
export async function fetchStatus(
  portfolioId: string,
  equityShock: number,
  horizonDays = 30,
): Promise<PledgeStatus> {
  const params = new URLSearchParams({
    equityShock: equityShock.toString(),
    horizonDays: horizonDays.toString(),
  });

  return apiClient.get<PledgeStatus>(
    `/pledge/${encodeURIComponent(portfolioId)}/status?${params.toString()}`,
  );
}
