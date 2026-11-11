/**
 * telephonyService.ts
 * Servicio de la llamada de voz con el asesor de IA (backend Java, ElevenLabs + Twilio).
 *
 * El teléfono lo valida y normaliza el backend (E.164); aquí solo se hace una normalización amable para el usuario
 * español y se muestran los mensajes de error del backend sin el prefijo técnico de apiClient.
 *
 * @module core-ui/services/telephonyService
 */

import { apiClient } from "@/core-ui/api/apiClient";
import type {
  CallStarted,
  CallStatus,
  TriggerCallRequest,
} from "@/core-ui/types/telephony";

/**
 * Teléfono tal como lo escribe el usuario → E.164, o null si no es válido.
 * Acepta separadores (espacios, guiones, puntos, paréntesis), prefijo "00" y, por comodidad, un móvil/fijo español
 * de 9 dígitos sin prefijo ("600 123 456" → "+34600123456").
 */
export function normalizePhone(raw: string): string | null {
  let s = raw.replace(/[\s().-]/g, "");
  if (s.startsWith("00")) s = `+${s.slice(2)}`;
  if (/^[6-9]\d{8}$/.test(s)) s = `+34${s}`;
  return /^\+[1-9]\d{7,14}$/.test(s) ? s : null;
}

/** "+34600123456" → "+34 *** *** 456": se muestra confirmado sin exponer el número entero en pantalla. */
export function maskPhone(e164: string): string {
  if (e164.length <= 6) return e164;
  return `${e164.slice(0, 3)} ${"*".repeat(e164.length - 6)} ${e164.slice(-3)}`;
}

/**
 * Mensaje legible para la UI. apiClient lanza "[apiClient] Error 429 ... → url — detalle": se queda con el detalle
 * del backend; si no hay conexión, un texto corto.
 */
export function friendlyCallError(err: unknown): string {
  const message = err instanceof Error ? err.message : "";
  if (message.includes("No se pudo conectar con el backend")) {
    return "No se pudo conectar con el servidor. Comprueba que el backend está arrancado.";
  }
  const detail = message.split(" — ").slice(1).join(" — ").trim();
  if (detail) return detail;
  return "No se pudo iniciar la llamada. Inténtalo de nuevo.";
}

/** Dispara la llamada saliente: el backend responde 202 con el id de la conversación para consultar su estado. */
export function startVoiceCall(request: TriggerCallRequest): Promise<CallStarted> {
  return apiClient.post<CallStarted>("/telephony/trigger-call", request);
}

/** Estado real de la llamada (dialing, in_call, ended, failed). */
export function fetchCallStatus(conversationId: string): Promise<CallStatus> {
  return apiClient.get<CallStatus>(
    `/telephony/calls/${encodeURIComponent(conversationId)}/status`,
  );
}
