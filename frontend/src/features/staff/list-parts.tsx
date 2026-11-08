import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

/** Filtr działu na listach ludzi (Pracownicy, Kadry): kilka działów z liczbą osób widać naraz, bez rozwijania. */
export function Chip({
  active,
  count,
  onClick,
  children,
}: {
  active: boolean
  count: number
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={cn(
        'inline-flex h-8 shrink-0 items-center gap-1.5 rounded-full border px-3 text-sm whitespace-nowrap outline-none transition-[background-color,color,border-color,transform] duration-150 ease-out focus-visible:ring-3 focus-visible:ring-ring/50 active:scale-[0.97] motion-reduce:active:scale-100',
        active
          ? 'border-foreground bg-foreground text-background'
          : 'border-border bg-card text-foreground hover:bg-muted dark:bg-input/30',
      )}
    >
      {children}
      <span className={cn('text-xs tabular-nums', active ? 'opacity-70' : 'text-muted-foreground')}>{count}</span>
    </button>
  )
}

/** Inicjały w stałym miejscu przed nazwiskiem, żeby nazwiska zaczynały się w jednej linii i dało się je przebiec okiem. */
export function Initials({ name, muted }: { name: string; muted?: boolean }) {
  const letters = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase())
    .join('')
  return (
    <span
      aria-hidden
      className={cn(
        'flex size-9 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold',
        muted ? 'text-muted-foreground' : 'text-foreground',
      )}
    >
      {letters}
    </span>
  )
}
