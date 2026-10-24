import { useEffect } from 'react'
import { KeyRoundIcon, LayoutDashboardIcon, LogOutIcon, MenuIcon, ShieldIcon, UserCogIcon, UserIcon } from 'lucide-react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { BrandMark } from '@/components/BrandMark'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { hasPermission, useCurrentUser, useLogout } from '@/features/auth/api'
import { TOOL_ROUTES, usePermissionCategories } from '@/features/tools/categories'
import { cn } from '@/lib/utils'
import { closeMobileNav, toggleMobileNav } from '@/store/uiSlice'
import { useAppDispatch, useAppSelector } from '@/store'

export function AppLayout() {
  const mobileNavOpen = useAppSelector((state) => state.ui.mobileNavOpen)
  const dispatch = useAppDispatch()
  const location = useLocation()

  useEffect(() => {
    dispatch(closeMobileNav())
  }, [location.pathname, dispatch])

  return (
    <div className="min-h-svh bg-muted/30 md:grid md:grid-cols-[15rem_1fr]">
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-40 w-60 border-r bg-background transition-transform md:static md:translate-x-0',
          mobileNavOpen ? 'translate-x-0' : '-translate-x-full',
        )}
      >
        <Sidebar />
      </aside>
      {mobileNavOpen && (
        <div className="fixed inset-0 z-30 bg-black/30 md:hidden" onClick={() => dispatch(closeMobileNav())} />
      )}

      <div className="flex min-w-0 flex-col">
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
          <div className="ml-auto">
            <UserMenu />
          </div>
        </header>
        <main className="mx-auto w-full max-w-6xl flex-1 p-4 md:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function Sidebar() {
  const me = useCurrentUser()
  const { data: categories = [] } = usePermissionCategories()
  const tools = categories.filter((category) => hasPermission(me, category.code))

  return (
    <nav className="flex h-full flex-col gap-6 p-3">
      <BrandMark className="px-1.5 pt-1.5" />

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
            />
          ))}
        </div>
      )}

      {me.owner && (
        <div className="grid gap-1">
          <SectionLabel>Ustawienia</SectionLabel>
          <SidebarLink to="/ustawienia/role" icon={ShieldIcon} label="Role i uprawnienia" />
          <SidebarLink to="/ustawienia/uzytkownicy" icon={UserCogIcon} label="Użytkownicy" />
        </div>
      )}
    </nav>
  )
}

function SectionLabel({ children }: { children: string }) {
  return <div className="px-2 pb-1 text-xs font-medium tracking-wide text-muted-foreground uppercase">{children}</div>
}

function SidebarLink({
  to,
  icon: Icon,
  label,
  end,
}: {
  to: string
  icon: React.ComponentType<{ className?: string }>
  label: string
  end?: boolean
}) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        cn(
          'group/link flex items-center gap-2.5 rounded-lg px-2 py-2 text-sm text-muted-foreground transition-colors duration-150 hover:bg-muted hover:text-foreground',
          isActive && 'bg-muted font-medium text-foreground',
        )
      }
    >
      {({ isActive }) => (
        <>
          <Icon className={cn('size-4 transition-colors duration-150', isActive && 'text-brand')} />
          {label}
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
        <DropdownMenuItem onClick={onLogout} disabled={logout.isPending}>
          <LogOutIcon />
          Wyloguj
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
