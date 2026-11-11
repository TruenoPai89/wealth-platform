"use client";

import React, { useMemo, useState } from "react";
import type { AssetPosition } from "@/core-ui/types/portfolio";
import type { Basket } from "@/core-ui/types/baskets";

interface CreateBasketModalProps {
  positions: AssetPosition[];
  onClose: () => void;
  onCreate: (basket: Basket) => void;
}

export function CreateBasketModal({ positions, onClose, onCreate }: CreateBasketModalProps) {
  const funds = positions.filter((position) => position.assetClass !== "CASH" && position.isin);
  const [name, setName] = useState("");
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<string[]>([]);
  const results = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return funds.filter((fund) => !needle || fund.name.toLowerCase().includes(needle) || fund.isin?.toLowerCase().includes(needle));
  }, [funds, query]);

  function toggle(instrumentId: string) {
    setSelected((current) => current.includes(instrumentId) ? current.filter((id) => id !== instrumentId) : [...current, instrumentId]);
  }

  function create() {
    if (!name.trim() || selected.length === 0) return;
    const weight = 1 / selected.length;
    onCreate({
      id: `custom-${Date.now()}`,
      name: name.trim(),
      description: `${selected.length} fondos seleccionados por ISIN`,
      weights: Object.fromEntries(selected.map((id) => [id, weight])),
    });
  }

  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-black/60 p-0 backdrop-blur-sm sm:items-center sm:p-4" role="dialog" aria-modal="true" aria-labelledby="create-basket-title">
      <div className="w-full max-w-xl rounded-t-2xl border border-surface-border bg-surface-raised p-5 sm:rounded-xl">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-[10px] uppercase tracking-[0.18em] text-brand-300">Nueva estrategia</p>
            <h2 id="create-basket-title" className="mt-1 text-xl font-semibold text-fg">Crear agrupación</h2>
            <p className="mt-1 text-sm text-gray-500">Busca por nombre o ISIN y crea una cesta con pesos iguales.</p>
          </div>
          <button type="button" onClick={onClose} className="text-2xl leading-none text-gray-500 hover:text-fg" aria-label="Cerrar">×</button>
        </div>
        <div className="mt-5 grid gap-3">
          <input value={name} onChange={(event) => setName(event.target.value)} placeholder="Nombre de la agrupación" className="w-full rounded-lg border border-surface-border bg-surface-base px-3 py-2 text-sm text-fg outline-none focus:ring-2 focus:ring-brand-500" />
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Buscar fondo o ISIN (ej. IE00...)" className="w-full rounded-lg border border-surface-border bg-surface-base px-3 py-2 text-sm text-fg outline-none focus:ring-2 focus:ring-brand-500" />
        </div>
        <div className="mt-3 max-h-56 overflow-y-auto rounded-lg border border-surface-border">
          {results.map((fund) => {
            const active = selected.includes(fund.instrumentId);
            return (
              <button key={fund.instrumentId} type="button" onClick={() => toggle(fund.instrumentId)} className={`flex w-full items-center justify-between gap-3 border-b border-surface-border/60 px-3 py-2.5 text-left last:border-0 ${active ? "bg-brand-500/10" : "hover:bg-surface-overlay/40"}`}>
                <span className="min-w-0"><span className="block truncate text-sm text-fg">{fund.name}</span><span className="font-mono text-[11px] text-gray-500">{fund.isin}</span></span>
                <span className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-full border text-xs ${active ? "border-brand-400 bg-brand-500 text-white" : "border-surface-border text-transparent"}`}>✓</span>
              </button>
            );
          })}
          {results.length === 0 && <p className="px-3 py-5 text-center text-sm text-gray-500">No hay fondos que coincidan.</p>}
        </div>
        <div className="mt-4 flex items-center justify-between gap-3">
          <p className="text-xs text-gray-500">{selected.length} {selected.length === 1 ? "fondo seleccionado" : "fondos seleccionados"}</p>
          <div className="flex gap-2">
            <button type="button" onClick={onClose} className="rounded-lg bg-surface-overlay px-3 py-2 text-sm text-gray-300 hover:text-fg">Cancelar</button>
            <button type="button" onClick={create} disabled={!name.trim() || selected.length === 0} className="rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-500 disabled:cursor-not-allowed disabled:opacity-50">Crear agrupación</button>
          </div>
        </div>
      </div>
    </div>
  );
}
