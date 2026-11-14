import { useMemo } from 'react'
import { ChevronRightIcon, CroissantIcon, PlusIcon } from 'lucide-react'
import { useNavigate, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useBakeryBons, useBakeryChecks, useCreateBakeryCheck } from '@/features/bakery-check/api'
import { bakeryCheckPath } from '@/features/bakery-check/paths'
import { WzArticlesButton } from '@/features/bakery-check/WzArticlesButton'
import { useSuppliers } from '@/features/documents/api'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import { SearchPicker } from '@/features/meat/SearchPicker'
import { ApiError } from '@/lib/api'
import { formatShortDate } from '@/lib/dates'
import type { BakeryBon, Supplier } from '@/lib/types'

/** Dostawca u góry, a pod nim jego bony: weryfikacja zaczyna się od bonu, bo z niego idzie faktura i numery WZ. */
export function CheckStart({ storeId }: { storeId: string }) {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const supplierId = params.get('dostawca')
  const { data: suppliers = [] } = useSuppliers(storeId)
  const supplier = useMemo(() => suppliers.find((item) => item.id === supplierId) ?? null, [suppliers, supplierId])
  const bons = useBakeryBons(storeId, supplierId)
  const checks = useBakeryChecks(storeId, supplierId)
  const create = useCreateBakeryCheck(storeId)

  const start = (documentId: string | null) => {
    if (!supplierId) return
    create.mutate(
      { supplierId, documentId },
      {
        onSuccess: (check) => {
          check.notices.forEach((notice) => toast.warning(notice))
          navigate(bakeryCheckPath(check.id))
        },
        onError: (failure) => {
          toast.error(failure instanceof ApiError ? failure.message : 'Nie udało się zacząć weryfikacji')
        },
      },
    )
  }

  const open = (bon: BakeryBon) => (bon.checkId ? navigate(bakeryCheckPath(bon.checkId)) : start(bon.id))

  return (
    <>
      <PageHeader
        title="Weryfikacja piekarni"
        description="Wybierz dostawcę i bon. Numery i daty WZ przyjdą z faktury, a Ty wpiszesz ilości z dokumentów."
      />

      <SearchPicker<Supplier>
        label="Dostawca"
        items={suppliers}
        value={supplier}
        onChange={(item) => setParams({ dostawca: item.id }, { replace: true })}
        itemKey={(item) => item.id}
        itemLabel={(item) => item.name}
        searchFields={(item) => [item.name, item.keyword, item.code, item.nip]}
        renderItem={(item) => (
          <span className="block min-w-0">
            <span className="block truncate font-medium">{item.name}</span>
            <span className="block truncate text-xs text-muted-foreground tabular-nums">
              {[item.code && `kod ${item.code}`, item.nip && `NIP ${item.nip}`].filter(Boolean).join(' · ')}
            </span>
          </span>
        )}
        placeholder="Szukaj dostawcy: nazwa, kod, NIP"
        emptyText="Nie ma takiego dostawcy"
        size="lg"
        className="max-w-xl"
      />

      {!supplierId ? (
        <FullPageMessage
          title="Najpierw wybierz dostawcę"
          description="Pokażemy jego bony z ostatnich 120 dni i zapisane weryfikacje."
          inline
        />
      ) : (
        <div className="mt-8 grid gap-10">
          <section>
            <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
              <h2 className="text-lg font-semibold">Bony dostawcy</h2>
              <div className="flex flex-wrap gap-2">
                {supplier && (
                  <WzArticlesButton storeId={storeId} supplierId={supplier.id} supplierName={supplier.name} />
                )}
                <Button variant="outline" onClick={() => start(null)} loading={create.isPending}>
                  <PlusIcon />
                  Weryfikacja bez bonu
                </Button>
              </div>
            </div>
            {bons.isPending ? (
              <div aria-hidden className="h-32 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
            ) : bons.isError ? (
              <p className="text-sm text-destructive">Nie udało się wczytać bonów.</p>
            ) : bons.data.length === 0 ? (
              <p className="rounded-xl border bg-card px-4 py-6 text-center text-sm text-muted-foreground">
                Ten dostawca nie ma bonów z ostatnich 120 dni. Gdy faktura jest już w KSeF, a bonu jeszcze nie ma w
                raporcie, zacznij weryfikację bez bonu i przypnij fakturę.
              </p>
            ) : (
              <div className="overflow-x-auto rounded-xl border bg-card">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>Data</TableHead>
                      <TableHead>Bon</TableHead>
                      <TableHead>Faktura</TableHead>
                      <TableHead className="text-right">Zakup netto</TableHead>
                      <TableHead className="w-0" />
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {bons.data.map((bon) => (
                      <TableRow key={bon.id}>
                        <TableCell className="whitespace-nowrap tabular-nums">
                          {formatDocumentDate(bon.documentDate)}
                        </TableCell>
                        <TableCell className="font-medium tabular-nums" title={bon.number}>
                          {bon.bonNumber}
                        </TableCell>
                        <TableCell>
                          {bon.invoices.join(', ') || bon.supplierDocumentNumber || (
                            <span className="text-muted-foreground">brak faktury</span>
                          )}
                        </TableCell>
                        <TableCell className="text-right tabular-nums">
                          {formatMoney(bon.correctedValue ?? bon.purchaseValue)}
                          {bon.correctedValue !== null && (
                            <span className="block text-xs text-muted-foreground line-through">
                              {formatMoney(bon.purchaseValue)}
                            </span>
                          )}
                        </TableCell>
                        <TableCell>
                          <Button
                            size="sm"
                            variant={bon.checkId ? 'outline' : 'default'}
                            onClick={() => open(bon)}
                            disabled={create.isPending}
                          >
                            {bon.checkId ? 'Otwórz' : 'Weryfikuj'}
                            <ChevronRightIcon />
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            )}
          </section>

          {checks.data && checks.data.length > 0 && (
            <section>
              <h2 className="mb-3 text-lg font-semibold">Zapisane weryfikacje</h2>
              <ul className="divide-y rounded-xl border bg-card">
                {checks.data.map((check) => (
                  <li key={check.id}>
                    <button
                      type="button"
                      className="flex w-full items-center gap-4 px-4 py-3 text-left transition-colors duration-150 hover:bg-muted/60 focus-visible:bg-muted/60 focus-visible:outline-none"
                      onClick={() => navigate(bakeryCheckPath(check.id))}
                    >
                      <CroissantIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium">
                          {[check.bons.length > 0 && `Bon ${check.bons.join(', ')}`, check.invoices.join(', ')]
                            .filter(Boolean)
                            .join(' · ') || 'Bez bonu i faktury'}
                        </span>
                        <span className="block truncate text-xs text-muted-foreground tabular-nums">
                          {[
                            `wpisane WZ: ${check.filledWz} z ${check.wzCount}`,
                            check.from && check.to && `${formatShortDate(check.from)} – ${formatShortDate(check.to)}`,
                            check.createdBy,
                          ]
                            .filter(Boolean)
                            .join(' · ')}
                        </span>
                      </span>
                      <ChevronRightIcon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>
      )}
    </>
  )
}
