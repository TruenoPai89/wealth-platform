"use client";

/**
 * useChartTheme.ts — Colores de las gráficas (Recharts) según el tema activo.
 *
 * Recharts pinta con atributos SVG (stroke, fill), no con clases de Tailwind, así que los colores se leen de las
 * variables CSS de globals.css (--chart-*). Un MutationObserver sobre la clase de <html> los vuelve a leer al
 * cambiar el tema, por lo que las gráficas se repintan solas sin recargar.
 *
 * @module core-ui/useChartTheme
 */

import { useEffect, useState } from "react";

export interface ChartTheme {
  primary: string;
  secondary: string;
  tertiary: string;
  danger: string;
  axis: string;
  grid: string;
  /** Estilo de <Tooltip contentStyle>: fondo elevado translúcido con desenfoque. */
  tooltip: React.CSSProperties;
}

const FALLBACK: ChartTheme = {
  primary: "#60a5fa",
  secondary: "#2dd4bf",
  tertiary: "#fbbf24",
  danger: "#f87171",
  axis: "#94a3b8",
  grid: "#334155",
  tooltip: {
    background: "rgb(15 23 42 / 0.94)",
    border: "1px solid rgb(51 65 85)",
    color: "#f1f5f9",
    borderRadius: 10,
    fontSize: 12,
    boxShadow: "0 8px 24px rgb(0 0 0 / 0.25)",
  },
};

function read(): ChartTheme {
  const css = getComputedStyle(document.documentElement);
  const rgb = (name: string, fallback: string) => {
    const v = css.getPropertyValue(name).trim();
    return v ? `rgb(${v})` : fallback;
  };
  return {
    primary: rgb("--chart-1", FALLBACK.primary),
    secondary: rgb("--chart-2", FALLBACK.secondary),
    tertiary: rgb("--chart-3", FALLBACK.tertiary),
    danger: rgb("--chart-danger", FALLBACK.danger),
    axis: rgb("--chart-axis", FALLBACK.axis),
    grid: rgb("--chart-grid", FALLBACK.grid),
    tooltip: {
      background: `rgb(${css.getPropertyValue("--bg-card").trim() || "15 23 42"} / 0.94)`,
      border: `1px solid rgb(${css.getPropertyValue("--border-subtle").trim() || "51 65 85"})`,
      color: `rgb(${css.getPropertyValue("--text-main").trim() || "241 245 249"})`,
      borderRadius: 10,
      fontSize: 12,
      backdropFilter: "blur(8px)",
      WebkitBackdropFilter: "blur(8px)",
      boxShadow: "0 8px 24px rgb(0 0 0 / 0.25)",
    },
  };
}

export function useChartTheme(): ChartTheme {
  const [theme, setTheme] = useState<ChartTheme>(FALLBACK);

  useEffect(() => {
    setTheme(read());
    const observer = new MutationObserver(() => setTheme(read()));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
    return () => observer.disconnect();
  }, []);

  return theme;
}
