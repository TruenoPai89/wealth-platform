"use client";

/**
 * SlotRegistry.tsx — Motor de Inyección de Componentes (Patrón de Slots)
 *
 * PROBLEMA QUE RESUELVE:
 *   La tabla de posiciones (Módulo 1) necesita renderizar elementos que aún
 *   no existen (badges fiscales del Módulo 2, candados del Módulo 4, etc.)
 *   sin crear acoplamiento directo entre módulos.
 *
 * CÓMO FUNCIONA:
 *   1. `SlotRegistry` es un Context de React que actúa como registro central.
 *   2. Los módulos futuros (mod2-tax-ui, mod4-pledge-ui) llaman a `useRegisterSlot`
 *      para "enchufar" su componente en un hueco con nombre.
 *   3. La tabla (Módulo 1) usa `<Slot name="StatusBadgeSlot" />` para renderizar
 *      lo que sea que esté registrado en ese hueco en ese momento.
 *   4. Si nadie ha registrado nada → el slot renderiza null (invisible, sin errores).
 *
 * SLOTS DEFINIDOS (Módulo 1):
 *   - "StatusBadgeSlot"  → Badge de estado por fila (fiscal, pignoración, etc.)
 *   - "ActionRowSlot"    → Botones/acciones extra por fila (Módulo 2: Tax-loss, etc.)
 *   - "HeaderKpiSlot"    → KPIs adicionales en la cabecera de la tabla
 *
 * USO — Módulo 2 (cuando esté listo):
 *   ```tsx
 *   // En mod2-tax-ui/TaxModuleBootstrap.tsx:
 *   useRegisterSlot("StatusBadgeSlot", TwoMonthWarningBadge);
 *   ```
 *
 * @module core-ui/SlotRegistry
 */

import React, {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
} from "react";
import type { AssetPosition } from "@/core-ui/types/portfolio";

// ─── Tipos públicos ───────────────────────────────────────────────────────────

/** Nombre canónico de los slots disponibles en la tabla de posiciones */
export type SlotName =
  | "StatusBadgeSlot"   // Badge de estado por fila (fiscal, pignoración...)
  | "ActionRowSlot"     // Acciones extra por fila
  | "HeaderKpiSlot";    // KPIs adicionales en la cabecera de la tabla

/** Props que todos los componentes inyectados en un slot de fila recibirán */
export interface SlotRowProps {
  position: AssetPosition;
}

/** Props que los componentes inyectados en HeaderKpiSlot recibirán */
export interface SlotHeaderProps {
  totalValue: number;
  baseCurrency: string;
}

/** Tipo del componente React aceptado por un slot */
export type SlotComponent<P extends object = object> =
  React.ComponentType<P>;

/** Entrada en el registro: nombre del slot + componente + orden de renderizado */
interface SlotEntry {
  name: SlotName;
  component: SlotComponent;
  /** Prioridad de renderizado cuando hay varios registros en el mismo slot. Menor = primero. */
  order: number;
}

// ─── Contexto interno ────────────────────────────────────────────────────────

interface SlotRegistryContextValue {
  entries: SlotEntry[];
  registerSlot: (entry: SlotEntry) => void;
  unregisterSlot: (name: SlotName, component: SlotComponent) => void;
}

const SlotRegistryContext = createContext<SlotRegistryContextValue | null>(null);

SlotRegistryContext.displayName = "SlotRegistryContext";

// ─── Provider ────────────────────────────────────────────────────────────────

interface SlotRegistryProviderProps {
  children: React.ReactNode;
}

export function SlotRegistryProvider({ children }: SlotRegistryProviderProps) {
  const [entries, setEntries] = useState<SlotEntry[]>([]);

  const registerSlot = useCallback((entry: SlotEntry) => {
    setEntries((prev) => {
      // Evitar duplicados exactos (mismo nombre + mismo componente)
      const isDuplicate = prev.some(
        (e) => e.name === entry.name && e.component === entry.component,
      );
      if (isDuplicate) return prev;
      return [...prev, entry].sort((a, b) => a.order - b.order);
    });
  }, []);

  const unregisterSlot = useCallback(
    (name: SlotName, component: SlotComponent) => {
      setEntries((prev) =>
        prev.filter((e) => !(e.name === name && e.component === component)),
      );
    },
    [],
  );

  const value = useMemo(
    () => ({ entries, registerSlot, unregisterSlot }),
    [entries, registerSlot, unregisterSlot],
  );

  return (
    <SlotRegistryContext.Provider value={value}>
      {children}
    </SlotRegistryContext.Provider>
  );
}

// ─── Hook de acceso al contexto ───────────────────────────────────────────────

function useSlotRegistry(): SlotRegistryContextValue {
  const ctx = useContext(SlotRegistryContext);
  if (!ctx) {
    throw new Error(
      "[SlotRegistry] useSlotRegistry debe usarse dentro de <SlotRegistryProvider>. " +
        "Asegúrate de que AppShell envuelve la aplicación con el provider.",
    );
  }
  return ctx;
}

// ─── Hook público: registrar un componente en un slot ────────────────────────

/**
 * Hook para que los módulos futuros registren sus componentes en los slots.
 *
 * @example
 * // En mod2-tax-ui/TwoMonthWarningBadge.tsx (o en su bootstrap):
 * useRegisterSlot("StatusBadgeSlot", TwoMonthWarningBadge, { order: 1 });
 */
export function useRegisterSlot(
  name: SlotName,
  component: SlotComponent,
  options: { order?: number } = {},
): void {
  const { registerSlot, unregisterSlot } = useSlotRegistry();
  const { order = 10 } = options;

  // Usamos un efecto para registrar al montar y limpiar al desmontar.
  // La dependencia es estable porque los componentes son referencias fijas.
  React.useEffect(() => {
    registerSlot({ name, component, order });
    return () => {
      unregisterSlot(name, component);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, order]); // component es una referencia estable (definición de módulo)
}

// ─── Componente público: renderizar un slot ───────────────────────────────────

interface SlotProps<P extends object = object> {
  name: SlotName;
  /** Props que se pasarán a todos los componentes registrados en este slot */
  slotProps?: P;
  /** Renderizado por defecto si ningún módulo ha registrado nada en este slot */
  fallback?: React.ReactNode;
}

/**
 * Componente que renderiza todos los componentes registrados en un slot dado.
 * Si el slot está vacío, renderiza `fallback` (por defecto: null).
 *
 * @example
 * // En PositionsTableWithSlots.tsx:
 * <Slot name="StatusBadgeSlot" slotProps={{ position }} />
 */
export function Slot<P extends object = object>({
  name,
  slotProps,
  fallback = null,
}: SlotProps<P>) {
  const { entries } = useSlotRegistry();

  const slotEntries = entries.filter((e) => e.name === name);

  if (slotEntries.length === 0) return <>{fallback}</>;

  return (
    <>
      {slotEntries.map((entry, index) => {
        const Component = entry.component as React.ComponentType<P>;
        return (
          <Component
            key={`${entry.name}-${index}`}
            {...(slotProps ?? ({} as P))}
          />
        );
      })}
    </>
  );
}
