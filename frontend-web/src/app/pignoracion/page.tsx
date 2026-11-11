
import { AppShell } from "@/core-ui/AppShell";
import { MarginSimulator } from "@/mod4-pledge-ui/MarginSimulator";
import { PledgePanel } from "@/mod4-pledge-ui/PledgePanel";

export default function PignoracionPage() {
  return (
    <AppShell
      clientName="Familia Mendoza Ruiz"
      activeNavId="pledge"
    >
      <div className="flex flex-col gap-10">
        <MarginSimulator portfolioId="PF-001" />

        <div className="border-t border-surface-border pt-8">
          <h2 className="mb-4 text-xl font-semibold text-fg">
            O analiza tu contrato de pignoración (PDF / texto)
          </h2>
          <PledgePanel portfolioId="PF-001" />
        </div>
      </div>
    </AppShell>
  );
}
