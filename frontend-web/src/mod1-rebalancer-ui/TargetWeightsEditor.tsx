"use client";

/**
 * TargetWeightsEditor.tsx — El cliente edita su meta de balanceo (peso objetivo por activo).
 *
 * - Un campo en % por posición (el efectivo también es un peso objetivo más).
 * - La suma debe ser exactamente 100 %: el botón Guardar solo se activa entonces.
 * - Guardar → PUT /portfolios/{id}/targets (el backend vuelve a validar) y el padre recalcula el plan.
 *
 * Trabaja en centésimas de punto porcentual (enteros) para evitar errores de coma flotante al sumar.
 *
 * @module mod1-rebalancer-ui/TargetWeightsEditor
 */

import React, { useMemo, useState } from "react";
import type { RebalancePositionRow } from "@/core-ui/types/rebalancePlan";

interface TargetWeightsEditorProps {
  positions: RebalancePositionRow[];
  /** Recibe instrumentId → peso (0..1). Debe rechazar (throw) si el backend no lo acepta. */
  onSave: (weights: Record<string, number>) => Promise<void>;
  onCancel: () => void;
}

/** "35,5" | "35.5" → centésimas de % (3550). null si no es un número válido entre 0 y 100. */
function parseHundredths(raw: string): number | null {
  const n = Number(raw.replace(",", ".").trim());
  if (raw.trim() === "" || !Number.isFinite(n) || n < 0 || n > 100) return null;
  return Math.round(n * 100);
}

function toInitialText(weight: number): string {
  return (Math.round(weight * 10000) / 100).toFixed(2);
}

export function TargetWeightsEditor({
  positions,
  onSave,
  onCancel,
}: TargetWeightsEditorProps) {
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      positions.map((p) => [p.instrumentId, toInitialText(p.targetWeight)]),
    ),
  );
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const { totalHundredths, allValid } = useMemo(() => {
    let total = 0;
    let valid = true;
    for (const p of positions) {
      const h = parseHundredths(values[p.instrumentId] ?? "");
      if (h === null) valid = false;
      else total += h;
    }
    return { totalHundredths: total, allValid: valid };
  }, [positions, values]);

  const sumOk = allValid && totalHundredths === 10000;
  const totalPct = (totalHundredths / 100).toFixed(2).replace(".", ",");

  async function handleSave() {
    setSaving(true);
    setSaveError(null);
    try {
      const weights: Record<string, number> = {};
      for (const p of positions) {
        weights[p.instrumentId] =
          (parseHundredths(values[p.instrumentId] ?? "") ?? 0) / 10000;
      }
      await onSave(weights);
    } catch (err) {
      setSaveError(err instanceof Error ? err.message : "No se pudo guardar la meta.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <section
      className="bg-surface-raised border border-surface-border rounded-xl p-5 flex flex-col gap-4"
      aria-label="Editor de meta de balanceo"
    >
      <div>
        <h2 className="text-lg font-semibold text-fg">Meta de balanceo</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          Define qué porcentaje de tu cartera quieres en cada activo. Debe sumar 100 %.
        </p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        {positions.map((p) => {
          const invalid = parseHundredths(values[p.instrumentId] ?? "") === null;
          return (
            <label
              key={p.instrumentId}
              className="flex items-center justify-between gap-3 bg-surface-overlay/40 rounded-lg px-3 py-2"
            >
              <span className="flex flex-col min-w-0">
                <span className="text-sm text-fg truncate">{p.name}</span>
                <span className="text-xs text-gray-500 font-mono">{p.instrumentId}</span>
              </span>
              <span className="flex items-center gap-1 shrink-0">
                <input
                  type="text"
                  inputMode="decimal"
                  aria-label={`Peso objetivo de ${p.name}`}
                  value={values[p.instrumentId] ?? ""}
                  onChange={(e) =>
                    setValues((prev) => ({ ...prev, [p.instrumentId]: e.target.value }))
                  }
                  className={[
                    "w-20 text-right font-mono text-sm bg-surface-base border rounded-md px-2 py-1 text-fg",
                    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500",
                    invalid ? "border-semantic-loss" : "border-surface-border",
                  ].join(" ")}
                />
                <span className="text-sm text-gray-500">%</span>
              </span>
            </label>
          );
        })}
      </div>

      <div className="flex items-center justify-between gap-3 flex-wrap">
        <p
          className={`text-sm font-medium ${sumOk ? "text-semantic-gain" : "text-semantic-loss"}`}
          role="status"
        >
          Suma: {allValid ? `${totalPct} %` : "valores no válidos"}
          {sumOk ? " ✓" : " — debe ser 100,00 %"}
        </p>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={onCancel}
            disabled={saving}
            className="px-4 py-2 rounded-lg text-sm font-medium bg-surface-overlay text-gray-300 hover:text-fg disabled:opacity-50"
          >
            Cancelar
          </button>
          <button
            type="button"
            onClick={() => void handleSave()}
            disabled={!sumOk || saving}
            className="px-4 py-2 rounded-lg text-sm font-medium bg-brand-600 text-white hover:bg-brand-500 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {saving ? "Guardando..." : "Guardar y rebalancear"}
          </button>
        </div>
      </div>

      {saveError && (
        <p className="text-sm text-semantic-loss" role="alert">
          {saveError}
        </p>
      )}
    </section>
  );
}
