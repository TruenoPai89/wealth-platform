import type { Metadata } from "next";
import "./globals.css";
import { THEME_INIT_SCRIPT } from "@/core-ui/theme";

export const metadata: Metadata = {
  title: "Wealth Platform — Plataforma Patrimonial Integrada",
  description:
    "Gestión integral de carteras: balanceo, optimización fiscal, declaración IRPF y control de pignoración.",
};

interface RootLayoutProps {
  children: React.ReactNode;
}

export default function RootLayout({ children }: RootLayoutProps) {
  return (
    // suppressHydrationWarning: el script de tema cambia la clase de <html> antes de que React hidrate.
    <html lang="es" className="dark" suppressHydrationWarning>
      <head>
        {/* Aplica el tema guardado ANTES del primer pintado (evita el parpadeo oscuro→claro) */}
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
      </head>
      <body className="bg-surface-base text-fg antialiased">
        {children}
      </body>
    </html>
  );
}
