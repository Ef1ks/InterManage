import { ChartColumnIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { cn } from '@/lib/utils'

const percentFormat = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 1, maximumFractionDigits: 1 })

/** Procent z jednym miejscem po przecinku; brak wartości to kreska. */
export const formatPercent = (value: number | null) => (value == null ? '–' : `${percentFormat.format(value)}%`)

/** Nazwa bez kodu z systemu sklepu: rejony mają nazwy z kodem ("01_DELIKATESY"), a kod i tak stoi obok. */
export const nomenclatureName = (name: string) => name.replace(/^\d+_/, '')

/** Ikonka wykresu przy nazwie wiersza; nie rozwija wiersza. */
export function ChartButton({
  onClick,
  label = 'Wykres sprzedaży',
  tabIndex,
  className,
}: {
  onClick: () => void
  label?: string
  tabIndex?: number
  className?: string
}) {
  return (
    <Button
      variant="ghost"
      size="icon-xs"
      type="button"
      aria-label={label}
      title={label}
      tabIndex={tabIndex}
      className={cn('-my-0.5 shrink-0 text-muted-foreground hover:text-foreground', className)}
      onClick={(event) => {
        event.stopPropagation()
        onClick()
      }}
    >
      <ChartColumnIcon />
    </Button>
  )
}

/** Kafelek sumy nad raportem. {@code strong} to główna liczba raportu, {@code good} i {@code bad} ocena wyniku. */
export function StatTile({
  label,
  value,
  detail,
  tone,
}: {
  label: string
  value: string
  detail?: string | null
  tone?: 'strong' | 'good' | 'bad'
}) {
  const muted = tone === 'strong' ? 'text-background/70' : tone ? 'opacity-80' : 'text-muted-foreground'
  return (
    <Card
      className={cn(
        'gap-1 p-4',
        tone === 'strong' && 'bg-foreground text-background ring-0',
        tone === 'good' &&
          'border-emerald-300 bg-emerald-200/70 text-emerald-950 dark:border-emerald-500/40 dark:bg-emerald-500/20 dark:text-emerald-50',
        tone === 'bad' &&
          'border-red-300 bg-red-200/70 text-red-950 dark:border-red-500/40 dark:bg-red-500/20 dark:text-red-50',
      )}
    >
      <p className={cn('text-sm', muted)}>{label}</p>
      <p className="text-2xl font-semibold tracking-tight tabular-nums">{value}</p>
      {detail && <p className={cn('text-sm tabular-nums', muted)}>{detail}</p>}
    </Card>
  )
}
