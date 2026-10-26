import { CircleHelpIcon, XIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { useAppDispatch, useAppSelector } from '@/store'
import { persistHiddenHints, setHintHidden } from '@/store/uiSlice'
import { cn } from '@/lib/utils'

type Props = {
  /** Identyfikator do zapamiętania, że użytkownik schował podpowiedź. */
  id: string
  title: string
  /** Kolejne kroki, od pierwszego. */
  steps: React.ReactNode[]
  className?: string
}

/**
 * Podpowiedź "jak to działa" w kilku krokach, dla osób, które pierwszy raz widzą ekran.
 * Po schowaniu zostaje mały przycisk, którym można ją przywrócić.
 */
export function HowTo({ id, title, steps, className }: Props) {
  const hiddenHints = useAppSelector((state) => state.ui.hiddenHints)
  const dispatch = useAppDispatch()
  const hidden = hiddenHints.includes(id)

  const setHidden = (next: boolean) => {
    dispatch(setHintHidden({ hint: id, hidden: next }))
    const others = hiddenHints.filter((hint) => hint !== id)
    persistHiddenHints(next ? [...others, id] : others)
  }

  if (hidden) {
    return (
      <div className={cn('flex justify-end', className)}>
        <Button variant="ghost" size="sm" className="text-muted-foreground" onClick={() => setHidden(false)}>
          <CircleHelpIcon />
          Jak to działa?
        </Button>
      </div>
    )
  }

  return (
    <section
      aria-label={title}
      className={cn('rounded-xl bg-muted/70 px-4 py-3.5 animate-in fade-in-0 duration-200 motion-reduce:animate-none', className)}
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold">{title}</h2>
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label="Schowaj podpowiedź"
          title="Schowaj podpowiedź"
          className="-my-1 -mr-1.5 text-muted-foreground"
          onClick={() => setHidden(true)}
        >
          <XIcon />
        </Button>
      </div>
      <ol className="mt-2.5 grid gap-x-6 gap-y-2.5 sm:grid-flow-col sm:auto-cols-fr">
        {steps.map((step, index) => (
          <li key={index} className="flex gap-2.5 text-sm text-foreground/80">
            <span
              aria-hidden
              className="flex size-5 shrink-0 items-center justify-center rounded-full bg-foreground text-[0.7rem] font-semibold text-background tabular-nums"
            >
              {index + 1}
            </span>
            <span className="text-pretty">{step}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}
