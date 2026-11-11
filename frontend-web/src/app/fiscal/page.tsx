import { AppShell } from "@/core-ui/AppShell";
import { TaxDashboard } from "@/mod2-tax-ui/TaxDashboard";
import { IrpfSalePanel } from "@/mod3-irpf-ui/IrpfSalePanel";

export default function FiscalPage() {
  return (
    <AppShell clientName="Familia Mendoza Ruiz" activeNavId="tax">
      <TaxDashboard portfolioId="PF-001" />
      <IrpfSalePanel portfolioId="PF-001" />
    </AppShell>
  );
}
