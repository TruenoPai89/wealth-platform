"use client";

/**
 * SliderField.tsx — Control numérico interactivo: slider + input numérico + botones de ajuste rápido.
 *
 * El valor se guarda en la unidad del dominio (p. ej. 0.045 para un 4,5 %) y se muestra multiplicado por `scale`
 * (100 para porcentajes). El input acepta coma o punto decimal; mientras se escribe no se corrige nada, y al salir
 * del campo (blur) el valor se ajusta a [min, max].
 *
 * @module mod4-pledge-ui/SliderField
 */

import React, { useEffect, useId, useRef, useState } from "react";

export interface QuickAdjust {
  label: string;
  /** Suma `delta` al valor actual (en unidades del dominio)… */
  delta?: number;
  /** …o lo fija a `set`. */
  set?: number;
}

interface SliderFieldProps {
  label: string;
  value: number;
  onChange: (value: number) => void;
  min: number;
  max: number;
  /** Paso del slider, en unidades del dominio. */
  step: number;
  /** Factor de visualización (100 → fracción mostrada como %). Por defecto 1. */
  scale?: number;
  /** Sufijo mostrado junto al input: "€", "%", "años". */
  unit: string;
  /** Decimales del input numérico. */
  decimals?: number;
  quick?: QuickAdjust[];
  hint?: string;
  disabled?: boolean;
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

/** "1.234,5" | "1234.5" | "12,5" → número, o null si no es válido. */
function parseDraft(raw: string): number | null {
  let s = raw.replace(/[€%\s]/g, "");
  if (s === "") return null;
  if (s.includes(",")) s = s.replace(/\./g, "").replace(",", ".");
  const n = Number(s);
  return Number.isFinite(n) ? n : null;
}

export function SliderField({
  label,
  value,
  onChange,
  min,
  max,
  step,
  scale = 1,
  unit,
  decimals = 0,
  quick,
  hint,
  disabled,
}: SliderFieldProps) {
  const id = useId();
  const focused = useRef(false);
  // Coma decimal (es-ES); parseDraft acepta coma o punto al escribir.
  const fmt = (v: number) => (v * scale).toFixed(decimals).replace(".", ",");
  const [draft, setDraft] = useState(fmt(value));

  // Sincroniza el texto cuando el valor cambia desde fuera (slider, botones), salvo mientras se escribe.
  useEffect(() => {
    if (!focused.current) setDraft(fmt(value));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, scale, decimals]);

  const parsed = parseDraft(draft);
  const inRange = parsed !== null && parsed / scale >= min - 1e-9 && parsed / scale <= max + 1e-9;
  const invalid = draft.trim() !== "" && !inRange;

  function handleText(raw: string) {
    setDraft(raw);
    const n = parseDraft(raw);
    if (n !== null && n / scale >= min - 1e-9 && n / scale <= max + 1e-9) onChange(n / scale);
  }

  function handleBlur() {
    focused.current = false;
    const n = parseDraft(draft);
    const next = n === null ? value : clamp(n / scale, min, max);
    onChange(next);
    setDraft(fmt(next));
  }

  function apply(q: QuickAdjust) {
    const next = clamp(q.set !== undefined ? q.set : value + (q.delta ?? 0), min, max);
    onChange(next);
  }

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-col gap-1.5 min-[420px]:flex-row min-[420px]:items-center min-[420px]:justify-between min-[420px]:gap-3">
        <label htmlFor={id} className="text-xs font-medium text-gray-400">
          {label}
        </label>
        <span className="flex items-center gap-1 self-end min-[420px]:self-auto">
          <input
            id={id}
            type="text"
            inputMode="decimal"
            autoComplete="off"
            value={draft}
            disabled={disabled}
            onFocus={() => {
              focused.current = true;
            }}
            onChange={(e) => handleText(e.target.value)}
            onBlur={handleBlur}
            aria-invalid={invalid}
            className={[
              "w-28 rounded-md border bg-surface-base px-2 py-1 text-right font-mono text-sm tabular-nums text-fg",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 disabled:opacity-50",
              invalid ? "border-semantic-loss" : "border-surface-border",
            ].join(" ")}
          />
          <span className="w-9 text-xs text-gray-500">{unit}</span>
        </span>
      </div>

      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={clamp(value, min, max)}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        aria-label={label}
        className="w-full accent-brand-500 disabled:opacity-50"
      />

      {(quick?.length || hint || invalid) && (
        <div className="flex flex-wrap items-center gap-1.5">
          {quick?.map((q) => (
            <button
              key={q.label}
              type="button"
              disabled={disabled}
              onClick={() => apply(q)}
              className="rounded border border-surface-border px-2.5 py-1 text-xs text-gray-300 transition-colors hover:border-gray-500 hover:bg-surface-overlay hover:text-fg active:bg-surface-overlay/70 disabled:opacity-50"
            >
              {q.label}
            </button>
          ))}
          {invalid ? (
            <span className="text-xs text-semantic-loss" role="status">
              Valor fuera de rango ({(min * scale).toFixed(decimals)}–{(max * scale).toFixed(decimals)} {unit}).
            </span>
          ) : (
            hint && <span className="text-xs text-gray-500">{hint}</span>
          )}
        </div>
      )}
    </div>
  );
}
