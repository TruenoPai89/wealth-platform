"use client";

/**
 * VoiceAgentModal.tsx — «Habla con tu Asesor de IA»: conversación de voz en el navegador (ElevenLabs, WebRTC).
 *
 * Sin teléfono ni Twilio. Flujo:
 *   1. El usuario pulsa «Empezar a hablar». Se pide permiso de micrófono.
 *   2. El backend Java entrega un token de conversación de un solo uso (GET /voice/web-session). La API key de
 *      ElevenLabs nunca llega al navegador y el agente sigue siendo privado.
 *   3. El SDK @elevenlabs/react abre la sesión. El agente (Alex) llama a sus herramientas get_portfolio_context y
 *      get_fund_dfi contra el backend (vía ngrok, con la cabecera secreta) y responde por voz.
 *   4. «Colgar» o cerrar el modal termina la sesión (a diferencia de la llamada telefónica, aquí sí se cuelga).
 *
 * La transcripción se muestra en pantalla: accesibilidad y, en la demo, el jurado ve qué entiende y qué responde.
 *
 * @module mod5-voice-ui/VoiceAgentModal
 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import { ConversationProvider, useConversation } from "@elevenlabs/react";
import { fetchWebVoiceSession } from "@/core-ui/services/webVoiceService";
import { friendlyCallError } from "@/core-ui/services/telephonyService";
import { VoiceOrb, type OrbPhase } from "@/mod5-voice-ui/VoiceOrb";

interface VoiceAgentModalProps {
  /** Cliente (CLI-001) o cartera (PF-001) cuyo contexto conoce el agente. */
  userId: string;
  /** Nombre mostrado en el texto de bienvenida. */
  clientName?: string;
  /** Sin uso en la voz web; se mantiene por compatibilidad con la llamada telefónica. */
  defaultPhone?: string;
  onClose: () => void;
}

interface Line {
  id: number;
  who: "user" | "ai";
  text: string;
}

const MAX_LINES = 6;

export function VoiceAgentModal(props: VoiceAgentModalProps) {
  return (
    <ConversationProvider>
      <VoiceAgentDialog {...props} />
    </ConversationProvider>
  );
}

