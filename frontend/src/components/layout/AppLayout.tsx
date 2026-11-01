import { useEffect, useRef, useState } from 'react'
import {
  ArrowLeftIcon,
  KeyRoundIcon,
  LayoutDashboardIcon,
  LogOutIcon,
  MenuIcon,
  MonitorIcon,
  MoonIcon,
  SettingsIcon,
  ShieldIcon,
  StoreIcon,
  SunIcon,
  UserCogIcon,
  UserIcon,
} from 'lucide-react'
import { NavLink, Outlet, ScrollRestoration, useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { BrandMark } from '@/components/BrandMark'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { hasPermission, useCurrentUser, useLogout } from '@/features/auth/api'
import { DeliNewOrdersBadge } from '@/features/deli-order/DeliNewOrdersBadge'
import { IncomingTransfersBadge } from '@/features/documents/IncomingTransfersBadge'
import { PartnerInvitationsBadge } from '@/features/store-settings/PartnerInvitationsBadge'
import { StoreSwitcher } from '@/features/stores/StoreSwitcher'
import { TOOL_ROUTES, usePermissionCategories } from '@/features/tools/categories'
import { historyIndex, recordLocation } from '@/lib/history-trail'
import { THEME_LABELS, useThemeChoice, type ThemeChoice } from '@/lib/theme'
import { cn } from '@/lib/utils'
import { closeMobileNav, toggleMobileNav } from '@/store/uiSlice'
import { useAppDispatch, useAppSelector } from '@/store'

// Czas animacji menu, ten sam co duration-200 w klasach <aside>.
const SIDEBAR_TRANSITION_MS = 200

export function AppLayout() {
  const mobileNavOpen = useAppSelector((state) => state.ui.mobileNavOpen)
  const dispatch = useAppDispatch()
  const location = useLocation()
  // Pasek z ikonami (szerokie strony) rozwija się po najechaniu myszą, nad treścią i bez przyciemnienia.
  // Zapamiętana jest ścieżka, na której menu się rozwinęło: przejście na inną stronę je zwija.
  const [hoveredPath, setHoveredPath] = useState<string | null>(null)
  const hovered = hoveredPath === location.pathname
  const hoverTimer = useRef<number | undefined>(undefined)
  const expanded = mobileNavOpen || hovered
  // Zwijanie paska: menu najpierw zwęża się z pełną treścią, dopiero po animacji przechodzi na ikony.
  const [collapsing, setCollapsing] = useState(false)
  const [prevExpanded, setPrevExpanded] = useState(expanded)
  if (prevExpanded !== expanded) {
    setPrevExpanded(expanded)
    setCollapsing(!expanded)
  }

  useEffect(() => {
    if (!collapsing) return
    const timer = window.setTimeout(() => setCollapsing(false), SIDEBAR_TRANSITION_MS)
    return () => window.clearTimeout(timer)
  }, [collapsing])

  useEffect(() => {
    dispatch(closeMobileNav())
  }, [location.pathname, dispatch])

  useEffect(() => () => window.clearTimeout(hoverTimer.current), [])

  // Krótkie opóźnienie: przejechanie kursorem przy krawędzi okna nie rozwija menu.
  const onPointerEnter = (event: React.PointerEvent) => {
    if (event.pointerType !== 'mouse') return
    window.clearTimeout(hoverTimer.current)
    hoverTimer.current = window.setTimeout(() => setHoveredPath(location.pathname), 150)
  }
  const onPointerLeave = (event: React.PointerEvent<HTMLElement>) => {
    window.clearTimeout(hoverTimer.current)
    // Otwarta lista (np. wybór sklepu) wyświetla się poza menu: wejście w nią nie zwija menu.
    if (event.currentTarget.querySelector('[data-popup-open]')) return
    setHoveredPath(null)
  }

  // Esc zamyka wysunięte menu.
  useEffect(() => {
    if (!mobileNavOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') dispatch(closeMobileNav())
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [mobileNavOpen, dispatch])

  useEffect(() => {
    recordLocation(location.pathname)
  }, [location.key, location.pathname])

  return (
    // Szerokie strony (data-wide-page) zwijają menu do paska z ikonami (wariant rail); rozwinięte wysuwa się nad treść.
    <div className="group/layout app-layout min-h-svh bg-muted/30 md:grid md:grid-cols-[15rem_1fr] md:has-[[data-wide-page]]:grid-cols-[3.5rem_1fr]">
      <aside
        data-rail={expanded || collapsing ? undefined : ''}
        onPointerEnter={onPointerEnter}
        onPointerLeave={onPointerLeave}
        className={cn(
          'fixed inset-y-0 left-0 z-40 w-60 overflow-x-hidden overflow-y-auto border-r bg-background md:sticky md:top-0 md:h-svh md:translate-x-0',
          // Wysuwanie: na telefonie przesunięcie, na pasku z ikonami rozszerzenie i zwężenie.
          'transition-[translate,width,box-shadow] duration-200 ease-[cubic-bezier(0.32,0.72,0,1)]',
          'md:group-has-[[data-wide-page]]/layout:fixed md:group-has-[[data-wide-page]]/layout:h-full',
          collapsing && 'md:group-has-[[data-wide-page]]/layout:w-14',
          'rail:sticky rail:h-svh rail:w-14 rail:transition-none',
          expanded ? 'translate-x-0' : '-translate-x-full',
          hovered && !mobileNavOpen && 'shadow-xl',
        )}
      >
        <Sidebar />
      </aside>
      {mobileNavOpen && (
        <div
          className="fixed inset-0 z-30 bg-black/30 animate-in duration-200 fade-in-0 md:hidden md:group-has-[[data-wide-page]]/layout:block"
          onClick={() => dispatch(closeMobileNav())}
        />
      )}

      {/* col-start-2: wysunięte menu wypada z siatki, a treść ma zostać w swojej kolumnie. */}
      <div className="flex min-w-0 flex-col md:col-start-2">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-2 border-b bg-background px-4">
          <Button
            variant="ghost"
            size="icon"
            className="md:hidden"
            aria-label="Menu"
            onClick={() => dispatch(toggleMobileNav())}
          >
            <MenuIcon />
          </Button>
          <BackButton />
          <div className="ml-auto">
            <UserMenu />
          </div>
        </header>
        {/* Szerokie strony (data-wide-page) zajmują całą szerokość, a menu jest zwinięte do ikon. */}
        {/* Strony z data-fit-page (od lg) mają wysokość okna pod nagłówkiem i przewijają się w środku, nie całe. */}
        <main className="mx-auto w-full max-w-6xl flex-1 p-4 md:p-6 has-[[data-wide-page]]:max-w-none lg:has-[[data-fit-page]]:flex lg:has-[[data-fit-page]]:h-[calc(100svh-3.5rem)] lg:has-[[data-fit-page]]:flex-col lg:has-[[data-fit-page]]:overflow-y-auto">
          <Outlet />
        </main>
        {/* Przewinięcie zapamiętane per ścieżka: powrót na listę wraca w to samo miejsce, a zmiana filtrów
            w adresie (ta sama ścieżka) nie przerzuca strony na górę. */}
        <ScrollRestoration getKey={(location) => location.pathname} />
      </div>
    </div>
  )
}

function Sidebar() {
  const dispatch = useAppDispatch()
  const me = useCurrentUser()
  const { data: categories = [] } = usePermissionCategories()
  const tools = categories.filter((category) => hasPermission(me, category.code))
  // Ustawienia sklepu: budżet rejonów (edycja sprzedaży) i sklepy zaprzyjaźnione (edycja dokumentów).
  const canEditDocuments = hasPermission(me, 'DOCUMENTS', 'EDIT')
  // Ustawienia sklepu: budżet i marże (sprzedaż), sklepy zaprzyjaźnione (dokumenty), pliki PROTO (też wprowadzanie
  // danych i garmaż).
  const canStoreSettings =
    canEditDocuments ||
    hasPermission(me, 'SALES', 'EDIT') ||
    hasPermission(me, 'DATA_ENTRY', 'EDIT') ||
    hasPermission(me, 'DELI_ORDERS', 'EDIT')

  return (
    // Minimalna szerokość: przy rozszerzaniu paska treść od razu ma docelowy układ i odsłania się, zamiast się ściskać.
    <nav className="flex h-full min-w-52 flex-col gap-6 p-3 rail:min-w-0 rail:items-center rail:gap-4 rail:px-2">
      <div className="grid gap-5 rail:hidden">
        <BrandMark className="px-2 pt-1.5" />
        <StoreSwitcher />
      </div>
      <div className="hidden rail:grid rail:justify-items-center rail:gap-3">
        <BrandMark compact className="pt-1" />
        <Button
          variant="ghost"
          size="icon"
          aria-label="Rozwiń menu"
          title="Rozwiń menu"
          onClick={() => dispatch(toggleMobileNav())}
        >
          <MenuIcon />
        </Button>
      </div>

      <div className="grid gap-1">
        <SidebarLink to="/" icon={LayoutDashboardIcon} label="Pulpit" end />
      </div>

      {tools.length > 0 && (
        <div className="grid gap-1">
          <SectionLabel>Narzędzia</SectionLabel>
          {tools.map((category) => (
            <SidebarLink
              key={category.code}
              to={TOOL_ROUTES[category.code].path}
              icon={TOOL_ROUTES[category.code].icon}
              label={category.label}
              badge={
                category.code === 'DELI_ORDERS' ? (
                  <DeliNewOrdersBadge />
                ) : (
                  category.code === 'DOCUMENTS' && <IncomingTransfersBadge />
                )
              }
            />
          ))}
        </div>
      )}

      {(me.owner || canStoreSettings) && (
        <div className="grid gap-1">
          <SectionLabel>Ustawienia</SectionLabel>
          {canStoreSettings && (
            <SidebarLink
              to="/ustawienia-sklepu"
              icon={SettingsIcon}
              label="Ustawienia sklepu"
              badge={canEditDocuments && <PartnerInvitationsBadge />}
            />
          )}
          {me.owner && (
            <>
              <SidebarLink to="/ustawienia/sklepy" icon={StoreIcon} label="Sklepy" />
              <SidebarLink to="/ustawienia/role" icon={ShieldIcon} label="Role i uprawnienia" />
              <SidebarLink to="/ustawienia/uzytkownicy" icon={UserCogIcon} label="Użytkownicy" />
            </>
          )}
        </div>
      )}
    </nav>
  )
}

/**
 * Powrót do poprzedniego ekranu w aplikacji. Bez historii (strona otwarta z linku albo po odświeżeniu na pierwszym
 * ekranie) przenosi na Pulpit; na samym Pulpicie jest wtedy wyłączony.
 */
function BackButton() {
  const navigate = useNavigate()
  const location = useLocation()
  // 0 = pierwszy ekran tej karty.
  const hasHistory = historyIndex() > 0
  const disabled = !hasHistory && location.pathname === '/'

  return (
    <Button
      variant="ghost"
      size="icon"
      aria-label="Wróć do poprzedniego ekranu"
      title="Wróć"
      disabled={disabled}
      onClick={() => (hasHistory ? navigate(-1) : navigate('/'))}
    >
      <ArrowLeftIcon />
    </Button>
  )
}

function SectionLabel({ children }: { children: string }) {
  return (
    <>
      <div className="px-2 pb-1 text-xs font-medium tracking-wide text-muted-foreground uppercase rail:hidden">
        {children}
      </div>
      <div className="hidden h-px bg-border rail:mb-1 rail:block" />
    </>
  )
}

function SidebarLink({
  to,
  icon: Icon,
  label,
  end,
  badge,
}: {
  to: string
  icon: React.ComponentType<{ className?: string }>
  label: string
  end?: boolean
  /** Np. licznik nowych zamówień, wyrównany do prawej. */
  badge?: React.ReactNode
}) {
  return (
    <NavLink
      to={to}
      end={end}
      title={label}
      className={({ isActive }) =>
        cn(
          'group/link relative flex items-center gap-2.5 rounded-lg px-2 py-2 rail:size-10 rail:justify-center rail:p-0 text-sm text-muted-foreground transition-colors duration-150 hover:bg-muted hover:text-foreground',
          isActive && 'bg-muted font-medium text-foreground',
        )
      }
    >
      {({ isActive }) => (
        <>
          <Icon className={cn('size-4 transition-colors duration-150', isActive && 'text-brand')} />
          <span className="min-w-0 flex-1 truncate rail:hidden">{label}</span>
          {/* W pasku z ikonami licznik siedzi w rogu ikony. */}
          {badge && <span className="contents rail:absolute rail:-top-1 rail:-right-1 rail:block">{badge}</span>}
        </>
      )}
    </NavLink>
  )
}

function UserMenu() {
  const me = useCurrentUser()
  const logout = useLogout()
  const navigate = useNavigate()

  const onLogout = () =>
    logout.mutate(undefined, {
      onSuccess: () => navigate('/logowanie', { replace: true }),
      onError: () => toast.error('Nie udało się wylogować'),
    })

  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="ghost" />}>
        <UserIcon />
        <span className="max-w-40 truncate">{me.displayName}</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuGroup>
          <DropdownMenuLabel>
            <div className="truncate font-medium text-foreground">{me.displayName}</div>
            <div className="truncate text-xs font-normal">{me.roleName}</div>
          </DropdownMenuLabel>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={() => navigate('/konto')}>
          <KeyRoundIcon />
          Moje konto
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <ThemeOptions />
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={onLogout} disabled={logout.isPending}>
          <LogOutIcon />
          Wyloguj
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

const THEME_ICONS: Record<ThemeChoice, React.ComponentType> = { light: SunIcon, dark: MoonIcon, system: MonitorIcon }

/** Motyw interfejsu, zapamiętany w przeglądarce. Menu zostaje otwarte (domyślne w Base UI), więc widać efekt od razu. */
function ThemeOptions() {
  const { theme, choose } = useThemeChoice()

  return (
    <DropdownMenuGroup>
      <DropdownMenuLabel>Motyw</DropdownMenuLabel>
      <DropdownMenuRadioGroup value={theme} onValueChange={(value) => choose(value as ThemeChoice)}>
        {(Object.keys(THEME_LABELS) as ThemeChoice[]).map((choice) => {
          const Icon = THEME_ICONS[choice]
          return (
            <DropdownMenuRadioItem key={choice} value={choice}>
              <Icon />
              {THEME_LABELS[choice]}
            </DropdownMenuRadioItem>
          )
        })}
      </DropdownMenuRadioGroup>
    </DropdownMenuGroup>
  )
}
