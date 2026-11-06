import {
  ArrowLeftRightIcon,
  BadgeAlertIcon,
  ChevronRightIcon,
  CircleOffIcon,
  ClipboardListIcon,
  FileStackIcon,
  GiftIcon,
  type LucideIcon,
  PackageXIcon,
  ScaleIcon,
  TagIcon,
  TrendingDownIcon,
  TrophyIcon,
} from 'lucide-react'
import { Link } from 'react-router'

import { hasPermission, useCurrentUser } from '@/features/auth/api'
import { formatDocumentDate } from '@/features/documents/format'
import { useSalesReportsOverview } from '@/features/sales/api'
import { salesPaths } from '@/features/sales/paths'
import { TOOL_ROUTES } from '@/features/tools/categories'
import type { PermissionCategory } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * {@code fromDocuments}: raport liczy się z dokumentów, więc pod kafelkiem zamiast zakresu sprzedaży jest ten opis.
 * {@code permission}: narzędzie z własnej kategorii uprawnień, kafelek tylko dla osób, które ją mają.
 */
type Report = {
  path: string
  title: string
  description: string
  icon: LucideIcon
  fromDocuments?: string
  permission?: PermissionCategory
}

/** Raporty sprzedaży w sekcjach; kolejne dopisujemy tutaj jako następne kafelki. */
const REPORT_GROUPS: { title: string; reports: Report[] }[] = [
  {
    title: 'Sprzedaż i marża',
    reports: [
      {
        path: salesPaths.periodSummary,
        title: 'Podsumowanie sprzedaży i strat',
        description: 'Okres wg grup i rejonów: marża, likwidacje i marża po stratach względem budżetu. Z wydrukiem.',
        icon: ClipboardListIcon,
      },
      {
        path: salesPaths.comparison,
        title: 'Porównanie sprzedaży',
        description:
          'Dzień, tydzień, miesiąc albo własny okres z poprzednim: ilość, zakup, sprzedaż i marża wg nomenklatury.',
        icon: ArrowLeftRightIcon,
      },
      {
        path: salesPaths.topSales,
        title: 'Topy sprzedażowe',
        description:
          'Najlepsze artykuły okresu: ilość, marża zł albo marża %, wg nomenklatury i w porównaniu ze sklepami.',
        icon: TrophyIcon,
      },
    ],
  },
  {
    title: 'Weryfikacja zakupów',
    reports: [
      {
        path: salesPaths.priceCheck,
        title: 'Zakup bazowy poza bazą',
        description:
          'Artykuły z cadencier kupione u innego dostawcy niż baza w ostatnich 30 dniach. Do wyjaśnienia z dostawcą.',
        icon: ScaleIcon,
        fromDocuments: 'Z przyjęć PZ i ich korekt',
      },
      {
        path: salesPaths.overpayments,
        title: 'Przepłacone artykuły',
        description:
          'Artykuły kupione o ponad 5% drożej niż w jednej z trzech poprzednich dostaw. Do wyjaśnienia z dostawcą.',
        icon: BadgeAlertIcon,
        fromDocuments: 'Z przyjęć PZ i ich korekt',
      },
      {
        path: salesPaths.lowMargins,
        title: 'Za niskie marże',
        description:
          'Artykuły z marżą o 10 punktów niższą niż wymagana na nomenklaturze, liczoną z ceny poza promocją. Do wyjaśnienia.',
        icon: TrendingDownIcon,
        fromDocuments: 'Z przyjęć PZ i ich korekt',
      },
      {
        path: TOOL_ROUTES.MEAT_PURCHASES.path,
        title: 'Zakup mięsa',
        description:
          'Ceny zakupu mięsa z dokumentów WZ (skan odczytuje AI) i porównanie cen, sprzedaży i marży sklepów.',
        icon: TOOL_ROUTES.MEAT_PURCHASES.icon,
        fromDocuments: 'Z papierowych dokumentów WZ',
        permission: 'MEAT_PURCHASES',
      },
    ],
  },
  {
    title: 'Promocje, gratisy i protokoły',
    reports: [
      {
        path: salesPaths.promotions,
        title: 'Skuteczność promocji',
        description:
          'Czy promocja dała więcej marży niż sprzedaż bez niej: wzrost sprzedaży, próg opłacalności, marża FIFO.',
        icon: TagIcon,
      },
      {
        path: salesPaths.gratis,
        title: 'Przyjęte gratisy',
        description:
          'Gratisy od dostawców po miesiącach i rejonach: za handel i ekspozycję, za straty i ich udział w obrocie.',
        icon: GiftIcon,
        fromDocuments: 'Z przeniesień stanów zarejestrowanych jako gratis',
      },
      {
        path: salesPaths.protocols,
        title: 'Raport protokołów',
        description:
          'Likwidacje, potrzeby sklepu, reprezentacja i inne protokoły po artykule, marce, dostawcy albo nomenklaturze.',
        icon: FileStackIcon,
        fromDocuments: 'Z protokołów w raporcie Wejścia/Wyjścia',
      },
    ],
  },
  {
    title: 'Stany',
    reports: [
      {
        path: salesPaths.zeroStock,
        title: 'Raport zerówek',
        description:
          'Artykuły aktywne w cadencier ze stanem 0 w wybranej nomenklaturze: wydruk z kodami kreskowymi i plik zamówienia.',
        icon: CircleOffIcon,
        fromDocuments: 'Z kartoteki i listy cadencier',
      },
      {
        path: salesPaths.slowMoving,
        title: 'Niesprzedające się artykuły',
        description:
          'Towar na stanie z niską rotacją tygodniową w wybranym okresie, wartość stanu i dostawy z takimi artykułami. Z wydrukiem.',
        icon: PackageXIcon,
      },
    ],
  },
]

export function ReportsGrid({ storeId }: { storeId: string }) {
  const me = useCurrentUser()
  const { data } = useSalesReportsOverview(storeId)
  const coverage = !data
    ? ' '
    : data.lastSaleDate
      ? `Sprzedaż od ${formatDocumentDate(data.firstSaleDate!)} do ${formatDocumentDate(data.lastSaleDate)}`
      : 'Brak sprzedaży · wgraj raport w Imporcie danych'

  return (
    <div className="space-y-8">
      {REPORT_GROUPS.map((group) => (
        <section key={group.title}>
          <h2 className="mb-3 text-base font-semibold">{group.title}</h2>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {group.reports
              .filter((report) => !report.permission || hasPermission(me, report.permission))
              .map((report) => (
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
                    <h3 className="font-semibold">{report.title}</h3>
                    <p className="mt-0.5 text-sm text-muted-foreground">{report.description}</p>
                  </div>
                  <p className="mt-auto text-sm text-muted-foreground tabular-nums">
                    {report.fromDocuments ?? coverage}
                  </p>
                </Link>
              ))}
          </div>
        </section>
      ))}
    </div>
  )
}
