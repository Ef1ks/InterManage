import { Link } from 'react-router'

import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import {
  bonNumber,
  documentPaths,
  formatDocumentDate,
  formatMoney,
  formatPercent,
  formatPrice,
  formatQuantity,
} from '@/features/documents/format'
import { useOverpayments } from '@/features/sales/api'
import { ExcludedSuppliersSettings } from '@/features/sales/ExcludedSuppliersSettings'
import { ReviewedReportPage } from '@/features/sales/ReviewedReportPage'
import type { OverpaymentDelivery } from '@/lib/types'
import { cn } from '@/lib/utils'

/**
 * Przepłacone artykuły: przyjęcia z ostatnich 30 dni, w których artykuł kosztował o ponad 5% więcej niż w jednej
 * z trzech poprzednich dostaw z normalną ilością. Jeden wiersz na przyjęcie, rozwinięty pokazuje te artykuły i ceny,
 * z którymi je porównano. Sprawę wyjaśnia się z dostawcą jak w zakupie bazowym poza bazą, ale statusy są osobne.
 */
export function OverpaymentReport({ storeId }: { storeId: string }) {
  const query = useOverpayments(storeId)
  return (
    <ReviewedReportPage
      storeId={storeId}
      report="OVERPAYMENT"
      query={query}
      title="Przepłacone artykuły"
      intro="Przyjęcia z ostatnich 30 dni, w których artykuł kosztował o ponad 5% więcej niż w jednej z trzech poprzednich dostaw, od dowolnego dostawcy. Dostawy z ilością ponad 5 razy większą niż zwykle nie biorą udziału w porównaniu, bo to zwykle krótki termin. Poprzednie ceny są z bonu, sprzed korekt, bo baza zwraca promocję korektą. Sprawdzane przyjęcie liczy się po korektach. Raport pomija mięso, drób, garmażerkę, owoce i warzywa, bo wahania cen są tam normalne, oraz papierosy, kupowane z jednego miejsca, prasę od Kolportera i dostawców wybranych w ustawieniach raportu."
      actions={
        query.data && (
          <ExcludedSuppliersSettings
            storeId={storeId}
            path="overpayments"
            report={query.data}
            description="Dostawy pomijanych dostawców nie trafiają do raportu i nie są też ceną odniesienia dla innych. Lista obowiązuje w całym sklepie."
            label="Dostawcy pomijani w raporcie"
            placeholder="Raport obejmuje wszystkich dostawców. Szukaj po kodzie albo nazwie…"
            readOnlyLabel="Raport pomija dostawców"
          />
        )
      }
      linesLabel="droższe"
      excessLabel="przepłacone"
      emptyDeliveryText="W tym przyjęciu nie ma już przepłaconych artykułów."
      nothingFoundText="W ostatnich 30 dniach żaden artykuł nie był wyraźnie droższy niż w poprzednich dostawach."
      renderLines={(delivery) => <LinesTable delivery={delivery} />}
    />
  )
}

function LinesTable({ delivery }: { delivery: OverpaymentDelivery }) {
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
              Najniższa<span className="block text-xs font-normal text-muted-foreground">z poprzednich dostaw</span>
            </TableHead>
            <TableHead className="text-right">Drożej</TableHead>
            <TableHead className="text-right">Przepłacone zł</TableHead>
            <TableHead className="pr-4">Poprzednie dostawy (cena z bonu)</TableHead>
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
              <TableCell className="text-right">{formatPrice(line.referencePrice)}</TableCell>
              <TableCell className="text-right font-medium text-destructive">
                {formatPercent(line.overpaidPercent)}
              </TableCell>
              <TableCell className="text-right font-semibold">{formatMoney(line.excessValue)}</TableCell>
              <TableCell className="pr-4 text-xs whitespace-normal">
                <ul className="grid gap-0.5">
                  {line.references.map((reference) => (
                    <li key={reference.documentId} className="flex flex-wrap gap-x-1.5">
                      <span
                        className={cn(
                          'font-medium',
                          reference.purchasePrice === line.referencePrice && 'text-emerald-700 dark:text-emerald-300',
                        )}
                      >
                        {formatPrice(reference.purchasePrice)}
                      </span>
                      <Link
                        to={documentPaths.document('PZ', reference.documentId)}
                        className="text-muted-foreground underline-offset-4 hover:underline"
                      >
                        bon {bonNumber(reference.documentNumber)}
                      </Link>
                      <span className="text-muted-foreground">
                        {formatDocumentDate(reference.documentDate)} · {formatQuantity(reference.quantity)} szt.
                      </span>
                      {(reference.supplierCode || reference.supplierName) && (
                        <span
                          className={cn(
                            'text-muted-foreground',
                            reference.supplierCode !== line.supplierCode && 'font-medium text-foreground',
                          )}
                        >
                          · {[reference.supplierCode, reference.supplierName].filter(Boolean).join(' ')}
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
