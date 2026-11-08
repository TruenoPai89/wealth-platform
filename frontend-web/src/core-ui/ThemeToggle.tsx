"use client";

/**
 * ThemeToggle.tsx — Botón de la barra superior para alternar entre modo oscuro y claro.
 *
 * El tema real vive en la clase de <html> (lo fija el script de layout.tsx antes de pintar); aquí solo se lee
 * al montar y se cambia al pulsar. Hasta montar no se pinta el icono, para no mostrar uno equivocado.
 *
 * @module core-ui/ThemeToggle
 */

import React, { useEffect, useState } from "react";
import { applyTheme, currentTheme, type Theme } from "@/core-ui/theme";

function SunIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
    </svg>
  );
}

export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme | null>(null);

  useEffect(() => {
    setTheme(currentTheme());
  }, []);

  function toggle() {
    const next: Theme = (theme ?? currentTheme()) === "dark" ? "light" : "dark";
    applyTheme(next);
    setTheme(next);
  }

  const isDark = theme === "dark";
  return (
    <button
      type="button"
      onClick={toggle}
      data-testid="theme-toggle"
      role="switch"
      aria-checked={theme === null ? undefined : isDark}
      aria-label={isDark ? "Cambiar a modo claro" : "Cambiar a modo oscuro"}
      title={isDark ? "Modo claro" : "Modo oscuro"}
      className="p-2 rounded-md text-gray-400 hover:text-fg hover:bg-surface-overlay transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
    >
      {/* El icono muestra a qué tema se cambiará */}
      <span className="block w-[18px] h-[18px]">{theme === null ? null : isDark ? <SunIcon /> : <MoonIcon />}</span>
    </button>
  );
}
