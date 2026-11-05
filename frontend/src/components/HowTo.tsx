import { CircleHelpIcon, XIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { useHiddenHint } from '@/lib/use-hidden-hint'
import { cn } from '@/lib/utils'

type Props = {
  /** Identyfikator do zapamiętania, że użytkownik schował podpowiedź. */
  id: string
  title: string
  /** Kolejne kroki, od pierwszego. */
  steps: React.ReactNode[]
  className?: string
  /** Przycisk przywracania stoi w nagłówku strony ({@link HowToButton}), więc schowana podpowiedź nie zajmuje wiersza. */
  reopenInHeader?: boolean
}

/** Przywraca schowaną podpowiedź; gdy jest widoczna, nic nie pokazuje. */
export function HowToButton({ id }: { id: string }) {
  const [hidden, setHidden] = useHiddenHint(id)
  if (!hidden) return null
  return (
    <Button variant="ghost" size="sm" className="text-muted-foreground" onClick={() => setHidden(false)}>
      <CircleHelpIcon />
      Jak to działa?
    </Button>
  )
}

/**
 * Podpowiedź "jak to działa" w kilku krokach, dla osób, które pierwszy raz widzą ekran.
 * Po schowaniu zostaje mały przycisk, którym można ją przywrócić.
 */
export function HowTo({ id, title, steps, className, reopenInHeader }: Props) {
  const [hidden, setHidden] = useHiddenHint(id)

  if (hidden) {
    if (reopenInHeader) return null
    return (
      <div className={cn('flex justify-end', className)}>
        <HowToButton id={id} />
      </div>
    )
  }

  return (
    <section
      aria-label={title}
      className={cn(
        'rounded-xl bg-muted/70 px-4 py-3.5 animate-in fade-in-0 duration-200 motion-reduce:animate-none',
        className,
      )}
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
