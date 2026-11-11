/**
 * webVoiceService.ts — sesión de voz en el navegador con el asesor de IA (ElevenLabs, WebRTC, sin teléfono).
 *
 * GET /voice/web-session?userId= → { conversationToken, agentId }. El token lo pide el backend Java con la API key
 * (que nunca llega al navegador) y es de un solo uso.
 *
 * @module core-ui/services/webVoiceService
 */

import { apiClient } from "@/core-ui/api/apiClient";

export interface WebVoiceSession {
  conversationToken: string;
  agentId: string;
}

export function fetchWebVoiceSession(userId: string): Promise<WebVoiceSession> {
  return apiClient.get<WebVoiceSession>(`/voice/web-session?userId=${encodeURIComponent(userId)}`);
}
