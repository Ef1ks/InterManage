import { useCallback, useEffect, useState } from 'react'
import { ChevronDownIcon, ChevronUpIcon, ListIcon, MinusIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export type CategoryNavItem = { id: string; name: string; count: number }

/** Id elementu kategorii na stronie; sekcja kategorii dostaje je razem z `scroll-mt-20` (pod przyklejonym nagłówkiem). */
export const categoryAnchorId = (categoryId: string) => `kategoria-${categoryId}`

/** Kategoria liczy się jako „ta, przy której jesteśmy”, gdy jej nagłówek dojedzie pod pasek aplikacji. */
const REACHED_OFFSET = 100

/**
 * Szybkie przejście między kategoriami działu. Na komputerze spis kategorii w prawym dolnym rogu (można go zwinąć),
 * na telefonie strzałki po lewej: poprzednia / następna kategoria.
 */
export function CategoryNav({
  items,
  aboveStickyFooter = false,
}: {
  items: CategoryNavItem[]
  /** Strona ma przyklejoną stopkę (np. wysyłka zamówienia) - podnosimy nawigację nad nią. */
  aboveStickyFooter?: boolean
}) {
  const [current, setCurrent] = useState(-1)
  const [expanded, setExpanded] = useState(true)

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

  const jumpTo = useCallback(
    (index: number) => {
      const item = items[index]
      if (!item) return
      const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
      document
        .getElementById(categoryAnchorId(item.id))
        ?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
    },
    [items],
  )

  if (items.length < 2) return null

  return (
    <>
      {/* Telefon: strzałki po lewej, nad dolną krawędzią. */}
      <div className={cn(
          'fixed left-4 z-20 flex flex-col gap-2 md:hidden',
          aboveStickyFooter ? 'bottom-36' : 'bottom-4',
        )}>
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

      {/* Komputer: spis kategorii w prawym dolnym rogu. */}
      <nav
        aria-label="Kategorie działu"
        className={cn('fixed right-6 z-20 hidden md:block', aboveStickyFooter ? 'bottom-28' : 'bottom-6')}
      >
        {expanded ? (
          <div className="w-60 overflow-hidden rounded-xl bg-background shadow-[0_8px_24px_-8px_rgb(0_0_0/0.18)] ring-1 ring-foreground/10">
            <div className="flex items-center justify-between border-b py-1 pr-1 pl-3">
              <span className="text-xs font-medium tracking-wide text-muted-foreground uppercase">Kategorie</span>
              <Button variant="ghost" size="icon-sm" aria-label="Zwiń spis kategorii" onClick={() => setExpanded(false)}>
                <MinusIcon />
              </Button>
            </div>
            <ol className="max-h-[45svh] overflow-y-auto overscroll-contain p-1">
              {items.map((item, index) => (
                <li key={item.id}>
                  <button
                    type="button"
                    aria-current={index === current ? 'location' : undefined}
                    onClick={() => jumpTo(index)}
                    className={cn(
                      'flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-left text-sm text-muted-foreground transition-colors duration-150 outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
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
        ) : (
          <Button
            variant="outline"
            className="rounded-full bg-background shadow-md"
            aria-label="Pokaż spis kategorii"
            onClick={() => setExpanded(true)}
          >
            <ListIcon />
            Kategorie
            <span className="text-muted-foreground tabular-nums">{items.length}</span>
          </Button>
        )}
      </nav>
    </>
  )
}
