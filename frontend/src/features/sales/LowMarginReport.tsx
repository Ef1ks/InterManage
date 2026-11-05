import { TriangleAlertIcon } from 'lucide-react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { FormNote, MultiComboboxField } from '@/components/form'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { formatMoney, formatPercent, formatPrice, formatQuantity } from '@/features/documents/format'
import { useCan } from '@/features/auth/api'
import { useLowMargins, useSetLowMarginExcludedSuppliers } from '@/features/sales/api'
import { ReviewedReportPage } from '@/features/sales/ReviewedReportPage'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { ApiError } from '@/lib/api'
import type { LowMarginDelivery, LowMarginPromotion, LowMarginReport as Report, LowMarginSupplier } from '@/lib/types'

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
      intro="Przyjęcia z ostatnich 30 dni, w których artykuł ma marżę o co najmniej 10 punktów procentowych niższą niż marża wymagana na jego nomenklaturze (20% przy wymaganych 30%). Marża jest liczona od sprzedaży netto, z ceny zakupu po korektach. Gdy w dniu przyjęcia trwała promocja (z listy promocji, założona ręcznie na kasie albo z gazetki), cena promocyjna się nie liczy: marża jest z ceny katalogowej poza promocją. Raport pomija prasę od Kolportera."
      header={query.data && <ExcludedSuppliers storeId={storeId} report={query.data} />}
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
      excessLabel="za drogo"
      emptyDeliveryText="W tym przyjęciu nie ma już artykułów z za niską marżą."
      nothingFoundText="W ostatnich 30 dniach żaden artykuł nie miał marży wyraźnie niższej niż wymagana."
      renderLines={(delivery) => <LinesTable delivery={delivery} />}
    />
  )
}

const supplierLabel = (supplier: LowMarginSupplier) => `${supplier.code} ${supplier.name}`

/** Dostawcy, których nie dotyczy marża wymagana: lista dla całego sklepu, zapisuje się od razu. */
function ExcludedSuppliers({ storeId, report }: { storeId: string; report: Report }) {
  const canEdit = useCan('SALES', 'EDIT')
  const save = useSetLowMarginExcludedSuppliers(storeId)
  // Do odpowiedzi serwera pole pokazuje to, co właśnie wybrano.
  const value =
    save.isPending && save.variables ? save.variables : report.excludedSuppliers.map((supplier) => supplier.code)

  if (!canEdit) {
    if (report.excludedSuppliers.length === 0) return null
    return (
      <div className="mb-4">
        <FormNote>Marża nie dotyczy dostawców: {report.excludedSuppliers.map(supplierLabel).join(', ')}.</FormNote>
      </div>
    )
  }

  // Pomijany dostawca, którego nie ma już na liście dostawców, też musi mieć swoją etykietę.
  const missing = report.excludedSuppliers.filter(
    (supplier) => !report.suppliers.some((option) => option.code === supplier.code),
  )
  return (
    <div className="mb-4 rounded-xl border bg-card p-3">
      <MultiComboboxField
        label="Dostawcy bez marży wymaganej"
        description="Ich artykuły nie trafiają do raportu. Lista zapisuje się od razu dla całego sklepu."
        options={[...missing, ...report.suppliers].map((supplier) => ({
          value: supplier.code,
          label: supplierLabel(supplier),
        }))}
        value={value}
        placeholder="Marża dotyczy wszystkich dostawców. Szukaj po kodzie albo nazwie…"
        emptyText="Żaden dostawca nie pasuje"
        onChange={(codes) =>
          save.mutate(codes, {
            onError: (failure) =>
              toast.error(failure instanceof ApiError ? failure.message : 'Nie udało się zapisać listy dostawców'),
          })
        }
      />
    </div>
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
            <TableHead className="pr-4 text-right">Za drogo zł</TableHead>
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
                </p>
                <p className="text-xs text-muted-foreground">
                  {[line.brand, line.ean && `EAN ${line.ean}`, `ITM8 ${line.itm8}`].filter(Boolean).join(' · ')}
                </p>
              </TableCell>
              <TableCell className="text-right">{formatQuantity(line.quantity)}</TableCell>
              <TableCell className="text-right font-medium">{formatPrice(line.purchasePrice)}</TableCell>
              <TableCell className="text-right whitespace-normal">
                {formatPrice(line.salePrice)}
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
