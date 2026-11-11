"use client";

/**
 * VoiceAgentModal.tsx — «Llamar a mi Asesor de IA»: llamada de voz interactiva (ElevenLabs Conversational AI + Twilio).
 *
 * Flujo (el backend Java hace la llamada; la UI solo pide y muestra el estado REAL):
 *   1. TELÉFONO: el cliente escribe o confirma su número → «Recibir llamada ahora».
 *      POST /telephony/trigger-call (202 + conversationId). El número se valida/normaliza a E.164.
 *   2. MARCANDO («Marcando...»): ElevenLabs está llamando al teléfono.
 *   3. EN LLAMADA («En llamada con tu Cartera...»): el cliente ha descolgado y habla con el agente, que ya conoce su
 *      cartera (el agente pide GET /telephony/agent-context/{userId} al iniciar la voz).
 *   4. FINALIZADA («Llamada finalizada»), o fallida/sin respuesta, con opción de reintentar.
 *
 * El estado se consulta con GET /telephony/calls/{id}/status cada 2,5 s; se deja de consultar al cerrar el modal o al
 * terminar. Si el proveedor tarda demasiado en conectar (90 s) se da por «sin respuesta». Al cerrar el modal NO se
 * cuelga la llamada en curso: el aviso lo dice claramente.
 *
 * @module mod5-voice-ui/VoiceAgentModal
 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  fetchCallStatus,
  friendlyCallError,
  maskPhone,
  normalizePhone,
  startVoiceCall,
} from "@/core-ui/services/telephonyService";
import type { RemoteCallStatus } from "@/core-ui/types/telephony";
import { VoiceOrb, type OrbPhase } from "@/mod5-voice-ui/VoiceOrb";

const POLL_MS = 2500;
/** Máximo esperando a que el cliente descuelgue antes de darlo por «sin respuesta». */
const DIAL_TIMEOUT_MS = 90_000;
/** Fallos de consulta seguidos tolerados antes de mostrar error (un fallo suelto de red no debe romper la llamada). */
const MAX_POLL_FAILURES = 4;
const PHONE_STORAGE_KEY = "wealth.voice.phone";

type Phase = "form" | "dialing" | "in_call" | "ended" | "failed";

interface VoiceAgentModalProps {
  /** Cliente (CLI-001) o cartera (PF-001) cuyo contexto conocerá el agente. */
  userId: string;
  /** Nombre mostrado en el texto de la llamada. */
  clientName?: string;
  /** Teléfono sugerido (si el cliente ya lo tiene registrado). */
  defaultPhone?: string;
  onClose: () => void;
}

const STATUS_TEXT: Record<Exclude<Phase, "form">, { title: string; detail: string }> = {
  dialing: {
    title: "Marcando...",
    detail: "Tu asesor de IA te está llamando. Descuelga tu teléfono para empezar.",
  },
  in_call: {
    title: "En llamada con tu Cartera...",
    detail: "Habla con tu asesor: ya conoce tus posiciones, tu liquidez y tus agrupaciones.",
  },
  ended: {
    title: "Llamada finalizada",
    detail: "Gracias por hablar con tu asesor de IA. Puedes volver a llamar cuando quieras.",
  },
  failed: {
    title: "No se pudo completar la llamada",
    detail: "",
  },
};

