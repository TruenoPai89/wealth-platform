
import { AppShell } from "@/core-ui/AppShell";
import { PledgePanel } from "@/mod4-pledge-ui/PledgePanel";

export default function PignoracionPage() {
  return (
    <AppShell
      clientName="Familia Mendoza Ruiz"
      activeNavId="pledge"
    >
      <PledgePanel portfolioId="PF-001" />
    </AppShell>
  );
}
