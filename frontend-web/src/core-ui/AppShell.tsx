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

import React, { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { SlotRegistryProvider } from "@/core-ui/SlotRegistry";
import { ThemeToggle } from "@/core-ui/ThemeToggle";

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

function IconMenu() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <line x1="3" y1="6" x2="21" y2="6" />
      <line x1="3" y1="12" x2="21" y2="12" />
      <line x1="3" y1="18" x2="21" y2="18" />
    </svg>
  );
}

function IconClose() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <line x1="18" y1="6" x2="6" y2="18" />
      <line x1="6" y1="6" x2="18" y2="18" />
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
    href: "/irpf",
  },
  {
    id: "pledge",
    label: "Pignoración / LTV",
    icon: <IconPledge />,
    modulePhase: 4,
    href: "/pignoracion"
  },
];

// ─── Sub-componente: Topbar ───────────────────────────────────────────────────

interface TopbarProps {
  clientName: string;
  sidebarCollapsed: boolean;
  onToggleSidebar: () => void;
  mobileNavOpen: boolean;
  onToggleMobileNav: () => void;
}

const ICON_BUTTON = [
  "p-2 rounded-md text-gray-400 transition-colors",
  "hover:text-fg hover:bg-surface-overlay active:bg-surface-overlay/70",
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent",
].join(" ");