export function VoiceAgentModal({ userId, clientName, defaultPhone = "", onClose }: VoiceAgentModalProps) {
  const [rawPhone, setRawPhone] = useState(defaultPhone);
  const [phase, setPhase] = useState<Phase>("form");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [dialedPhone, setDialedPhone] = useState<string | null>(null);
  const [seconds, setSeconds] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  const e164 = normalizePhone(rawPhone);
  const phoneInvalid = rawPhone.trim() !== "" && e164 === null;
  const callActive = phase === "dialing" || phase === "in_call";

  // Teléfono recordado de la última vez (solo en este navegador) y foco al abrir.
  useEffect(() => {
    if (!defaultPhone) {
      try {
        const saved = window.localStorage.getItem(PHONE_STORAGE_KEY);
        if (saved) setRawPhone(saved);
      } catch {
        /* localStorage no disponible (modo privado): se ignora */
      }
    }
    inputRef.current?.focus();
  }, [defaultPhone]);

  // Esc cierra el modal (salvo mientras se envía la petición).
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !submitting) onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [submitting, onClose]);

  // Consulta del estado real mientras la llamada está activa. Se limpia al cerrar el modal o al cambiar de fase.
  const reachedCall = useRef(false);
  useEffect(() => {
    if (!conversationId || !callActive) return;

    let cancelled = false;
    let failures = 0;
    const startedAt = Date.now();

    function applyRemoteStatus(status: RemoteCallStatus) {
      if (status === "in_call") {
        reachedCall.current = true;
        setPhase("in_call");
      } else if (status === "ended") {
        if (reachedCall.current) {
          setPhase("ended");
        } else {
          // Terminó sin llegar a estar en llamada: el cliente no descolgó (o rechazó la llamada).
          setError("No se ha contestado la llamada. Comprueba tu teléfono e inténtalo de nuevo.");
          setPhase("failed");
        }
      } else if (status === "failed") {
        setError("La llamada ha fallado. Comprueba el número e inténtalo de nuevo.");
        setPhase("failed");
      } else if (Date.now() - startedAt > DIAL_TIMEOUT_MS) {
        setError("Nadie ha contestado a la llamada. Inténtalo de nuevo.");
        setPhase("failed");
      }
    }

    async function poll() {
      try {
        const { status } = await fetchCallStatus(conversationId as string);
        if (cancelled) return;
        failures = 0;
        applyRemoteStatus(status);
      } catch (err) {
        if (cancelled) return;
        failures += 1;
        if (failures >= MAX_POLL_FAILURES) {
          setError(friendlyCallError(err));
          setPhase("failed");
        }
      }
    }

    void poll();
    const id = window.setInterval(() => void poll(), POLL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [conversationId, callActive]);

  // Cronómetro de la conversación (solo mientras está en llamada).
  useEffect(() => {
    if (phase !== "in_call") return;
    setSeconds(0);
    const id = window.setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => window.clearInterval(id);
  }, [phase]);

  const handleCall = useCallback(async () => {
    if (e164 === null) return;
    setSubmitting(true);
    setError(null);
    reachedCall.current = false;
    try {
      const started = await startVoiceCall({ phoneNumber: e164, userId });
      try {
        window.localStorage.setItem(PHONE_STORAGE_KEY, rawPhone);
      } catch {
        /* se ignora */
      }
      setDialedPhone(e164);
      setConversationId(started.conversationId);
      setPhase("dialing");
    } catch (err) {
      setError(friendlyCallError(err));
    } finally {
      setSubmitting(false);
    }
  }, [e164, rawPhone, userId]);

  function retry() {
    setConversationId(null);
    setError(null);
    setSeconds(0);
    setPhase("form");
    window.setTimeout(() => inputRef.current?.focus(), 0);
  }

  const text = phase === "form" ? null : STATUS_TEXT[phase];
  const mm = String(Math.floor(seconds / 60)).padStart(2, "0");
  const ss = String(seconds % 60).padStart(2, "0");

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="voice-agent-title"
      data-testid="voice-modal"
    >
      <div className="relative w-full max-w-md overflow-hidden rounded-2xl border border-surface-border bg-surface-raised shadow-2xl">
        {/* Halo de marca detrás del orbe */}
        <div
          className="pointer-events-none absolute inset-x-0 top-0 h-44 bg-gradient-to-b from-brand-600/20 to-transparent"
          aria-hidden="true"
        />

        <button
          type="button"
          onClick={onClose}
          disabled={submitting}
          aria-label="Cerrar"
          className="absolute right-3 top-3 z-10 rounded-lg p-1.5 text-gray-500 transition-colors hover:bg-surface-overlay hover:text-fg disabled:opacity-50"
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
            {phase === "form" ? "Habla con tu Asesor de IA" : text?.title}
          </h2>

          {phase === "form" ? (
            <p className="mt-1 text-center text-sm text-gray-500">
              {clientName ? `${clientName}: ` : ""}te llamamos ahora y conversas por voz con un asesor que ya conoce tu
              cartera, tu liquidez y tus agrupaciones.
            </p>
          ) : (
            <p className="mt-1 text-center text-sm text-gray-500" aria-live="polite" data-testid="voice-status">
              {phase === "failed" ? error : text?.detail}
            </p>
          )}

          {phase === "form" && (
            <form
              className="mt-5 flex flex-col gap-3"
              onSubmit={(e) => {
                e.preventDefault();
                void handleCall();
              }}
            >
              <label htmlFor="voice-phone" className="text-xs font-medium text-gray-400">
                Tu teléfono
              </label>
              <input
                id="voice-phone"
                ref={inputRef}
                type="tel"
                inputMode="tel"
                autoComplete="tel"
                value={rawPhone}
                onChange={(e) => setRawPhone(e.target.value)}
                placeholder="+34 600 123 456"
                aria-invalid={phoneInvalid}
                aria-describedby="voice-phone-hint"
                disabled={submitting}
                className={`w-full rounded-lg border bg-surface-base px-3 py-2.5 font-mono text-sm text-fg placeholder:text-gray-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 ${
                  phoneInvalid ? "border-semantic-loss/60" : "border-surface-border"
                }`}
              />
              <p
                id="voice-phone-hint"
                className={`-mt-1 text-xs ${phoneInvalid ? "text-semantic-loss" : "text-gray-500"}`}
              >
                {phoneInvalid
                  ? "Introduce un número válido, p. ej. +34 600 123 456."
                  : e164
                    ? `Llamaremos al ${maskPhone(e164)}.`
                    : "Con prefijo internacional (+34 para España)."}
              </p>

              {error && (
                <p className="text-sm text-semantic-loss" role="alert">
                  {error}
                </p>
              )}

              <button
                type="submit"
                disabled={submitting || e164 === null}
                className="mt-1 inline-flex items-center justify-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-brand-500 disabled:cursor-not-allowed disabled:opacity-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
              >
                {submitting ? "Solicitando llamada..." : "Recibir llamada ahora"}
              </button>
              <p className="text-center text-[11px] leading-snug text-gray-600">
                Es un asistente de IA con fines informativos, no asesoramiento financiero personalizado. La llamada
                puede quedar registrada por el proveedor de voz.
              </p>
            </form>
          )}
          {phase !== "form" && (
            <div className="mt-5 flex flex-col gap-3">
              {dialedPhone && (
                <div className="flex items-center justify-between rounded-lg bg-surface-overlay/50 px-3 py-2 text-sm">
                  <span className="text-gray-500">{phase === "dialing" ? "Llamando al" : "Número"}</span>
                  <span className="font-mono text-fg">{maskPhone(dialedPhone)}</span>
                  {phase === "in_call" && (
                    <span className="font-mono text-semantic-gain" aria-label="Duración">
                      {mm}:{ss}
                    </span>
                  )}
                </div>
              )}

              {callActive && (
                <p className="text-center text-[11px] leading-snug text-gray-600">
                  Cerrar esta ventana no cuelga la llamada: para terminarla, cuelga desde tu teléfono.
                </p>
              )}

              <div className="flex justify-end gap-2">
                {(phase === "ended" || phase === "failed") && (
                  <button
                    type="button"
                    onClick={retry}
                    className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-brand-500"
                  >
                    {phase === "ended" ? "Llamar de nuevo" : "Reintentar"}
                  </button>
                )}
                <button
                  type="button"
                  onClick={onClose}
                  className="rounded-lg bg-surface-overlay px-4 py-2 text-sm font-medium text-gray-300 transition-colors hover:text-fg"
                >
                  {callActive ? "Cerrar ventana" : "Cerrar"}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
