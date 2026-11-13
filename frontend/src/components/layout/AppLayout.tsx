import { Fragment, useEffect, useRef, useState } from 'react'
import {
  ArrowLeftIcon,
  ChevronRightIcon,
  KeyRoundIcon,
  LayoutDashboardIcon,
  LogOutIcon,
  MenuIcon,
  MonitorIcon,
  MoonIcon,
  SunIcon,
  UsersRoundIcon,
} from 'lucide-react'
import { Link, NavLink, Outlet, ScrollRestoration, useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { BrandMark } from '@/components/BrandMark'
import { AppBackground } from '@/components/layout/AppBackground'
import { groupTarget, locate, useNavGroups } from '@/components/layout/navigation'
import { Button } from '@/components/ui/button'
import { UserAvatar } from '@/components/UserAvatar'
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
import { SwitchAccountDialog } from '@/features/auth/AccountSwitcher'
import { useCurrentUser, useLogout } from '@/features/auth/api'
import { useWorkdayLock } from '@/features/auth/use-workday-lock'
import { usePriceFileWatcher } from '@/features/data-entry/price-files'
import { DownloadUpdatesBanner } from '@/features/downloads/DownloadUpdatesBanner'
import { StoreSwitcher } from '@/features/stores/StoreSwitcher'
import { historyIndex, recordLocation } from '@/lib/history-trail'
import { THEME_LABELS, useThemeChoice, type ThemeChoice } from '@/lib/theme'
import { cn } from '@/lib/utils'
import { closeMobileNav, toggleMobileNav } from '@/store/uiSlice'
import { useAppDispatch, useAppSelector } from '@/store'

// Czas animacji menu, ten sam co duration-200 w klasach <aside>.
const SIDEBAR_TRANSITION_MS = 200

export function AppLayout() {
  // Ceny z kasy (pliki TRACEART) sprawdzane w tle na każdej stronie aplikacji.
  usePriceFileWatcher()
  useWorkdayLock()
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
    <div className="group/layout app-layout isolate min-h-svh bg-muted/30 md:grid md:grid-cols-[15rem_1fr] md:has-[[data-wide-page]]:grid-cols-[3.5rem_1fr]">
      <AppBackground />
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
            className="md:hidden md:group-has-[[data-wide-page]]/layout:inline-flex"
            aria-label="Menu"
            onClick={() => dispatch(toggleMobileNav())}
          >
            <MenuIcon />
          </Button>
          <BackButton />
          <GroupTabs />
          <div className="ml-auto shrink-0">
            <UserMenu />
          </div>
        </header>
        <DownloadUpdatesBanner />
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

/** Menu boczne: tylko główne kategorie; ich narzędzia są w zakładkach nad stroną i na stronie kategorii. */
function Sidebar() {
  const me = useCurrentUser()
  const { pathname } = useLocation()
  const groups = useNavGroups(me)
  const current = locate(groups, pathname)?.group

  return (
    // Minimalna szerokość: przy rozszerzaniu paska treść od razu ma docelowy układ i odsłania się, zamiast się ściskać.
    // Pasek to to samo menu, tylko węższe: wiersze mają stałe wysokości, więc nic nie zmienia miejsca przy rozwijaniu.
    <nav className="flex h-full min-w-52 flex-col gap-6 p-3 rail:min-w-0">
      <div className="grid gap-5">
        <BrandMark className="h-9 px-2 rail:hidden" />
        <BrandMark compact className="hidden h-9 rail:flex" />
        <div className="grid min-h-14.5">
          <StoreSwitcher />
        </div>
      </div>

      <div className="grid gap-1">
        <SidebarLink to="/" icon={LayoutDashboardIcon} label="Pulpit" active={pathname === '/'} />
        {groups.map((group) => {
          const target = groupTarget(group)
          return (
            <SidebarLink
              key={group.key}
              to={target.path}
              icon={target.icon}
              label={target.label}
              active={group === current}
              badge={group.items.map((item) => item.badge && <Fragment key={item.key}>{item.badge}</Fragment>)}
            />
          )
        })}
      </div>
    </nav>
  )
}

/**
 * Narzędzia bieżącej kategorii w nagłówku (jak zakładki), żeby przejść obok bez wracania do menu. Nagłówek ma stałą
 * wysokość, więc przyklejone paski stron (`top-14`) zostają na miejscu; nadmiar przewija się w bok.
 */
function GroupTabs() {
  const me = useCurrentUser()
  const { pathname } = useLocation()
  const located = locate(useNavGroups(me), pathname)
  if (!located || located.group.items.length < 2) return null
  const { group, item: active } = located

  return (
    <nav
      aria-label={group.label}
      className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
    >
      <NavLink
        to={group.path}
        end
        title={group.label}
        className={cn(
          'flex shrink-0 items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm font-medium text-foreground transition-colors duration-150 hover:bg-muted',
          active === null && 'bg-muted',
        )}
      >
        <group.icon className="size-4 text-muted-foreground" />
        <span className="hidden lg:inline">{group.label}</span>
      </NavLink>
      <ChevronRightIcon aria-hidden className="size-4 shrink-0 text-muted-foreground/60" />
      {group.items.map((item) => (
        <Link
          key={item.key}
          to={item.path}
          title={item.label}
          aria-current={item === active ? 'page' : undefined}
          className={cn(
            'flex shrink-0 items-center gap-1.5 rounded-lg px-2 py-1.5 text-sm text-muted-foreground transition-colors duration-150 hover:bg-muted hover:text-foreground',
            item === active && 'bg-muted font-medium text-foreground',
          )}
        >
          <item.icon className={cn('size-4', item === active && 'text-brand')} />
          <span className="whitespace-nowrap">{item.label}</span>
          {item.badge}
        </Link>
      ))}
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

function SidebarLink({
  to,
  icon: Icon,
  label,
  active,
  badge,
}: {
  to: string
  icon: React.ComponentType<{ className?: string }>
  label: string
  /** Kategoria jest aktywna na stronie kategorii i na stronach każdego jej narzędzia. */
  active: boolean
  /** Np. licznik nowych zamówień, wyrównany do prawej. */
  badge?: React.ReactNode
}) {
  return (
    <Link
      to={to}
      title={label}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'group/link relative flex h-9 items-center gap-2.5 rounded-lg px-2 text-sm text-muted-foreground transition-colors duration-150 hover:bg-muted hover:text-foreground',
        active && 'bg-muted font-medium text-foreground',
      )}
    >
      <Icon className={cn('size-4 transition-colors duration-150', active && 'text-brand')} />
      <span className="min-w-0 flex-1 truncate rail:hidden">{label}</span>
      {/* W pasku z ikonami licznik siedzi w rogu ikony. */}
      {badge && <span className="flex gap-1 rail:absolute rail:-top-1 rail:-right-1">{badge}</span>}
    </Link>
  )
}

function UserMenu() {
  const me = useCurrentUser()
  const logout = useLogout()
  const navigate = useNavigate()
  const [switchOpen, setSwitchOpen] = useState(false)

  const onLogout = () =>
    logout.mutate(undefined, {
      onSuccess: () => navigate('/logowanie', { replace: true }),
      onError: () => toast.error('Nie udało się wylogować'),
    })

  return (
    <>
      <SwitchAccountDialog
        open={switchOpen}
        onOpenChange={setSwitchOpen}
        // Inne konto może nie mieć dostępu do bieżącej strony, więc zaczyna od pulpitu.
        onSwitched={(next) => navigate(next.passwordChangeRequired ? '/ustaw-haslo' : '/', { replace: true })}
      />
      <DropdownMenu>
        <DropdownMenuTrigger render={<Button variant="ghost" />}>
          <UserAvatar name={me.displayName} url={me.avatar?.url} className="size-6 text-[0.625rem]" />
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
          <DropdownMenuItem onClick={() => setSwitchOpen(true)}>
            <UsersRoundIcon />
            Zmień konto
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
    </>
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