function VoiceAgentDialog({ userId, clientName, onClose }: VoiceAgentModalProps) {
  const [error, setError] = useState<string | null>(null);
  const [preparing, setPreparing] = useState(false);
  const [ended, setEnded] = useState(false);
  const [lines, setLines] = useState<Line[]>([]);
  const [seconds, setSeconds] = useState(0);
  const lineId = useRef(0);

  const conversation = useConversation({
    onMessage: ({ message, source }) => {
      if (!message?.trim()) return;
      lineId.current += 1;
      const line: Line = { id: lineId.current, who: source === "user" ? "user" : "ai", text: message };
      setLines((prev) => [...prev, line].slice(-MAX_LINES));
    },
    onError: (message) => {
      setError(typeof message === "string" && message ? message : "Error en la conversación de voz.");
    },
    onDisconnect: () => setEnded(true),
  });

  const { status, isSpeaking, startSession, endSession } = conversation;
  const connected = status === "connected";
  const connecting = preparing || status === "connecting";

  const phase: OrbPhase = error
    ? "failed"
    : connected
      ? "in_call"
      : connecting
        ? "dialing"
        : ended
          ? "ended"
          : "form";

  // Cronómetro mientras hay conversación.
  useEffect(() => {
    if (!connected) return;
    setSeconds(0);
    const id = window.setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => window.clearInterval(id);
  }, [connected]);

  // Cerrar el modal (o desmontarlo) cuelga: no queda el micrófono abierto en segundo plano.
  const endRef = useRef(endSession);
  endRef.current = endSession;
  useEffect(() => () => endRef.current(), []);

  const handleClose = useCallback(() => {
    endSession();
    onClose();
  }, [endSession, onClose]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") handleClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [handleClose]);

  const handleStart = useCallback(async () => {
    setError(null);
    setEnded(false);
    setLines([]);
    setPreparing(true);
    try {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        stream.getTracks().forEach((t) => t.stop()); // solo era para pedir permiso; el SDK abre el suyo
      } catch {
        setError("No hay acceso al micrófono. Permítelo en el navegador e inténtalo de nuevo.");
        return;
      }
      const session = await fetchWebVoiceSession(userId);
      startSession({
        conversationToken: session.conversationToken,
        connectionType: "webrtc",
        userId,
        dynamicVariables: { user_id: userId },
      });
    } catch (err) {
      setError(friendlyCallError(err));
    } finally {
      setPreparing(false);
    }
  }, [startSession, userId]);

  const mm = String(Math.floor(seconds / 60)).padStart(2, "0");
  const ss = String(seconds % 60).padStart(2, "0");

  const title = error
    ? "No se pudo conectar"
    : connected
      ? isSpeaking
        ? "Alex está hablando..."
        : "Te escucho..."
      : connecting
        ? "Conectando con tu asesor..."
        : ended
          ? "Conversación finalizada"
          : "Habla con tu Asesor de IA";

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="voice-agent-title"
      data-testid="voice-modal"
    >
      <div className="relative w-full max-w-md overflow-hidden rounded-2xl border border-surface-border bg-surface-raised shadow-2xl">
        <div
          className="pointer-events-none absolute inset-x-0 top-0 h-44 bg-gradient-to-b from-brand-600/20 to-transparent"
          aria-hidden="true"
        />

        <button
          type="button"
          onClick={handleClose}
          aria-label="Cerrar"
          className="absolute right-3 top-3 z-10 rounded-lg p-1.5 text-gray-500 transition-colors hover:bg-surface-overlay hover:text-fg"
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
               strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        </button>

        <div className="relative px-6 pb-6 pt-7">
          <VoiceOrb phase={phase} />

          <h2 id="voice-agent-title" className="mt-2 text-center text-lg font-semibold text-fg">
            {title}
          </h2>

          <p className="mt-1 text-center text-sm text-gray-500" aria-live="polite" data-testid="voice-status">
            {error
              ? error
              : connected
                ? `En conversación · ${mm}:${ss}`
                : connecting
                  ? "Preparando el micrófono y la conexión segura."
                  : ended
                    ? "Puedes volver a hablar con tu asesor cuando quieras."
                    : `${clientName ? `${clientName}: ` : ""}habla por el micrófono con un asesor que ya conoce tu cartera, tu liquidez y tus agrupaciones.`}
          </p>

          {lines.length > 0 && (
            <ul
              className="mt-4 flex max-h-48 flex-col gap-2 overflow-y-auto rounded-lg bg-surface-overlay/40 p-3 text-sm"
              aria-label="Transcripción"
              data-testid="voice-transcript"
            >
              {lines.map((l) => (
                <li key={l.id} className={l.who === "user" ? "text-right text-gray-400" : "text-left text-fg"}>
                  <span className="mr-1 text-[11px] font-semibold uppercase text-gray-500">
                    {l.who === "user" ? "Tú" : "Alex"}
                  </span>
                  {l.text}
                </li>
              ))}
            </ul>
          )}

          <div className="mt-5 flex flex-col gap-3">
            {connected ? (
              <button
                type="button"
                onClick={() => endSession()}
                className="inline-flex items-center justify-center rounded-lg bg-semantic-loss px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:opacity-90"
              >
                Colgar
              </button>
            ) : (
              <button
                type="button"
                onClick={() => void handleStart()}
                disabled={connecting}
                className="inline-flex items-center justify-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-brand-500 disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
              >
                {connecting ? "Conectando..." : ended || error ? "Volver a hablar" : "Empezar a hablar"}
              </button>
            )}
            <p className="text-center text-[11px] leading-snug text-gray-600">
              Asistente de IA con fines informativos, no asesoramiento financiero personalizado. La conversación puede
              quedar registrada por el proveedor de voz.
            </p>
          </div>
        </div>
      </div>
    </div>
  );
}
