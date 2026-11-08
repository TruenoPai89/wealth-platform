/**
 * page.tsx — Ruta raíz "/" de la aplicación (Server Component)
 *
 * En Next.js App Router, este es un Server Component por defecto.
 * El AppShell y el RebalanceDashboard son Client Components ("use client"),
 * así que los importamos aquí y Next.js gestiona la hidratación.
 *
 * NOTA: En el futuro, esta ruta podría redirigir a /dashboard o incluir
 * autenticación. Por ahora, sirve directamente el Módulo 1.
 */

import { AppShell } from "@/core-ui/AppShell";
import { RebalanceDashboard } from "@/mod1-rebalancer-ui/RebalanceDashboard";

export default function HomePage() {
  return (
    <AppShell
      clientName="Familia Mendoza Ruiz"
      activeNavId="rebalancer"
    >
      <RebalanceDashboard portfolioId="PTF-00142" />
    </AppShell>
  );
}
