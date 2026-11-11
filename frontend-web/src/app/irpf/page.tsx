import { AppShell } from "@/core-ui/AppShell";
import { AnnualTaxReportPanel } from "@/mod3-irpf-ui/AnnualTaxReportPanel";

export default function IrpfPage() {
  return (
    <AppShell clientName="Familia Mendoza Ruiz" activeNavId="irpf">
      <AnnualTaxReportPanel portfolioId="PF-001" fiscalYear={2026} />
    </AppShell>
  );
}
