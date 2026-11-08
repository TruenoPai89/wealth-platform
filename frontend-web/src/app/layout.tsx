import type { Metadata } from "next";
import "./globals.css";

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
    <html lang="es" className="dark">
      <body className="bg-surface-base text-white antialiased">
        {children}
      </body>
    </html>
  );
}
