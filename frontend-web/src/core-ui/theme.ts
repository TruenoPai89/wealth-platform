/**
 * theme.ts — Tema claro/oscuro de la plataforma.
 *
 * La clase de <html> (`dark` | `light`) activa las variables CSS de globals.css. La elección se guarda en
 * localStorage ("wealth-theme"); sin elección previa se respeta prefers-color-scheme del sistema y, si no
 * se puede leer, se usa el oscuro (el tema original de la plataforma).
 *
 * @module core-ui/theme
 */

export type Theme = "dark" | "light";

export const THEME_STORAGE_KEY = "wealth-theme";

/** Script inline para <head>: fija la clase antes del primer pintado. Debe ser autocontenido y no lanzar. */
export const THEME_INIT_SCRIPT = `(function(){try{var t=localStorage.getItem("${THEME_STORAGE_KEY}");if(t!=="light"&&t!=="dark"){t=window.matchMedia&&window.matchMedia("(prefers-color-scheme: light)").matches?"light":"dark"}var c=document.documentElement.classList;c.remove("dark","light");c.add(t)}catch(e){}})();`;

/** Tema aplicado ahora mismo en <html> (solo cliente). */
export function currentTheme(): Theme {
  return document.documentElement.classList.contains("light") ? "light" : "dark";
}

/** Aplica y guarda el tema. */
export function applyTheme(theme: Theme): void {
  const classes = document.documentElement.classList;
  // La transición suave de colores solo existe mientras se cambia de tema (ver globals.css): así no anima los hover.
  classes.add("theme-transition");
  window.setTimeout(() => classes.remove("theme-transition"), 250);
  classes.remove("dark", "light");
  classes.add(theme);
  try {
    localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    /* almacenamiento no disponible (modo privado): el tema solo dura la sesión */
  }
}
