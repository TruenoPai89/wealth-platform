"use client";

/**
 * VoiceOrb.tsx — Indicador visual del estado de la llamada de voz.
 *
 *   formulario / marcando → teléfono con anillos de timbre que se expanden
 *   en llamada            → barras de voz animadas sobre fondo de marca
 *   finalizada            → check verde
 *   fallida               → cruz roja
 *
 * Es decorativo (aria-hidden): el estado real lo anuncia el texto con aria-live del modal. Las animaciones viven en
 * globals.css (.voice-ring, .voice-bar) y se desactivan con prefers-reduced-motion.
 *
 * @module mod5-voice-ui/VoiceOrb
 */

import React from "react";

export type OrbPhase = "form" | "dialing" | "in_call" | "ended" | "failed";

const BAR_HEIGHTS = [14, 30, 44, 24, 36, 18, 28];

function PhoneIcon({ size = 30 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z" />
    </svg>
  );
}

export function VoiceOrb({ phase }: { phase: OrbPhase }) {
  const ringing = phase === "dialing" || phase === "in_call";

  const coreStyle =
    phase === "in_call"
      ? "bg-brand-600 text-white shadow-lg shadow-brand-600/40"
      : phase === "ended"
        ? "bg-semantic-gain/15 text-semantic-gain"
        : phase === "failed"
          ? "bg-semantic-loss/15 text-semantic-loss"
          : "bg-brand-600/15 text-brand-400";

  return (
    <div className="relative mx-auto flex h-32 w-32 items-center justify-center" aria-hidden="true" data-testid="voice-orb">
      {ringing &&
        [0, 1, 2].map((i) => (
          <span
            key={i}
            className="voice-ring absolute inset-3 rounded-full border border-brand-400/60"
            style={{ animationDelay: `${i * 0.72}s` }}
          />
        ))}

      <div className={`relative flex h-20 w-20 items-center justify-center rounded-full transition-colors duration-300 ${coreStyle}`}>
        {phase === "in_call" ? (
          <div className="flex h-12 items-center gap-[3px]">
            {BAR_HEIGHTS.map((h, i) => (
              <span
                key={i}
                className="voice-bar w-[3px] rounded-full bg-white"
                style={{ height: h, animationDelay: `${i * 0.13}s` }}
              />
            ))}
          </div>
        ) : phase === "ended" ? (
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"
               strokeLinecap="round" strokeLinejoin="round">
            <polyline points="20 6 9 17 4 12" />
          </svg>
        ) : phase === "failed" ? (
          <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"
               strokeLinecap="round" strokeLinejoin="round">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
        ) : (
          <PhoneIcon />
        )}
      </div>
    </div>
  );
}
