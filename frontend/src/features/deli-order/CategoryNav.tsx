import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronDownIcon, ChevronUpIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export type CategoryNavItem = { id: string; name: string; count: number }

/** Id elementu kategorii na stronie; sekcja kategorii dostaje je razem z `scroll-mt-20` (pod przyklejonym nagłówkiem). */
export const categoryAnchorId = (categoryId: string) => `kategoria-${categoryId}`

/** Kategoria liczy się jako „ta, przy której jesteśmy”, gdy jej nagłówek dojedzie pod pasek aplikacji. */
const REACHED_OFFSET = 100

/** Siatka strony z kolumną na spis kategorii (od `lg`), żeby spis nie zasłaniał formularza. */
export const CATEGORY_NAV_GRID = 'lg:grid lg:grid-cols-[minmax(0,1fr)_13rem] lg:items-start lg:gap-6'

const smoothBehavior = (): ScrollBehavior =>
  window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'

/**
 * Szybkie przejście między kategoriami działu. Na komputerze spis kategorii w kolumnie po prawej, przyklejony pod
 * paskiem aplikacji (rodzic układa stronę w {@link CATEGORY_NAV_GRID}), na telefonie i tablecie strzałki po lewej:
 * poprzednia / następna kategoria.
 */
export function CategoryNav({
  items,
  aboveStickyFooter = false,
}: {
  items: CategoryNavItem[]
  /** Strona ma przyklejoną stopkę (np. wysyłka zamówienia) - podnosimy strzałki nad nią. */
  aboveStickyFooter?: boolean
}) {
  const [current, setCurrent] = useState(-1)
  const listRef = useRef<HTMLOListElement>(null)

  useEffect(() => {
    let frame = 0
    const measure = () => {
      frame = 0
      let reached = -1
      items.forEach((item, index) => {
        const top = document.getElementById(categoryAnchorId(item.id))?.getBoundingClientRect().top
        if (top !== undefined && top <= REACHED_OFFSET) reached = index
      })
      // Na samym dole strony ostatnie kategorie mogą nie dojechać do góry - wtedy zaznaczamy ostatnią.
      const atBottom = window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 4
      setCurrent(atBottom && items.length > 0 && window.scrollY > 0 ? items.length - 1 : reached)
    }
    const onScroll = () => {
      if (!frame) frame = requestAnimationFrame(measure)
    }
    measure()
    window.addEventListener('scroll', onScroll, { passive: true })
    window.addEventListener('resize', onScroll)
    return () => {
      window.removeEventListener('scroll', onScroll)
      window.removeEventListener('resize', onScroll)
      cancelAnimationFrame(frame)
    }
  }, [items])

  // Bieżąca kategoria zostaje widoczna w długim spisie.
  useEffect(() => {
    const list = listRef.current
    const active = list?.querySelector<HTMLElement>('[aria-current="location"]')
    if (!list || !active) return
    const top = active.offsetTop - list.offsetTop
    if (top < list.scrollTop || top + active.offsetHeight > list.scrollTop + list.clientHeight) {
      list.scrollTo({ top: top - list.clientHeight / 2 + active.offsetHeight / 2, behavior: smoothBehavior() })
    }
  }, [current])

  const jumpTo = useCallback(
    (index: number) => {
      const item = items[index]
      if (!item) return
      document.getElementById(categoryAnchorId(item.id))?.scrollIntoView({ behavior: smoothBehavior(), block: 'start' })
    },
    [items],
  )

  if (items.length < 2) return null

  return (
    <>
      {/* Telefon i tablet: strzałki po lewej, nad dolną krawędzią. */}
      <div
        className={cn(
          'fixed left-4 z-20 flex flex-col gap-2 lg:hidden',
          aboveStickyFooter ? 'bottom-36' : 'bottom-4',
        )}
      >
        <Button
          variant="outline"
          size="icon-lg"
          className="size-11 rounded-full bg-background shadow-md"
          aria-label="Poprzednia kategoria"
          disabled={current <= 0}
          onClick={() => jumpTo(current - 1)}
        >
          <ChevronUpIcon />
        </Button>
        <Button
          variant="outline"
          size="icon-lg"
          className="size-11 rounded-full bg-background shadow-md"
          aria-label="Następna kategoria"
          disabled={current >= items.length - 1}
          onClick={() => jumpTo(current + 1)}
        >
          <ChevronDownIcon />
        </Button>
      </div>

      {/* Komputer: spis kategorii w kolumnie obok, przyklejony pod paskiem aplikacji. */}
      <nav aria-label="Kategorie działu" className="sticky top-20 hidden lg:block">
        <div className="overflow-hidden rounded-xl bg-background ring-1 ring-foreground/10">
          <div className="border-b px-3 py-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
            Kategorie
          </div>
          <ol ref={listRef} className="max-h-[calc(100svh-14rem)] overflow-y-auto overscroll-contain p-1">
            {items.map((item, index) => (
              <li key={item.id}>
                <button
                  type="button"
                  aria-current={index === current ? 'location' : undefined}
                  onClick={() => jumpTo(index)}
                  className={cn(
                    'flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm text-muted-foreground transition-colors duration-200 ease-out outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
                    index === current && 'bg-muted font-medium text-foreground',
                  )}
                >
                  <span className="min-w-0 flex-1 truncate">{item.name}</span>
                  <span className="text-xs text-muted-foreground tabular-nums">{item.count}</span>
                </button>
              </li>
            ))}
          </ol>
        </div>
      </nav>
    </>
  )
}
