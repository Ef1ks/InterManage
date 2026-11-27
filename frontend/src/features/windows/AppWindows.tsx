import { useEffect, useState } from 'react'
import {
  AppWindowIcon,
  ChevronDownIcon,
  LayoutDashboardIcon,
  PencilIcon,
  StarIcon,
  StarOffIcon,
  XIcon,
} from 'lucide-react'
import { useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { locate, pageKey, useNavGroups } from '@/components/layout/navigation'
import { ContextMenu, ContextMenuContent, ContextMenuTrigger } from '@/components/ui/context-menu'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { useCurrentUser } from '@/features/auth/api'
import { cn } from '@/lib/utils'

const MAX_WINDOWS = 10
/** Okno nieotwierane tak długo zamyka się samo (bieżące nigdy). */
const EXPIRE_MS = 2 * 60 * 60 * 1000
const CHECK_MS = 60 * 1000
const STORAGE_KEY = 'intermanage.windows'
const FAVORITES_KEY = 'intermanage.favorites'
const MAX_FAVORITES = 20

/** Okno = jedna strona aplikacji (`page` z `pageKey`) z ostatnim adresem, na którym była (filtry, podstrona). */
type AppWindow = { id: string; page: string; url: string; seenAt: number }

/** Zakładka w pasku ulubionych: adres z filtrami i nazwa (na start nazwa strony, do zmiany). */
type Favorite = { id: string; url: string; label: string }

function loadFavorites(key: string): Favorite[] {
  try {
    const saved = JSON.parse(localStorage.getItem(key) ?? '[]') as unknown
    if (!Array.isArray(saved)) return []
    return (saved as Favorite[])
      .filter(
        (item) =>
          typeof item?.id === 'string' &&
          typeof item.url === 'string' &&
          item.url.startsWith('/') &&
          typeof item.label === 'string',
      )
      .slice(0, MAX_FAVORITES)
  } catch {
    return []
  }
}

const pathOf = (url: string) => url.split(/[?#]/)[0]

/** Okna nieotwierane od 2 godzin wypadają; bieżące zostaje zawsze. */
function withoutExpired(windows: AppWindow[], activePage: string, now: number) {
  const kept = windows.filter((item) => item.page === activePage || now - item.seenAt < EXPIRE_MS)
  return kept.length === windows.length ? windows : kept
}

/**
 * Okna po przejściu na adres: strona z oknem dostaje w nim nowy adres, a nowa strona nowe okno na końcu. Gdy okien
 * jest już najwięcej, nowa strona zajmuje miejsce okna najdawniej oglądanego, więc kolejność pozostałych zostaje.
 */
function visit(windows: AppWindow[], url: string, now: number): AppWindow[] {
  const page = pageKey(pathOf(url))
  if (windows.some((item) => item.page === page)) {
    return windows.map((item) => (item.page === page ? { ...item, url, seenAt: now } : item))
  }
  const created: AppWindow = { id: crypto.randomUUID(), page, url, seenAt: now }
  if (windows.length < MAX_WINDOWS) return [...windows, created]
  const oldest = windows.reduce((old, other) => (other.seenAt < old.seenAt ? other : old))
  return windows.map((item) => (item === oldest ? created : item))
}

function load(key: string): AppWindow[] {
  try {
    const saved = JSON.parse(localStorage.getItem(key) ?? '[]') as unknown
    if (!Array.isArray(saved)) return []
    return (saved as AppWindow[])
      .filter(
        (item) =>
          typeof item?.id === 'string' &&
          typeof item.url === 'string' &&
          item.url.startsWith('/') &&
          typeof item.seenAt === 'number',
      )
      .map((item) => ({ ...item, page: pageKey(pathOf(item.url)) }))
      .slice(0, MAX_WINDOWS)
  } catch {
    return []
  }
}

function save(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Bez localStorage okna działają do odświeżenia strony.
  }
}

/**
 * Okna aplikacji pod nagłówkiem, jak karty w przeglądarce: każda strona (narzędzie z menu, Pulpit) otwarta z menu
 * albo linku dostaje swoje okno, a okno pamięta ostatni adres tej strony (filtry, otwarty dokument). Klik w okno
 * wraca na tę stronę bez szukania w menu. Najwyżej 10 okien: nowa strona zajmuje miejsce najdawniej oglądanego, a okno nieotwierane od
 * 2 godzin zamyka się samo. Przełączenie to zwykłe przejście na adres okna: strona nie zostaje zamontowana w tle
 * (to kosztowałoby pamięć i zapytania), ale dane z serwera czekają w pamięci zapytań, a przewinięcie przywraca
 * `ScrollRestoration`. Okna są zapamiętane w przeglądarce, osobno dla każdego konta.
 */
export function AppWindowsBar() {
  const me = useCurrentUser()
  const groups = useNavGroups(me)
  const location = useLocation()
  const navigate = useNavigate()
  const url = `${location.pathname}${location.search}`
  const activePage = pageKey(location.pathname)
  const storageKey = `${STORAGE_KEY}.${me.id}`
  const favoritesKey = `${FAVORITES_KEY}.${me.id}`
  const [windows, setWindows] = useState(() => visit(load(storageKey), url, Date.now()))
  const [favorites, setFavorites] = useState(() => loadFavorites(favoritesKey))
  const [renamingId, setRenamingId] = useState<string | null>(null)

  // Każde przejście w aplikacji: okno bieżącej strony dostaje adres, nowa strona nowe okno.
  useEffect(() => {
    setWindows((current) => visit(current, url, Date.now()))
  }, [url])

  useEffect(() => {
    const timer = window.setInterval(
      () => setWindows((current) => withoutExpired(current, pageKey(pathOf(currentUrl())), Date.now())),
      CHECK_MS,
    )
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => save(storageKey, windows), [storageKey, windows])
  useEffect(() => save(favoritesKey, favorites), [favoritesKey, favorites])

  const describe = (windowUrl: string) => {
    const pathname = pathOf(windowUrl)
    if (pathname === '/') return { label: 'Pulpit', icon: LayoutDashboardIcon }
    const located = locate(groups, pathname)
    if (located?.item) return { label: located.item.label, icon: located.item.icon }
    if (located) return { label: located.group.label, icon: located.group.icon }
    return { label: 'Strona aplikacji', icon: AppWindowIcon }
  }

  /** Zamyka podane okna (jedno zawsze zostaje), z jednym „Przywróć” dla wszystkich. */
  const closeMany = (targets: AppWindow[]) => {
    const ids = new Set(targets.map((item) => item.id))
    const rest = windows.filter((item) => !ids.has(item.id))
    if (targets.length === 0 || rest.length === 0) return
    const before = windows
    setWindows(rest)
    // Jak w przeglądarce: po zamknięciu bieżącego okna otwiera się sąsiednie po prawej, a bez niego po lewej.
    const activeIndex = windows.findIndex((item) => item.page === activePage)
    if (targets.some((item) => item.page === activePage)) {
      const next = windows.slice(activeIndex + 1).find((item) => !ids.has(item.id)) ?? rest[rest.length - 1]
      navigate(next.url)
    }
    toast(
      targets.length === 1 ? `Zamknięto okno: ${describe(targets[0].url).label}` : `Zamknięto okna: ${targets.length}`,
      {
        action: {
          label: 'Przywróć',
          onClick: () =>
            setWindows((current) => {
              // Zamknięte wracają na swoje miejsca, o ile w międzyczasie nie otwarto tych stron od nowa.
              const back = before.filter(
                (item) => ids.has(item.id) && !current.some((other) => other.page === item.page),
              )
              const merged = before.filter((item) => back.includes(item) || current.some((c) => c.id === item.id))
              const added = current.filter((item) => !before.some((b) => b.id === item.id))
              return [...merged.map((item) => current.find((c) => c.id === item.id) ?? item), ...added].slice(
                0,
                MAX_WINDOWS,
              )
            }),
        },
      },
    )
  }
  const close = (target: AppWindow) => closeMany([target])

  /** Pozycje zamykania liczone od wskazanego okna (menu prawego przycisku i strzałka na końcu paska). */
  const closeItems = (target: AppWindow) => {
    const index = windows.findIndex((item) => item.id === target.id)
    const others = windows.filter((item) => item.id !== target.id)
    const toRight = windows.slice(index + 1)
    const toLeft = windows.slice(0, Math.max(index, 0))
    return (
      <>
        <DropdownMenuItem disabled={others.length === 0} onClick={() => closeMany(others)}>
          Zamknij pozostałe ({others.length})
        </DropdownMenuItem>
        <DropdownMenuItem disabled={toRight.length === 0} onClick={() => closeMany(toRight)}>
          Zamknij okna po prawej ({toRight.length})
        </DropdownMenuItem>
        <DropdownMenuItem disabled={toLeft.length === 0} onClick={() => closeMany(toLeft)}>
          Zamknij okna po lewej ({toLeft.length})
        </DropdownMenuItem>
      </>
    )
  }

  const favoriteOf = (windowUrl: string) => favorites.find((item) => item.url === windowUrl)

  const addFavorite = (windowUrl: string) => {
    if (favoriteOf(windowUrl)) return
    if (favorites.length >= MAX_FAVORITES) {
      toast.error(`Najwyżej ${MAX_FAVORITES} ulubionych: usuń któreś, żeby dodać nowe`)
      return
    }
    const label = describe(windowUrl).label
    setFavorites((current) => [...current, { id: crypto.randomUUID(), url: windowUrl, label }])
    toast.success(`Dodano do ulubionych: ${label}`)
  }

  const removeFavorite = (target: Favorite) => {
    const index = favorites.findIndex((item) => item.id === target.id)
    setFavorites((current) => current.filter((item) => item.id !== target.id))
    toast(`Usunięto z ulubionych: ${target.label}`, {
      action: {
        label: 'Przywróć',
        onClick: () =>
          setFavorites((current) => {
            if (current.some((item) => item.id === target.id || item.url === target.url)) return current
            const next = [...current]
            next.splice(Math.min(index, next.length), 0, target)
            return next.slice(0, MAX_FAVORITES)
          }),
      },
    })
  }

  const renameFavorite = (target: Favorite, text: string) => {
    setRenamingId(null)
    const label = text.trim().slice(0, 40)
    if (label === '' || label === target.label) return
    setFavorites((current) => current.map((item) => (item.id === target.id ? { ...item, label } : item)))
  }

  const activeWindow = windows.find((item) => item.page === activePage) ?? windows[windows.length - 1]

  return (
    <>
      {favorites.length > 0 && (
        <nav
          data-favorites-bar=""
          aria-label="Ulubione"
          className="hidden h-8 items-center gap-1 overflow-x-auto border-b glass-chrome px-2 [scrollbar-width:none] md:flex [&::-webkit-scrollbar]:hidden"
        >
          <StarIcon aria-hidden className="mr-1 size-3.5 shrink-0 fill-amber-400 text-amber-500" />
          {favorites.map((item) => {
            const { icon: Icon } = describe(item.url)
            const current = item.url === url
            if (renamingId === item.id) {
              return (
                <Input
                  key={item.id}
                  autoFocus
                  defaultValue={item.label}
                  maxLength={40}
                  aria-label="Nazwa ulubionej"
                  onFocus={(event) => event.target.select()}
                  onBlur={(event) => renameFavorite(item, event.target.value)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') renameFavorite(item, event.currentTarget.value)
                    if (event.key === 'Escape') setRenamingId(null)
                  }}
                  className="h-6 w-44 shrink-0 px-2 text-xs"
                />
              )
            }
            return (
              <ContextMenu key={item.id}>
                <ContextMenuTrigger
                  render={
                    <button
                      type="button"
                      title={`${item.label} (prawy przycisk: zmień nazwę albo usuń)`}
                      aria-current={current ? 'page' : undefined}
                      onClick={() => {
                        if (!current) navigate(item.url)
                      }}
                      // Środkowy przycisk myszy usuwa z ulubionych, jak zamyka okno.
                      onAuxClick={(event) => {
                        if (event.button === 1) {
                          event.preventDefault()
                          removeFavorite(item)
                        }
                      }}
                      className={cn(
                        'flex h-6 max-w-48 min-w-0 shrink-0 items-center gap-1.5 rounded-md px-2 text-xs text-foreground/80 transition-colors duration-150 hover:bg-muted hover:text-foreground',
                        current && 'bg-muted font-medium text-foreground',
                      )}
                    />
                  }
                >
                  <Icon className="size-3.5 shrink-0 text-muted-foreground" />
                  <span className="truncate">{item.label}</span>
                </ContextMenuTrigger>
                <ContextMenuContent className="w-56">
                  <DropdownMenuItem onClick={() => setRenamingId(item.id)}>
                    <PencilIcon />
                    Zmień nazwę
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => removeFavorite(item)}>
                    <StarOffIcon />
                    Usuń z ulubionych
                  </DropdownMenuItem>
                </ContextMenuContent>
              </ContextMenu>
            )
          })}
        </nav>
      )}
      <nav
        aria-label="Okna"
        className="hidden h-9 items-end gap-1 overflow-x-auto border-b glass-chrome px-2 pt-1 [scrollbar-width:none] md:flex [&::-webkit-scrollbar]:hidden"
      >
        {windows.map((item) => {
          const active = item.page === activePage
          const { label, icon: Icon } = describe(item.url)
          const favorite = favoriteOf(item.url)
          return (
            <ContextMenu key={item.id}>
              <ContextMenuTrigger
                render={
                  <div
                    className={cn(
                      'group/window relative flex h-8 max-w-56 min-w-0 shrink-0 items-center rounded-t-lg border border-b-0 text-sm transition-colors duration-150',
                      active
                        ? '-mb-px border-border bg-card font-medium text-foreground'
                        : 'border-transparent bg-muted/70 text-foreground/80 hover:bg-muted hover:text-foreground',
                    )}
                  />
                }
              >
                <button
                  type="button"
                  aria-current={active ? 'page' : undefined}
                  title={`${label} (prawy przycisk: ulubione i zamykanie)`}
                  onClick={() => {
                    if (!active) navigate(item.url)
                  }}
                  // Środkowy przycisk myszy zamyka okno, jak w przeglądarce.
                  onAuxClick={(event) => {
                    if (event.button === 1) {
                      event.preventDefault()
                      close(item)
                    }
                  }}
                  className="flex h-full min-w-0 items-center gap-1.5 py-1 pr-1 pl-2.5"
                >
                  <Icon className={cn('size-4 shrink-0', active && 'text-brand')} />
                  <span className="truncate">{label}</span>
                  {favorite && (
                    <StarIcon aria-label="w ulubionych" className="size-3 shrink-0 fill-amber-400 text-amber-500" />
                  )}
                </button>
                {windows.length > 1 && (
                  <button
                    type="button"
                    aria-label={`Zamknij okno: ${label}`}
                    title="Zamknij okno"
                    onClick={() => close(item)}
                    className={cn(
                      'mr-1 grid size-5 shrink-0 place-items-center rounded text-muted-foreground hover:bg-muted-foreground/15 hover:text-foreground',
                      !active && 'opacity-0 group-hover/window:opacity-100 focus-visible:opacity-100',
                    )}
                  >
                    <XIcon className="size-3.5" />
                  </button>
                )}
              </ContextMenuTrigger>
              <ContextMenuContent className="w-64">
                {favorite ? (
                  <DropdownMenuItem onClick={() => removeFavorite(favorite)}>
                    <StarOffIcon />
                    Usuń z ulubionych
                  </DropdownMenuItem>
                ) : (
                  <DropdownMenuItem onClick={() => addFavorite(item.url)}>
                    <StarIcon />
                    Dodaj do ulubionych
                  </DropdownMenuItem>
                )}
                <DropdownMenuSeparator />
                <DropdownMenuItem disabled={windows.length < 2} onClick={() => close(item)}>
                  <XIcon />
                  Zamknij okno
                </DropdownMenuItem>
                {closeItems(item)}
              </ContextMenuContent>
            </ContextMenu>
          )
        })}
        {windows.length > 1 && activeWindow && (
          <DropdownMenu>
            <DropdownMenuTrigger
              render={
                <button
                  type="button"
                  aria-label="Zamknij kilka okien"
                  title="Zamknij kilka okien"
                  className="mb-1 ml-1 grid size-7 shrink-0 place-items-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground"
                />
              }
            >
              <ChevronDownIcon className="size-4" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-64">
              {closeItems(activeWindow)}
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </nav>
    </>
  )
}

/** Bieżący adres dla odliczania w tle (poza renderem, więc z `window.location`). */
const currentUrl = () => `${window.location.pathname}${window.location.search}`
