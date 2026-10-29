import {
  ArrowLeftRightIcon,
  ChefHatIcon,
  ChevronRightIcon,
  ClipboardListIcon,
  GiftIcon,
  type LucideIcon,
  ScaleIcon,
} from 'lucide-react'
import { Link } from 'react-router'

import { formatDocumentDate } from '@/features/documents/format'
import { useSalesReportsOverview } from '@/features/sales/api'
import { salesPaths } from '@/features/sales/paths'
import { cn } from '@/lib/utils'

/** {@code fromDocuments}: raport liczy się z dokumentów, więc pod kafelkiem zamiast zakresu sprzedaży jest ten opis. */
type Report = { path: string; title: string; description: string; icon: LucideIcon; fromDocuments?: string }

/** Raporty sprzedaży; kolejne dopisujemy tutaj jako następne kafelki. */
const REPORTS: Report[] = [
  {
    path: salesPaths.periodSummary,
    title: 'Podsumowanie sprzedaży i strat',
    description: 'Okres wg grup i rejonów: marża, likwidacje i marża po stratach względem budżetu. Z wydrukiem.',
    icon: ClipboardListIcon,
  },
  {
    path: salesPaths.comparison,
    title: 'Porównanie sprzedaży',
    description: 'Dzień, tydzień, miesiąc albo własny okres z poprzednim: ilość, zakup, sprzedaż i marża wg nomenklatury.',
    icon: ArrowLeftRightIcon,
  },
  {
    path: salesPaths.deliCosts,
    title: 'Koszty garmażu',
    description: 'Sprzedaż garmażu (nomenklatura 36) z kosztem z protokołów reprezentacji zamiast zakupu FIFO.',
    icon: ChefHatIcon,
  },
  {
    path: salesPaths.priceCheck,
    title: 'Zakup bazowy poza bazą',
    description: 'Artykuły z cadencier kupione u innego dostawcy niż baza w ostatnich 30 dniach. Do wyjaśnienia z dostawcą.',
    icon: ScaleIcon,
    fromDocuments: 'Z przyjęć PZ i ich korekt',
  },
  {
    path: salesPaths.gratis,
    title: 'Przyjęte gratisy',
    description: 'Gratisy od dostawców po miesiącach i rejonach: za handel i ekspozycję, za straty i ich udział w obrocie.',
    icon: GiftIcon,
    fromDocuments: 'Z przeniesień stanów zarejestrowanych jako gratis',
  },
]

export function ReportsGrid({ storeId }: { storeId: string }) {
  const { data } = useSalesReportsOverview(storeId)
  const coverage = !data
    ? ' '
    : data.lastSaleDate
      ? `Sprzedaż od ${formatDocumentDate(data.firstSaleDate!)} do ${formatDocumentDate(data.lastSaleDate)}`
      : 'Brak sprzedaży · wgraj raport w Imporcie danych'

  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      {REPORTS.map((report) => (
        <Link
          key={report.path}
          to={report.path}
          className={cn(
            'group flex flex-col gap-3 rounded-xl border bg-card p-4 transition-[border-color,box-shadow] duration-150 outline-none',
            'hover:border-foreground/20 hover:shadow-[0_2px_8px_-2px_rgb(0_0_0/0.08)] focus-visible:ring-3 focus-visible:ring-ring/50',
          )}
        >
          <div className="flex items-start justify-between gap-3">
            <span className="flex size-10 items-center justify-center rounded-lg bg-muted text-foreground">
              <report.icon aria-hidden className="size-5" />
            </span>
            <ChevronRightIcon
              aria-hidden
              className="size-4 text-muted-foreground transition-transform duration-150 group-hover:translate-x-0.5"
            />
          </div>
          <div className="min-w-0">
            <h2 className="font-semibold">{report.title}</h2>
            <p className="mt-0.5 text-sm text-muted-foreground">{report.description}</p>
          </div>
          <p className="mt-auto text-sm text-muted-foreground tabular-nums">
            {report.fromDocuments ?? coverage}
          </p>
        </Link>
      ))}
    </div>
  )
}
