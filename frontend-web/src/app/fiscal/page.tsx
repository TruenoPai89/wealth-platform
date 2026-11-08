import { AppShell } from "@/core-ui/AppShell";
import { TaxDashboard } from "@/mod2-tax-ui/TaxDashboard";

export default function FiscalPage() {
  return (
    <AppShell clientName="Familia Mendoza Ruiz" activeNavId="tax">
      <TaxDashboard portfolioId="PF-001" />
    </AppShell>
  );
}
