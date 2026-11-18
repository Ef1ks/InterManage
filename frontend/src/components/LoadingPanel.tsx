import { cn } from '@/lib/utils'

/**
 * Miejsce na treść, która się wczytuje: zaślepka z przesuwającym się refleksem i, po chwili, kółkiem z napisem na
 * środku. Wysokość i zaokrąglenie daje {@code className} (np. `h-72 rounded-xl`), tak jak miała zaślepka, którą
 * zastępuje, więc strona nie skacze po wczytaniu. Czytniki ekranu dostają sam napis.
 */
export function LoadingPanel({ className, label = 'Wczytuję dane…' }: { className?: string; label?: string }) {
  return (
    <div
      role="status"
      aria-live="polite"
      className={cn('loading-shimmer flex items-center justify-center rounded-xl', className)}
    >
      <span className="loading-label flex items-center gap-2.5 text-sm text-muted-foreground">
        <span
          aria-hidden
          className="loading-spinner size-4 rounded-full border-2 border-current border-r-transparent"
        />
        {label}
      </span>
    </div>
  )
}
