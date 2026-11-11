/**
 * Tipos de la llamada de voz con el asesor de IA — espejo de los records Java de `com.wealth.mod5_telephony`.
 * Endpoints (base /api/v1):
 *   POST /telephony/trigger-call                 body { phoneNumber, userId } → 202 CallStarted
 *   GET  /telephony/calls/{conversationId}/status                           → CallStatus
 *
 * (GET /telephony/agent-context/{userId} lo consume el agente de voz de ElevenLabs, no la UI.)
 *
 * @module core-ui/types/telephony
 */

/** Estado de la llamada que devuelve el backend, ya normalizado (no el crudo de ElevenLabs). */
export type RemoteCallStatus = "dialing" | "in_call" | "ended" | "failed";

export interface CallStarted {
  conversationId: string;
  status: RemoteCallStatus;
}

export interface CallStatus {
  conversationId: string;
  status: RemoteCallStatus;
}

export interface TriggerCallRequest {
  /** Teléfono en formato internacional E.164 (+34600123456). */
  phoneNumber: string;
  /** Identificador del cliente (CLI-001) o de su cartera (PF-001). */
  userId: string;
}
