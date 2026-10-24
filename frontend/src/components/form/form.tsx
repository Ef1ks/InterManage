import type { ComponentProps, ReactNode } from 'react'
import { CircleAlertIcon } from 'lucide-react'

import { cn } from '@/lib/utils'

type FormProps = ComponentProps<'form'> & {
  /** Ogólny błąd (np. z serwera) pokazywany nad polami. */
  error?: string | null
}

/** Formularz z odstępami między polami i miejscem na błąd ogólny. Walidację robi react-hook-form, nie przeglądarka. */
export function Form({ error, className, children, ...props }: FormProps) {
  return (
    <form noValidate className={cn('grid gap-4', className)} {...props}>
      {error && <FormAlert>{error}</FormAlert>}
      {children}
    </form>
  )
}

export function FormAlert({ children }: { children: ReactNode }) {
  return (
    <div
      role="alert"
      className="flex items-start gap-2 rounded-xl bg-destructive/8 px-3 py-2.5 text-sm text-destructive animate-in fade-in-0 slide-in-from-top-1 duration-200 dark:bg-destructive/15"
    >
      <CircleAlertIcon className="mt-0.5 size-4 shrink-0" aria-hidden />
      <span>{children}</span>
    </div>
  )
}

/** Notatka w formularzu tłumacząca, co się stanie po zapisaniu. */
export function FormNote({ icon, children }: { icon?: ReactNode; children: ReactNode }) {
  return (
    <div className="flex items-start gap-2.5 rounded-xl bg-muted/70 px-3 py-2.5 text-sm text-muted-foreground [&_svg]:mt-0.5 [&_svg]:size-4 [&_svg]:shrink-0">
      {icon}
      <span>{children}</span>
    </div>
  )
}

/** Przyciski na dole formularza: główna akcja po prawej, na telefonie na całą szerokość. */
export function FormActions({ className, children }: { className?: string; children: ReactNode }) {
  return <div className={cn('flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end', className)}>{children}</div>
}
