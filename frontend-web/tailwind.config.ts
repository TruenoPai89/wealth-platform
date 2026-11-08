import type { Config } from "tailwindcss";

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
          base:    "#0f1117",
          raised:  "#1a1d27",
          overlay: "#22263a",
          border:  "#2e3348",
        },
        semantic: {
          gain:    "#22c55e",
          loss:    "#ef4444",
          warning: "#f59e0b",
          pledged: "#a855f7",
          neutral: "#6b7280",
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
