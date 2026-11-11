import { AppShell } from "@/core-ui/AppShell";
import { AnnualTaxReportPanel } from "@/mod3-irpf-ui/AnnualTaxReportPanel";
import { IrpfSalePanel } from "@/mod3-irpf-ui/IrpfSalePanel";

export default function IrpfPage() {
  return (
    <AppShell clientName="Familia Mendoza Ruiz" activeNavId="irpf">
      <AnnualTaxReportPanel portfolioId="PF-001" fiscalYear={2026} />
      <IrpfSalePanel portfolioId="PF-001" />
    </AppShell>
  );
}
