import { useDeferredValue, useMemo, useState } from 'react'
import { DownloadIcon, WandSparklesIcon } from 'lucide-react'
import { Navigate, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import {
  useCalculationLinks,
  useExportPartnerCalculations,
  usePartnerCalculations,
  usePartnerStores,
} from '@/features/deli-prices/api'
import { CalculatorTabs } from '@/features/deli-prices/CalculatorTabs'
import { formatMoney, pricePaths } from '@/features/deli-prices/format'
import { InsertPricesIntoTable } from '@/features/deli-prices/InsertPricesIntoTable'
import { PartnerLinkDialog } from '@/features/deli-prices/PartnerLinkDialog'
import { ApiError } from '@/lib/api'
import { matchesSearch } from '@/lib/search'
import type { PartnerCalculation, StoreSummary } from '@/lib/types'

/**
 * Kalkulacje sklepu zaprzyjaźnionego: jego wyroby i ceny, przypisane do naszych artykułów, bo kody są lokalne. Jednym
 * przyciskiem przypisują się pewne pary, resztę z propozycji; eksport idzie z naszymi EAN-ami i cenami sąsiada.
 */
export function PartnerCalculations({ store }: { store: StoreSummary }) {
  const { partnerStoreId } = useParams()
  const { data: partners, isPending } = usePartnerStores(store.id)
  if (isPending)
    return <div aria-hidden className="h-64 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  const partner = partners?.find((candidate) => candidate.id === partnerStoreId)
  if (!partner) return <Navigate to={pricePaths.list} replace />
  return <PartnerView key={partner.id} store={store} partnerId={partner.id} partnerName={partner.name} />
}

function PartnerView({
  store,
  partnerId,
  partnerName,
}: {
  store: StoreSummary
  partnerId: string
  partnerName: string
}) {
  const canEdit = useCan('DELI_PRICES', 'EDIT')
  const { data: calculations, isPending, isError, refetch } = usePartnerCalculations(store.id, partnerId)
  const { auto, undoAuto } = useCalculationLinks(store.id, partnerId)
  const exportFile = useExportPartnerCalculations(store.id, partnerId)
  const [query, setQuery] = useState('')
  const deferredQuery = useDeferredValue(query)
  const [searchParams, setSearchParams] = useSearchParams()
  const [linking, setLinking] = useState<PartnerCalculation | null>(null)

  const unlinkedCount = useMemo(
    () => (calculations ?? []).filter((calculation) => !calculation.link).length,
    [calculations],
  )
  const onlyUnlinked = searchParams.get('przypisanie') === 'bez' && unlinkedCount > 0
  const toggleUnlinked = () => setSearchParams(onlyUnlinked ? {} : { przypisanie: 'bez' }, { replace: true })
  const hasLinks = (calculations?.length ?? 0) > unlinkedCount

  const visible = useMemo(
    () =>
      (calculations ?? []).filter(
        (calculation) =>
          (!onlyUnlinked || !calculation.link) &&
          matchesSearch(
            [calculation.name, calculation.ean, calculation.link?.name, calculation.link?.ean],
            deferredQuery,
          ),
      ),
    [calculations, deferredQuery, onlyUnlinked],
  )
  // Cena sąsiada inna niż nasza na półce: to trafia do wprowadzania danych, z naszym EAN-em.
  const changed = useMemo(
    () =>
      (calculations ?? []).flatMap((calculation) =>
        calculation.link && calculation.salePrice != null && calculation.salePrice !== calculation.link.salePrice
          ? [{ ean: calculation.link.ean, salePrice: calculation.salePrice }]
          : [],
      ),
    [calculations],
  )

  const runAuto = () =>
    auto.mutate(undefined, {
      onSuccess: ({ ids, left }) => {
        toast.success(ids.length === 0 ? 'Nie ma nowych pewnych par' : `Przypisano automatycznie: ${ids.length}`, {
          description: left > 0 ? `Do ręcznego przypisania zostało: ${left}.` : 'Wszystkie wyroby są przypisane.',
          action:
            ids.length > 0
              ? {
                  label: 'Cofnij',
                  onClick: () =>
                    undoAuto.mutate(ids, {
                      onSuccess: () => toast.success('Cofnięto przypisanie'),
                      onError: () => toast.error('Nie udało się cofnąć przypisania'),
                    }),
                }
              : undefined,
        })
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się przypisać'),
    })

  const download = () =>
    exportFile.mutate(undefined, {
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać pliku'),
    })

  return (
    <>
      <PageHeader
        title="Kalkulator cen garmażu"
        description={`Ceny sklepu ${partnerName}. Przypisz jego wyroby do naszych artykułów, a wyeksportujesz je z naszymi EAN-ami.`}
        actions={
          <>
            {canEdit && unlinkedCount > 0 && (
              <Button variant="outline" loading={auto.isPending} onClick={runAuto}>
                <WandSparklesIcon />
                Przypisz automatycznie
              </Button>
            )}
            <InsertPricesIntoTable storeId={store.id} lines={changed} />
            {hasLinks && (
              <Button variant="outline" loading={exportFile.isPending} onClick={download}>
                <DownloadIcon />
                Eksport do Excela
              </Button>
            )}
          </>
        }
      />

      <CalculatorTabs storeId={store.id} />

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać kalkulacji"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : !isPending && calculations.length === 0 ? (
        <Card className="items-center gap-3 px-6 py-12 text-center">
          <p className="font-medium">Sklep {partnerName} nie ma jeszcze kalkulacji</p>
        </Card>
      ) : (
        <>
          <div className="mb-3 flex flex-wrap items-center gap-x-4 gap-y-2">
            <SearchInput
              className="w-full sm:w-96"
              value={query}
              onChange={setQuery}
              placeholder="Szukaj wyrobu: nazwa, EAN, nasz artykuł"
            />
            {unlinkedCount > 0 && (
              <Button
                variant={onlyUnlinked ? 'secondary' : 'outline'}
                aria-pressed={onlyUnlinked}
                onClick={toggleUnlinked}
              >
                Nieprzypisane ({unlinkedCount})
              </Button>
            )}
          </div>

          <Card className="py-0">
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead className="pl-4">Wyrób sklepu {partnerName}</TableHead>
                  <TableHead className="text-right">Ich cena</TableHead>
                  <TableHead>Nasz artykuł</TableHead>
                  <TableHead className="pr-4 text-right">Nasza na półce</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {isPending ? (
                  <TableSkeletonRows columns={4} rows={4} />
                ) : visible.length === 0 ? (
                  <TableRow className="hover:bg-transparent">
                    <TableCell colSpan={4} className="py-8 text-center text-sm text-muted-foreground">
                      Żaden wyrób nie pasuje.
                    </TableCell>
                  </TableRow>
                ) : (
                  visible.map((calculation) => (
                    <TableRow key={calculation.calculationId} className="tabular-nums">
                      <TableCell className="max-w-80 py-3 pl-4 whitespace-normal">
                        <div className="font-medium">{calculation.name}</div>
                        <div className="text-xs text-muted-foreground">EAN {calculation.ean}</div>
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="text-base font-semibold">{formatMoney(calculation.salePrice)}</div>
                        <div className="text-xs text-muted-foreground">
                          {calculation.withoutRecipe && 'z półki · '}VAT {calculation.vatRate}%
                        </div>
                      </TableCell>
                      <TableCell className="max-w-80 whitespace-normal">
                        <LinkCell calculation={calculation} canEdit={canEdit} onOpen={() => setLinking(calculation)} />
                      </TableCell>
                      <TableCell className="pr-4 text-right text-muted-foreground">
                        {formatMoney(calculation.link?.salePrice)}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </Card>
        </>
      )}

      <PartnerLinkDialog
        storeId={store.id}
        partnerStoreId={partnerId}
        target={linking}
        onClose={() => setLinking(null)}
      />
    </>
  )
}

function LinkCell({
  calculation,
  canEdit,
  onOpen,
}: {
  calculation: PartnerCalculation
  canEdit: boolean
  onOpen: () => void
}) {
  const { link } = calculation
  if (!link) {
    return canEdit ? (
      <Button variant="outline" size="sm" onClick={onOpen}>
        Przypisz
      </Button>
    ) : (
      <span className="text-sm text-muted-foreground">nieprzypisany</span>
    )
  }
  const content = (
    <>
      <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="font-medium">{link.name}</span>
        {link.auto && <Badge className="bg-sky-200 text-sky-950 dark:bg-sky-900 dark:text-sky-50">automatycznie</Badge>}
      </span>
      <span className="block text-xs text-muted-foreground">EAN {link.ean}</span>
    </>
  )
  return canEdit ? (
    <button
      type="button"
      onClick={onOpen}
      title="Zmień przypisanie"
      className="-mx-1.5 rounded-lg px-1.5 py-1 text-left hover:bg-muted"
    >
      {content}
    </button>
  ) : (
    content
  )
}
