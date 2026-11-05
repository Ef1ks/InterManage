import { useEffect, useRef } from 'react'
import { ChevronDownIcon, ChevronUpIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { displayCode, LEVEL_STYLES, type NomenclatureLevel } from '@/features/sales/nomenclature-levels'
import { cn } from '@/lib/utils'

/** Pozycja ściągi; {@code code} pusty = bez nomenklatury. */
export type NomenclatureNavItem = {
  key: string
  code: string
  name: string
  level: NomenclatureLevel
  depth: number
}

/**
 * Ściąga do szybkiego przejścia po nomenklaturze, jak spis kategorii w zamówieniu garmażu: na dużym ekranie
 * w kolumnie obok tabeli (przyklejona pod paskiem aplikacji), na mniejszych strzałki po lewej - poprzednia / następna
 * pozycja.
 */
export function NomenclatureNav({
  items,
  current,
  onJump,
}: {
  items: NomenclatureNavItem[]
  current: string | null
  onJump: (key: string) => void
}) {
  const listRef = useRef<HTMLOListElement>(null)
  const currentIndex = items.findIndex((item) => item.key === current)

  // Bieżąca pozycja zawsze widoczna w spisie, także gdy rozwinięto dużo grup. Przewijamy sam spis, nie stronę.
  useEffect(() => {
    const list = listRef.current
    const active = list?.querySelector<HTMLElement>('[aria-current="location"]')
    if (!list || !active) return
    const top = active.offsetTop - list.offsetTop
    if (top < list.scrollTop || top + active.offsetHeight > list.scrollTop + list.clientHeight) {
      const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
      list.scrollTo({
        top: top - list.clientHeight / 2 + active.offsetHeight / 2,
        behavior: reduceMotion ? 'auto' : 'smooth',
      })
    }
  }, [current])

  if (items.length < 2) return null

  return (
    <>
      <div className="fixed bottom-4 left-4 z-40 flex flex-col gap-2 xl:hidden">
        <Button
          variant="outline"
          size="icon-lg"
          className="size-11 rounded-full bg-background shadow-md"
          aria-label="Poprzednia pozycja nomenklatury"
          disabled={currentIndex <= 0}
          onClick={() => onJump(items[currentIndex - 1].key)}
        >
          <ChevronUpIcon />
        </Button>
        <Button
          variant="outline"
          size="icon-lg"
          className="size-11 rounded-full bg-background shadow-md"
          aria-label="Następna pozycja nomenklatury"
          disabled={currentIndex >= items.length - 1}
          onClick={() => onJump(items[currentIndex + 1].key)}
        >
          <ChevronDownIcon />
        </Button>
      </div>

      <nav aria-label="Nomenklatura" className="sticky top-20 hidden xl:block">
        <div className="overflow-hidden rounded-xl bg-background ring-1 ring-foreground/10">
          <div className="border-b px-3 py-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
            Nomenklatura
          </div>
          <ol ref={listRef} className="max-h-[calc(80svh-2.5rem)] overflow-y-auto overscroll-contain p-1">
            {items.map((item) => (
              <li key={item.key}>
                <button
                  type="button"
                  aria-current={item.key === current ? 'location' : undefined}
                  onClick={() => onJump(item.key)}
                  style={{ paddingLeft: `${0.5 + item.depth * 0.875}rem` }}
                  className={cn(
                    'flex w-full items-center gap-2 rounded-lg py-1.5 pr-2 text-left text-sm text-muted-foreground transition-colors duration-200 ease-out outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50',
                    item.key === current && 'bg-muted font-medium text-foreground',
                  )}
                >
                  <span aria-hidden className={cn('size-2 shrink-0 rounded-sm', LEVEL_STYLES[item.level].dot)} />
                  <span className="shrink-0 text-xs tabular-nums">{item.code ? displayCode(item.code) : '–'}</span>
                  <span className="min-w-0 flex-1 truncate">{item.name}</span>
                </button>
              </li>
            ))}
          </ol>
        </div>
      </nav>
    </>
  )
}
