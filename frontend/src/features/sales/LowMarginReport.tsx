import { TriangleAlertIcon } from 'lucide-react'
import { Link } from 'react-router'

import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMoney, formatPercent, formatPrice, formatQuantity } from '@/features/documents/format'
import { useLowMargins } from '@/features/sales/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ExcludedSuppliersSettings } from '@/features/sales/ExcludedSuppliersSettings'
import { ReviewedReportPage } from '@/features/sales/ReviewedReportPage'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import type { LowMarginDelivery, LowMarginPromotion } from '@/lib/types'

const PROMOTION_LABELS: Record<LowMarginPromotion, string> = {
  LIST: 'z listy promocji',
  TILL: 'z plików kasy',
  LEAFLET: 'z gazetki',
  SALES: 'rozpoznana ze sprzedaży',
}

/**
 * Za niskie marże: przyjęcia z ostatnich 30 dni z artykułem, którego marża jest o co najmniej 10 punktów procentowych
 * niższa niż wymagana na jego nomenklaturze. W promocji marża liczy się z ceny zwykłej, nie promocyjnej. Sprawę wyjaśnia
 * się z dostawcą jak w zakupie bazowym poza bazą, ale statusy są osobne.
 */
export function LowMarginReport({ storeId }: { storeId: string }) {
  const query = useLowMargins(storeId)
  return (
    <ReviewedReportPage
      storeId={storeId}
      report="LOW_MARGIN"
      query={query}
      title="Za niskie marże"
      intro="Przyjęcia z ostatnich 30 dni, w których artykuł ma marżę o co najmniej 10 punktów procentowych niższą niż marża wymagana na jego nomenklaturze (20% przy wymaganych 30%). Marża jest liczona od sprzedaży netto, z ceny zakupu po korektach. Gdy w dniu przyjęcia trwała promocja (z listy promocji, założona ręcznie na kasie albo z gazetki), cena promocyjna się nie liczy: marża jest z ceny katalogowej poza promocją. Raport pomija prasę od Kolportera i artykuły aktywne w cadencier, bo mają z góry ustaloną marżę. Niebieska cena sprzedaży to CENA_CAD z kartoteki, czyli cena narzucona przez centralę."
      actions={
        query.data && (
          <ExcludedSuppliersSettings
            storeId={storeId}
            path="low-margins"
            report={query.data}
            description="Artykuły dostawców bez marży wymaganej nie trafiają do raportu. Lista obowiązuje w całym sklepie."
            label="Dostawcy bez marży wymaganej"
            placeholder="Marża dotyczy wszystkich dostawców. Szukaj po kodzie albo nazwie…"
            readOnlyLabel="Marża nie dotyczy dostawców"
          />
        )
      }
      notice={
        query.data?.marginsConfigured === false && (
          <p className="flex items-start gap-2 text-amber-700 dark:text-amber-400">
            <TriangleAlertIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
            <span>
              Sklep nie ma ustawionej marży wymaganej, więc raport nie ma z czym porównać. Ustawisz ją w{' '}
              <Link to={storeSettingsPaths.margins} className="underline underline-offset-4">
                Ustawieniach sklepu
              </Link>
              .
            </span>
          </p>
        )
      }
      linesLabel="za niska marża"
      excessLabel="brakująca marża"
      emptyDeliveryText="W tym przyjęciu nie ma już artykułów z za niską marżą."
      nothingFoundText="W ostatnich 30 dniach żaden artykuł nie miał marży wyraźnie niższej niż wymagana."
      renderLines={(delivery) => <LinesTable delivery={delivery} />}
    />
  )
}

function LinesTable({ delivery }: { delivery: LowMarginDelivery }) {
  return (
    <div className="overflow-x-auto rounded-lg border bg-background shadow-xs">
      <Table className="tabular-nums">
        <TableHeader className="bg-muted/60">
          <TableRow className="hover:bg-transparent">
            <TableHead className="pl-4">Artykuł</TableHead>
            <TableHead className="text-right">Ilość</TableHead>
            <TableHead className="text-right">
              Cena zakupu<span className="block text-xs font-normal text-muted-foreground">netto, po korektach</span>
            </TableHead>
            <TableHead className="text-right">
              Cena sprzedaży<span className="block text-xs font-normal text-muted-foreground">katalogowa, brutto</span>
            </TableHead>
            <TableHead className="text-right">Marża</TableHead>
            <TableHead className="text-right">
              Wymagana<span className="block text-xs font-normal text-muted-foreground">na nomenklaturze</span>
            </TableHead>
            <TableHead className="text-right">
              Cena zakupu<span className="block text-xs font-normal text-muted-foreground">dla marży wymaganej</span>
            </TableHead>
            <TableHead className="pr-4 text-right">Brakująca marża zł</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {delivery.lines.map((line) => (
            <TableRow key={line.itm8}>
              <TableCell className="max-w-80 pl-4 whitespace-normal">
                <p className="font-medium">
                  {line.name ?? 'Bez nazwy'}
                  {line.corrected && (
                    <span className="ml-1 text-muted-foreground" title="Po korekcie">
                      *
                    </span>
                  )}
                  <ArticleStatsButton article={line} className="ml-1" />
                </p>
                <p className="text-xs text-muted-foreground">
                  {[line.brand, line.ean && `EAN ${line.ean}`, `ITM8 ${line.itm8}`].filter(Boolean).join(' · ')}
                </p>
              </TableCell>
              <TableCell className="text-right">{formatQuantity(line.quantity)}</TableCell>
              <TableCell className="text-right font-medium">{formatPrice(line.purchasePrice)}</TableCell>
              <TableCell className="text-right whitespace-normal">
                {line.cadencierPrice ? (
                  <span
                    className="font-bold text-blue-700 dark:text-blue-300"
                    title="Cena cadencier (CENA_CAD) narzucona przez centralę"
                  >
                    {formatPrice(line.salePrice)}
                  </span>
                ) : (
                  formatPrice(line.salePrice)
                )}
                {line.promotion && (
                  <span
                    className="block text-xs text-orange-700 dark:text-orange-300"
                    title={`Promocja ${PROMOTION_LABELS[line.promotion]}`}
                  >
                    w promocji {formatPrice(line.documentSalePrice)}
                  </span>
                )}
              </TableCell>
              <TableCell className="text-right font-medium text-destructive">
                {formatPercent(line.marginPercent)}
              </TableCell>
              <TableCell className="text-right">{formatPercent(line.requiredMargin)}</TableCell>
              <TableCell className="text-right">{formatPrice(line.targetPurchasePrice)}</TableCell>
              <TableCell className="pr-4 text-right font-semibold">{formatMoney(line.excessValue)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
