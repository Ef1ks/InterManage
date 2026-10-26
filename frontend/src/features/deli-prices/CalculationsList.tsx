import { useDeferredValue, useMemo, useState } from 'react'
import { ChevronRightIcon, DownloadIcon, PlusIcon } from 'lucide-react'
import { Link, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { useCalculations, useExportCalculations } from '@/features/deli-prices/api'
import { formatDay, formatMoney, formatQuantity, ingredientsWord, pricePaths } from '@/features/deli-prices/format'
import { RefreshPricesButton } from '@/features/deli-prices/RefreshPricesButton'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { matchesSearch } from '@/lib/search'
import type { PriceCalculationSummary, StoreSummary } from '@/lib/types'

/**
 * Kalkulacje sklepu z wyszukiwarką (nazwa, marka, EAN wyrobu). Na górze odświeżenie cen wszystkich składników
 * (ostatnia dostawa PZ, a bez niej kartoteka) i eksport cen sprzedaży do Excela.
 */
export function CalculationsList({ store }: { store: StoreSummary }) {
  const canEdit = useCan('DELI_PRICES', 'EDIT')
  const { data: calculations, isPending, isError, refetch } = useCalculations(store.id)
  const exportFile = useExportCalculations(store.id)
  const [query, setQuery] = useState('')
  const deferredQuery = useDeferredValue(query)
  const navigate = useNavigate()

  const visible = useMemo(
    () =>
      (calculations ?? []).filter((calculation) =>
        matchesSearch([calculation.name, calculation.product?.brand, calculation.product?.ean], deferredQuery),
      ),
    [calculations, deferredQuery],
  )
  // Najstarsze odświeżenie mówi, od kiedy najdłużej czeka któraś kalkulacja.
  const oldestRefresh = useMemo(
    () =>
      (calculations ?? [])
        .map((calculation) => calculation.pricesRefreshedAt)
        .filter((value): value is string => value !== null)
        .sort()[0] ?? null,
    [calculations],
  )
  const open = (calculation: PriceCalculationSummary) => navigate(pricePaths.calculation(calculation.id))
  const hasCalculations = !isPending && (calculations?.length ?? 0) > 0

  const download = () =>
    exportFile.mutate(undefined, {
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać pliku'),
    })

  const createButton = canEdit && (
    <Button render={<Link to={pricePaths.create} />}>
      <PlusIcon />
      Stwórz nową kalkulację
    </Button>
  )

  return (
    <>
      <PageHeader
        title="Kalkulator cen garmażu"
        description={`Ceny sprzedaży wyrobów sklepu ${storeLabel(store)}: koszt składników z ostatnich dostaw, narzut 60% i VAT.`}
        actions={
          hasCalculations && (
            <>
              {canEdit && <RefreshPricesButton storeId={store.id} />}
              <Button variant="outline" loading={exportFile.isPending} onClick={download}>
                <DownloadIcon />
                Eksport do Excela
              </Button>
            </>
          )
        }
      />

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać kalkulacji"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : !isPending && calculations.length === 0 ? (
        <Card className="items-center gap-3 px-6 py-12 text-center">
          <p className="font-medium">Nie ma jeszcze żadnej kalkulacji</p>
          <p className="max-w-md text-sm text-muted-foreground">
            Wybierz wyrób z kartoteki, dodaj składniki i ich ilości, a kalkulator policzy cenę sprzedaży z cen ostatnich
            dostaw.
          </p>
          {createButton}
        </Card>
      ) : (
        <>
          <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
              <SearchInput
                className="w-full sm:w-96"
                value={query}
                onChange={setQuery}
                placeholder="Szukaj wyrobu: nazwa, marka, EAN"
              />
              {oldestRefresh && (
                <span className="text-sm text-muted-foreground tabular-nums">
                  Ceny składników z {formatDay(oldestRefresh)} lub nowsze
                </span>
              )}
            </div>
            {createButton}
          </div>

          <Card className="py-0">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="pl-4">Wyrób</TableHead>
                  <TableHead className="text-right">Koszt 1 kg</TableHead>
                  <TableHead className="text-right">Cena sprzedaży</TableHead>
                  <TableHead className="text-right">Na półce</TableHead>
                  <TableHead>Zmieniono</TableHead>
                  <TableHead className="w-10 pr-4">
                    <span className="sr-only">Otwórz</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {isPending ? (
                  <TableSkeletonRows columns={6} rows={4} />
                ) : visible.length === 0 ? (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={6} className="py-8 text-center text-sm text-muted-foreground">
                      Żadna kalkulacja nie pasuje do „{deferredQuery}”.
                    </TableCell>
                  </TableRow>
                ) : (
                  visible.map((calculation) => (
                    <TableRow
                      key={calculation.id}
                      tabIndex={0}
                      role="link"
                      aria-label={`Kalkulacja ${calculation.name}`}
                      onClick={() => open(calculation)}
                      onKeyDown={(event) => event.key === 'Enter' && open(calculation)}
                      className="cursor-pointer tabular-nums outline-none focus-visible:bg-muted"
                    >
                      <TableCell className="max-w-96 py-3 pl-4 whitespace-normal">
                        <div className="font-medium">{calculation.name}</div>
                        <div className="text-xs text-muted-foreground">{describe(calculation)}</div>
                      </TableCell>
                      <TableCell className="text-right">
                        {formatMoney(calculation.costPerKg)}
                        {calculation.missingPriceCount > 0 && (
                          <div className="text-xs text-amber-700 dark:text-amber-400">
                            bez ceny: {calculation.missingPriceCount}
                          </div>
                        )}
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="text-base font-semibold">{formatMoney(calculation.salePrice)}</div>
                        <div className="text-xs text-muted-foreground">
                          {calculation.manualPrice != null
                            ? `własna · wyliczona ${formatMoney(calculation.calculatedPrice)}`
                            : `VAT ${calculation.vatRate}%`}
                        </div>
                      </TableCell>
                      <TableCell className="text-right text-muted-foreground">
                        {formatMoney(calculation.product?.salePrice)}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {formatDay(calculation.updatedAt)}
                        {calculation.updatedBy && <div className="text-xs">{calculation.updatedBy}</div>}
                      </TableCell>
                      <TableCell className="pr-4 text-right">
                        <ChevronRightIcon aria-hidden className="ml-auto size-4 text-muted-foreground" />
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </Card>
        </>
      )}
    </>
  )
}

/** "EAN 5900… · 4 składniki · przepis na 3 kg" albo "· skład na 1 kg". */
function describe(calculation: PriceCalculationSummary) {
  const parts = [
    calculation.product && `EAN ${calculation.product.ean}`,
    `${calculation.itemCount} ${ingredientsWord(calculation.itemCount)}`,
    calculation.mode === 'PER_BATCH'
      ? `przepis na ${calculation.batchYield != null ? formatQuantity(calculation.batchYield) : '?'} kg`
      : 'skład na 1 kg',
  ]
  return parts.filter(Boolean).join(' · ')
}
