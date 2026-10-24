import type { ReactNode } from 'react'

import { cn } from '@/lib/utils'

type FieldShellProps = {
  id: string
  error?: string
  hint?: ReactNode
  className?: string
  children: ReactNode
}

/** Kontener pola: pozycjonuje etykietę i pokazuje błąd albo podpowiedź pod spodem. */
export function FieldShell({ id, error, hint, className, children }: FieldShellProps) {
  return (
    <div className={cn('grid gap-1.5', className)}>
      <div className="relative">{children}</div>
      {error ? (
        <p id={`${id}-message`} className="px-1 text-sm text-destructive animate-in fade-in-0 duration-150">
          {error}
        </p>
      ) : (
        hint && (
          <p id={`${id}-message`} className="px-1 text-xs text-muted-foreground">
            {hint}
          </p>
        )
      )}
    </div>
  )
}
