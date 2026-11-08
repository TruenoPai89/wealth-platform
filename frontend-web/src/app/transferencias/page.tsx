/**
 * page.tsx — Ruta "/transferencias": historial de movimientos del rebalanceo.
 *
 * Mismo AppShell que la ruta raíz; solo cambia el item activo del menú lateral.
 */

import { AppShell } from "@/core-ui/AppShell";
import { TransfersHistory } from "@/mod1-rebalancer-ui/TransfersHistory";

export default function TransfersPage() {
  return (
    <AppShell clientName="Familia Mendoza Ruiz" activeNavId="transfers">
      <TransfersHistory portfolioId="PF-001" />
    </AppShell>
  );
}