function Topbar({ clientName, sidebarCollapsed, onToggleSidebar, mobileNavOpen, onToggleMobileNav }: TopbarProps) {
  return (
    <header className="h-14 bg-white/50 dark:bg-slate-950/50 backdrop-blur-md border-b border-slate-200/80 dark:border-slate-800 flex items-center px-3 sm:px-4 gap-2 sm:gap-4 shrink-0 z-20">
      {/* Menú hamburguesa: solo smartphones y tablets pequeñas (abre el drawer) */}
      <button
        type="button"
        onClick={onToggleMobileNav}
        className={`${ICON_BUTTON} md:hidden`}
        aria-label={mobileNavOpen ? "Cerrar menú de navegación" : "Abrir menú de navegación"}
        aria-expanded={mobileNavOpen}
        aria-controls="mobile-nav"
        data-testid="mobile-nav-toggle"
      >
        {mobileNavOpen ? <IconClose /> : <IconMenu />}
      </button>

      {/* Logo / nombre plataforma */}
      <div className="flex items-center gap-2 min-w-0">
        <div className="w-8 h-8 rounded-lg bg-brand-600 flex items-center justify-center shrink-0">
          <span className="text-white font-bold text-sm">W</span>
        </div>
        <span
          className={[
            "text-fg font-semibold text-sm truncate hidden sm:block",
            // En escritorio, con el menú colapsado, se oculta para ganar espacio; en móvil siempre se ve.
            sidebarCollapsed ? "md:hidden" : "",
          ].join(" ")}
        >
          Wealth Platform
        </span>
      </div>

      {/* Separador */}
      <div className="h-6 w-px bg-surface-border hidden sm:block" />

      {/* Cliente activo */}
      <div className="flex items-center gap-2 flex-1 min-w-0">
        <span className="relative flex h-2 w-2 shrink-0" aria-hidden="true">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
        </span>
        <span className="text-gray-400 text-xs hidden sm:block">Cliente activo:</span>
        <span className="text-fg text-sm font-medium truncate">{clientName}</span>
      </div>

      {/* Modo claro / oscuro */}
      <div className="ml-auto">
        <ThemeToggle />
      </div>

      {/* Colapsar / expandir la barra lateral (solo escritorio; en móvil se usa el drawer) */}
      <button
        type="button"
        onClick={onToggleSidebar}
        className={`${ICON_BUTTON} hidden md:inline-flex`}
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
    "relative w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm font-medium",
    "transition-colors duration-150",
    "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent",
    isActive
      ? "bg-brand-600 text-white shadow-sm shadow-brand-950/20 hover:bg-brand-500 before:absolute before:inset-y-2 before:left-0 before:w-0.5 before:rounded-full before:bg-white"
      : isDisabled
        ? "text-gray-600 cursor-not-allowed opacity-50"
        : "text-gray-400 hover:text-fg hover:bg-surface-overlay active:bg-surface-overlay/70",
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

// ─── Sub-componente: lista de navegación (compartida por Sidebar y Drawer) ───

interface NavListProps {
  collapsed: boolean;
  activeNavId: string;
  onNavClick: (id: string) => void;
}

function NavList({ collapsed, activeNavId, onNavClick }: NavListProps) {
  return (
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
  );
}

function NavFooter() {
  return (
    <div className="px-4 mt-4 pt-4 border-t border-surface-border">
      <p className="text-xs text-gray-600">Plataforma Patrimonial</p>
      <p className="text-xs text-gray-600">v0.1.0 — Módulo 1 (beta)</p>
    </div>
  );
}

// ─── Sub-componente: Sidebar (escritorio, md+) ───────────────────────────────

interface SidebarProps {
  collapsed: boolean;
  activeNavId: string;
  onNavClick: (id: string) => void;
}

function Sidebar({ collapsed, activeNavId, onNavClick }: SidebarProps) {
  return (
    <aside
      className={[
        "hidden md:flex bg-surface-raised border-r border-surface-border",
        "flex-col py-4 shrink-0 overflow-hidden",
        "transition-[width] duration-200",
        collapsed ? "w-16" : "w-56",
      ].join(" ")}
      aria-label="Navegación principal"
    >
      <nav className="flex-1 px-2 overflow-y-auto">
        <NavList collapsed={collapsed} activeNavId={activeNavId} onNavClick={onNavClick} />
      </nav>
      {!collapsed && <NavFooter />}
    </aside>
  );
}

// ─── Sub-componente: Drawer de navegación (móvil, < md) ──────────────────────

interface MobileDrawerProps {
  open: boolean;
  activeNavId: string;
  onNavClick: (id: string) => void;
  onClose: () => void;
}

function MobileDrawer({ open, activeNavId, onNavClick, onClose }: MobileDrawerProps) {
  const panelRef = useRef<HTMLElement>(null);

  // Esc cierra; el foco entra al panel al abrir y el scroll del fondo queda bloqueado mientras está abierto.
  useEffect(() => {
    if (!open) return;
    const previouslyFocused = document.activeElement as HTMLElement | null;
    // El panel pasa de inert a interactivo en este mismo render: el foco se pide en el siguiente frame,
    // cuando el navegador ya lo admite (si no, focus() no hace nada y el foco se queda en la hamburguesa).
    const raf = window.requestAnimationFrame(() => {
      panelRef.current?.querySelector<HTMLElement>("a[href],button:not(:disabled)")?.focus();
    });
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => {
      window.cancelAnimationFrame(raf);
      window.removeEventListener("keydown", onKey);
      document.body.style.overflow = previousOverflow;
      previouslyFocused?.focus?.();
    };
  }, [open, onClose]);

  // Si la ventana crece a escritorio con el drawer abierto, se cierra (el Sidebar ya toma el relevo).
  useEffect(() => {
    if (!open) return;
    const mq = window.matchMedia("(min-width: 768px)");
    const onChange = () => mq.matches && onClose();
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [open, onClose]);

  return (
    <div className={`md:hidden ${open ? "" : "pointer-events-none"}`} data-testid="mobile-nav-root">
      {/* Fondo */}
      <div
        className={[
          "fixed inset-0 z-30 bg-black/50 backdrop-blur-sm transition-opacity duration-200",
          open ? "opacity-100" : "opacity-0",
        ].join(" ")}
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Panel */}
      <aside
        ref={panelRef}
        id="mobile-nav"
        role="dialog"
        aria-modal={open}
        aria-label="Navegación principal"
        aria-hidden={!open}
        // inert: oculto no es enfocable ni alcanzable con tabulador
        {...(open ? {} : { inert: "" as unknown as boolean })}
        className={[
          "fixed inset-y-0 left-0 z-40 flex w-72 max-w-[85vw] flex-col",
          "bg-surface-raised border-r border-surface-border shadow-2xl",
          "transition-transform duration-200 ease-out",
          open ? "translate-x-0" : "-translate-x-full",
        ].join(" ")}
      >
        <div className="h-14 shrink-0 flex items-center justify-between px-4 border-b border-surface-border">
          <span className="text-fg font-semibold text-sm">Wealth Platform</span>
          <button
            type="button"
            onClick={onClose}
            className={ICON_BUTTON}
            aria-label="Cerrar menú de navegación"
          >
            <IconClose />
          </button>
        </div>
        <nav className="flex-1 overflow-y-auto px-2 py-4">
          <NavList
            collapsed={false}
            activeNavId={activeNavId}
            onNavClick={(id) => {
              onNavClick(id);
              onClose();
            }}
          />
        </nav>
        <NavFooter />
      </aside>
    </div>
  );
}

// ─── Componente principal: AppShell ──────────────────────────────────────────

export function AppShell({
  children,
  clientName = "Sin cliente seleccionado",
  activeNavId = "rebalancer",
}: AppShellProps) {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [currentNavId, setCurrentNavId] = useState(activeNavId);

  const handleToggleSidebar = () => setSidebarCollapsed((prev) => !prev);
  const handleNavClick = (id: string) => setCurrentNavId(id);
  const closeMobileNav = () => setMobileNavOpen(false);

  return (
    // SlotRegistryProvider envuelve TODA la app para que los slots sean
    // accesibles desde cualquier módulo que esté montado en el árbol.
    <SlotRegistryProvider>
      {/* h-dvh: en móvil la barra del navegador no recorta el final de la página (h-screen sí) */}
      <div className="flex flex-col h-screen h-dvh w-full max-w-full bg-surface-base text-fg overflow-hidden">
        <Topbar
          clientName={clientName}
          sidebarCollapsed={sidebarCollapsed}
          onToggleSidebar={handleToggleSidebar}
          mobileNavOpen={mobileNavOpen}
          onToggleMobileNav={() => setMobileNavOpen((v) => !v)}
        />

        {/* Cuerpo: sidebar (md+) + contenido. min-w-0: sin él un hijo ancho (tabla) ensancha todo el flex. */}
        <div className="flex flex-1 min-h-0 min-w-0 overflow-hidden">
          <Sidebar collapsed={sidebarCollapsed} activeNavId={currentNavId} onNavClick={handleNavClick} />

          {/* Área de contenido principal */}
          <main
            className="flex-1 min-w-0 overflow-y-auto overflow-x-hidden p-3 sm:p-4 lg:p-6 bg-surface-base"
            aria-label="Contenido principal"
          >
            <div className="mx-auto w-full max-w-content">{children}</div>
          </main>
        </div>

        <MobileDrawer
          open={mobileNavOpen}
          activeNavId={currentNavId}
          onNavClick={handleNavClick}
          onClose={closeMobileNav}
        />
      </div>
    </SlotRegistryProvider>
  );
}
