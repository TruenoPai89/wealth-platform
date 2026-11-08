import { AppShell } from "@/core-ui/AppShell";
import { RebalanceDashboard } from "@/mod1-rebalancer-ui/RebalanceDashboard";

export default function HomePage() {
  return (
    <AppShell clientName="CLI-00089" activeNavId="rebalancer">
      <RebalanceDashboard portfolioId="PF-001" />
    </AppShell>
  );
}
