import type { Config } from "tailwindcss";

/**
 * Tema claro/oscuro: los colores de superficie, texto y gris de la UI son VARIABLES CSS (definidas en
 * src/app/globals.css para :root/.dark y .light) con formato "R G B" para que sigan funcionando las opacidades
 * (bg-surface-overlay/50, text-gray-500/80...). Cambiar de tema = cambiar la clase de <html>; ningún componente
 * necesita variantes dark:.
 */
const withVar = (name: string) => `rgb(var(${name}) / <alpha-value>)`;

const config: Config = {
  content: [
    "./src/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      colors: {
        // Design tokens de la plataforma patrimonial
        brand: {
          50:  "#f0f4ff",
          100: "#dde6ff",
          200: "#c0cfff",
          300: "#96aeff",
          400: "#6482fd",
          500: "#3f56f9",
          600: "#2832ee",
          700: "#2026da",
          800: "#1e22b1",
          900: "#1e228b",
          950: "#141455",
        },
        surface: {
          base:    withVar("--surface-base"),
          raised:  withVar("--surface-raised"),
          overlay: withVar("--surface-overlay"),
          border:  withVar("--surface-border"),
        },
        // Texto principal (blanco en oscuro, casi negro en claro). El texto sobre fondos de marca sigue siendo text-white.
        fg: withVar("--fg"),
        // Escala de grises de texto: se invierte en el tema claro para mantener el contraste sobre fondo blanco.
        gray: {
          200: withVar("--gray-200"),
          300: withVar("--gray-300"),
          400: withVar("--gray-400"),
          500: withVar("--gray-500"),
          600: withVar("--gray-600"),
          700: withVar("--gray-700"),
        },
        semantic: {
          gain:    withVar("--c-gain"),
          loss:    withVar("--c-loss"),
          warning: withVar("--c-warning"),
          pledged: withVar("--c-pledged"),
          neutral: "rgb(107 114 128 / <alpha-value>)",
        },
      },
      fontFamily: {
        sans: ["Inter", "ui-sans-serif", "system-ui", "sans-serif"],
        mono: ["JetBrains Mono", "ui-monospace", "monospace"],
      },
    },
  },
  plugins: [],
};

export default config;
