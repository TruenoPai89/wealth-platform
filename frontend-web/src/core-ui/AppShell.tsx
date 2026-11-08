"use client";

/**
 * AppShell.tsx — Layout principal de la Plataforma Patrimonial
 *
 * Estructura:
 *   ┌─────────────────────────────────────────────────────────┐
 *   │  Topbar  (logo, cliente activo, perfil)                 │
 *   ├──────────┬──────────────────────────────────────────────┤
 *   │ Sidebar  │  <children> (área de contenido principal)    │
 *   │ (nav)    │                                              │
 *   └──────────┴──────────────────────────────────────────────┘
 *
 * El SlotRegistryProvider envuelve todo para que los slots sean
 * accesibles desde cualquier punto del árbol de componentes.
 *
 * @module core-ui/AppShell
 */

import React, { useState } from "react";
import Link from "next/link";
import { SlotRegistryProvider } from "@/core-ui/SlotRegistry";

// ─── Tipos ───────────────────────────────────────────────────────────────────

interface NavItem {
  id: string;
  label: string;
  icon: React.ReactNode;
  /** Módulo que activa este item. Para fases futuras. */
  modulePhase: 1 | 2 | 3 | 4;
  isActive?: boolean;
  /** Ruta de Next.js. Los items sin href todavía no son navegables (se muestran como "Próx."). */
  href?: string;
}

interface AppShellProps {
  children: React.ReactNode;
  /** Nombre del cliente activo, mostrado en la topbar */
  clientName?: string;
  /** ID del item de navegación activo */
  activeNavId?: string;
}

// ─── Iconos SVG inline (sin dependencias externas) ──────────────────────────

function IconBalancer() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 3v18M3 9l9-6 9 6M3 15l9 6 9-6" />
    </svg>
  );
}

function IconTax() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="3" width="18" height="18" rx="2" />
      <path d="M9 9h6M9 12h6M9 15h4" />
    </svg>
  );
}

function IconIrpf() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <polyline points="14 2 14 8 20 8" />
      <line x1="16" y1="13" x2="8" y2="13" />
      <line x1="16" y1="17" x2="8" y2="17" />
      <polyline points="10 9 9 9 8 9" />
    </svg>
  );
}

function IconPledge() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  );
}

function IconTransfers() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17 3l4 4-4 4" />
      <path d="M3 7h18" />
      <path d="M7 21l-4-4 4-4" />
      <path d="M21 17H3" />
    </svg>
  );
}

function IconChevronLeft() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="15 18 9 12 15 6" />
    </svg>
  );
}

function IconChevronRight() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="9 18 15 12 9 6" />
    </svg>
  );
}

// ─── Definición de la navegación ─────────────────────────────────────────────

const NAV_ITEMS: NavItem[] = [
  {
    id: "rebalancer",
    label: "Mis Inversiones",
    icon: <IconBalancer />,
    modulePhase: 1,
    href: "/",
  },
  {
    id: "transfers",
    label: "Transferencias",
    icon: <IconTransfers />,
    modulePhase: 1,
    href: "/transferencias",
  },
  {
    id: "tax",
    label: "Optimizador Fiscal",
    icon: <IconTax />,
    modulePhase: 2,
    href: "/fiscal",
  },
  {
    id: "irpf",
    label: "Declaración IRPF",
    icon: <IconIrpf />,
    modulePhase: 3,
  },
  {
    id: "pledge",
    label: "Pignoración / LTV",
    icon: <IconPledge />,
    modulePhase: 4,
  },
];

// ─── Sub-componente: Topbar ───────────────────────────────────────────────────

interface TopbarProps {
  clientName: string;
  sidebarCollapsed: boolean;
  onToggleSidebar: () => void;
}

function Topbar({ clientName, sidebarCollapsed, onToggleSidebar }: TopbarProps) {
  return (
    <header className="h-14 bg-surface-raised border-b border-surface-border flex items-center px-4 gap-4 shrink-0 z-10">
      {/* Logo / nombre plataforma */}
      <div className="flex items-center gap-2 min-w-0">
        <div className="w-8 h-8 rounded-lg bg-brand-600 flex items-center justify-center shrink-0">
          <span className="text-white font-bold text-sm">W</span>
        </div>
        {!sidebarCollapsed && (
          <span className="text-white font-semibold text-sm truncate hidden sm:block">
            Wealth Platform
          </span>
        )}
      </div>

      {/* Separador */}
      <div className="h-6 w-px bg-surface-border hidden sm:block" />

      {/* Cliente activo */}
      <div className="flex items-center gap-2 flex-1 min-w-0">
        <div className="w-2 h-2 rounded-full bg-semantic-gain shrink-0" aria-hidden="true" />
        <span className="text-gray-400 text-xs hidden sm:block">Cliente activo:</span>
        <span className="text-white text-sm font-medium truncate">{clientName}</span>
      </div>

      {/* Toggle sidebar (mobile) */}
      <button
        onClick={onToggleSidebar}
        className="ml-auto p-2 rounded-md text-gray-400 hover:text-white hover:bg-surface-overlay transition-colors"
        aria-label={sidebarCollapsed ? "Expandir navegación" : "Colapsar navegación"}
      >
        {sidebarCollapsed ? <IconChevronRight /> : <IconChevronLeft />}
      </button>
    </header>
  );
}

