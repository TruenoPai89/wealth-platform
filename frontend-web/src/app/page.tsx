import { AppShell } from "@/core-ui/AppShell";
import { RebalanceDashboard } from "@/mod1-rebalancer-ui/RebalanceDashboard";

export default function HomePage() {
  return (
    <AppShell clientName="Familia Mendoza Ruiz" activeNavId="rebalancer">
      {/* PF-001 = cartera del mock de OpenJEV que rebalancea el backend */}
      <RebalanceDashboard portfolioId="PF-001" />
    </AppShell>
  );
}