// ─── Sub-componente: SidebarNavItem ──────────────────────────────────────────

interface SidebarNavItemProps {
  item: NavItem;
  isActive: boolean;
  collapsed: boolean;
  onClick: (id: string) => void;
}

function SidebarNavItem({ item, isActive, collapsed, onClick }: SidebarNavItemProps) {
  // Navegable = tiene ruta. Los módulos 2-4 siguen como "Próx." hasta que existan sus pantallas.
  const isDisabled = !item.href;

  const className = [
    "w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium",
    "transition-colors duration-150",
    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500",
    isActive
      ? "bg-brand-600 text-white"
      : isDisabled
        ? "text-gray-600 cursor-not-allowed opacity-50"
        : "text-gray-400 hover:text-white hover:bg-surface-overlay",
  ].join(" ");

  const content = (
    <>
      <span className="shrink-0">{item.icon}</span>
      {!collapsed && (
        <span className="truncate flex-1 text-left">{item.label}</span>
      )}
      {!collapsed && isDisabled && (
        <span className="text-xs bg-surface-overlay text-gray-500 px-1.5 py-0.5 rounded shrink-0">
          Próx.
        </span>
      )}
    </>
  );

  return (
    <li>
      {item.href ? (
        <Link
          href={item.href}
          onClick={() => onClick(item.id)}
          className={className}
          title={item.label}
          aria-current={isActive ? "page" : undefined}
        >
          {content}
        </Link>
      ) : (
        <button
          disabled
          className={className}
          title={`Módulo ${item.modulePhase} — Próximamente`}
        >
          {content}
        </button>
      )}
    </li>
  );
}

// ─── Sub-componente: Sidebar ─────────────────────────────────────────────────

interface SidebarProps {
  collapsed: boolean;
  activeNavId: string;
  onNavClick: (id: string) => void;
}

function Sidebar({ collapsed, activeNavId, onNavClick }: SidebarProps) {
  return (
    <aside
      className={[
        "bg-surface-raised border-r border-surface-border",
        "flex flex-col py-4 shrink-0 overflow-hidden",
        "transition-all duration-200",
        collapsed ? "w-16" : "w-56",
      ].join(" ")}
      aria-label="Navegación principal"
    >
      <nav className="flex-1 px-2">
        <ul className="space-y-1">
          {NAV_ITEMS.map((item) => (
            <SidebarNavItem
              key={item.id}
              item={item}
              isActive={item.id === activeNavId}
              collapsed={collapsed}
              onClick={onNavClick}
            />
          ))}
        </ul>
      </nav>

      {/* Versión / info en footer del sidebar */}
      {!collapsed && (
        <div className="px-4 mt-4 pt-4 border-t border-surface-border">
          <p className="text-xs text-gray-600">Plataforma Patrimonial</p>
          <p className="text-xs text-gray-700">v0.1.0 — Módulo 1 (beta)</p>
        </div>
      )}
    </aside>
  );
}

// ─── Componente principal: AppShell ──────────────────────────────────────────

export function AppShell({
  children,
  clientName = "Sin cliente seleccionado",
  activeNavId = "rebalancer",
}: AppShellProps) {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [currentNavId, setCurrentNavId] = useState(activeNavId);

  const handleToggleSidebar = () => setSidebarCollapsed((prev) => !prev);
  const handleNavClick = (id: string) => setCurrentNavId(id);

  return (
    // SlotRegistryProvider envuelve TODA la app para que los slots sean
    // accesibles desde cualquier módulo que esté montado en el árbol.
    <SlotRegistryProvider>
      <div className="flex flex-col h-screen bg-surface-base text-white overflow-hidden">
        {/* Topbar */}
        <Topbar
          clientName={clientName}
          sidebarCollapsed={sidebarCollapsed}
          onToggleSidebar={handleToggleSidebar}
        />

        {/* Cuerpo: sidebar + contenido */}
        <div className="flex flex-1 overflow-hidden">
          <Sidebar
            collapsed={sidebarCollapsed}
            activeNavId={currentNavId}
            onNavClick={handleNavClick}
          />

          {/* Área de contenido principal */}
          <main
            className="flex-1 overflow-y-auto p-6 bg-surface-base"
            aria-label="Contenido principal"
          >
            {children}
          </main>
        </div>
      </div>
    </SlotRegistryProvider>
  );
}
