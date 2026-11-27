import { useState } from 'react'
import {
  CheckIcon,
  ClipboardCheckIcon,
  FileOutputIcon,
  FileSpreadsheetIcon,
  FileTextIcon,
  PackageXIcon,
  PrinterIcon,
  ScanBarcodeIcon,
  ShoppingCartIcon,
  Trash2Icon,
  Undo2Icon,
} from 'lucide-react'
import { useNavigate, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo, HowToButton } from '@/components/HowTo'
import { LoadingPanel } from '@/components/LoadingPanel'
import { PageHeader } from '@/components/PageHeader'
import { SavedFolder } from '@/components/SavedFolder'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableFooter, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError, downloadFile, downloadPostedFile, fetchPostedFile, printFile } from '@/lib/api'
import { PROTOCOLS, useProtoFolder, useProtoSettings } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import { ZeroStockOrderDialog } from '@/features/sales/ZeroStockOrderDialog'
import type { CollectorList, CollectorListDetails, CollectorListOutput, ProtocolKind } from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { cn } from '@/lib/utils'

import { useSupplierReturnActions } from '@/features/supplier-returns/api'
import { supplierReturnsPath } from '@/features/supplier-returns/paths'

import { InventoryDialog } from './InventoryDialog'
import { collectorPaths } from './paths'
import { markLoaded, useCreateCollectorFile } from './files-api'
import {
  listQuery,
  useCollectorListDetails,
  useCollectorLists,
  useMarkProtocolSaved,
  useRemoveCollectorList,
  useRestoreCollectorList,
  useUndoProtocol,
} from './lists-api'

const HINT = 'collector-lists'
const PAGE = 100

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })
const money = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const quantity = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 3 })

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)
const OUTPUT_LABELS: Partial<Record<CollectorListOutput, string>> = {
  ORDER: 'Zamówienie',
  INVENTORY: 'Inwentaryzacja',
  SUPPLIER_RETURN: 'Zwrot do dostawcy',
}
const protocolLabel = (kind: CollectorListOutput) =>
  OUTPUT_LABELS[kind] ?? PROTOCOLS.find((item) => item.kind === kind)?.label ?? kind
const plural = new Intl.PluralRules('pl-PL')
const LINES: Partial<Record<Intl.LDMLPluralRule, string>> = { one: 'pozycja', few: 'pozycje' }
const lineCount = (count: number) => `${count} ${LINES[plural.select(count)] ?? 'pozycji'}`

/**
 * Dane z kolektorów: listy zeskanowane w „Protokołach” kolektora i wysłane po włożeniu na podstawkę. Wybór protokołu
 * od razu tworzy plik dla programu sklepu (agent albo folder przeglądarki); kilka list można złożyć w jeden protokół.
 */
export function CollectorListsPage() {
  const store = useCurrentStore()
  return (
    <div data-wide-page>
      <PageHeader
        title="Dane z kolektorów"
        description={store ? storeLabel(store) : undefined}
        actions={<HowToButton id={HINT} />}
      />
      {store ? (
        <Lists key={store.id} storeId={store.id} />
      ) : (
        <FullPageMessage title="Brak sklepu" description="Listy z kolektorów są w konkretnym sklepie." inline />
      )}
    </div>
  )
}

function Lists({ storeId }: { storeId: string }) {
  const lists = useCollectorLists(storeId)
  const [params, setParams] = useSearchParams()

  if (lists.isPending) return <LoadingPanel className="h-72 rounded-xl" />
  if (lists.isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać list z kolektorów"
        action={{ label: 'Spróbuj ponownie', onClick: () => lists.refetch() }}
        inline
      />
    )
  }
  const known = new Set(lists.data.map((list) => list.id))
  const picked = params.getAll('lista').filter((id) => known.has(id))
  // Bez wyboru w adresie otwarta jest najnowsza lista czekająca na protokół, a bez niej najnowsza w ogóle.
  const initial = lists.data.find((list) => list.protocols.length === 0) ?? lists.data[0]
  const selected = picked.length > 0 ? picked : initial ? [initial.id] : []

  const select = (ids: string[]) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        next.delete('lista')
        ids.forEach((id) => next.append('lista', id))
        return next
      },
      { replace: true },
    )

  return (
    <div className="grid gap-4">
      <HowTo
        id={HINT}
        title="Protokół z kolektora"
        reopenInHeader
        steps={[
          'Na kolektorze wybierz „2 Protokoły”, skanuj artykuły i wpisuj ilości.',
          'Włóż kolektor na podstawkę. Lista pojawi się tutaj sama.',
          'Wybierz protokół. Plik od razu trafi do programu sklepu.',
        ]}
      />
      {lists.data.length === 0 ? (
        <FullPageMessage
          title="Brak list z kolektorów"
          description="Lista pojawi się tu po włożeniu kolektora na podstawkę."
          inline
        />
      ) : (
        <div className="grid items-start gap-4 lg:grid-cols-[15rem_minmax(0,1fr)]">
          <ListPicker storeId={storeId} lists={lists.data} selected={selected} onSelect={select} />
          <Details storeId={storeId} ids={selected} lists={lists.data} />
        </div>
      )}
    </div>
  )
}

function ListPicker({
  storeId,
  lists,
  selected,
  onSelect,
}: {
  storeId: string
  lists: CollectorList[]
  selected: string[]
  onSelect: (ids: string[]) => void
}) {
  const remove = useRemoveCollectorList(storeId)
  const restore = useRestoreCollectorList(storeId)

  const toggle = (id: string) => {
    const next = selected.includes(id) ? selected.filter((other) => other !== id) : [...selected, id]
    onSelect(next.length > 0 ? next : [id])
  }

  const removeList = (list: CollectorList) =>
    remove.mutate(list.id, {
      onSuccess: () => {
        onSelect(selected.filter((id) => id !== list.id))
        toast.success(`Usunięto listę z ${list.collectorName}`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(list.id, {
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić listy.')),
              }),
          },
        })
      },
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć listy.')),
    })

  return (
    <Card className="gap-0 overflow-hidden p-0 lg:sticky lg:top-16 lg:max-h-[calc(100dvh-5rem)] lg:overflow-y-auto">
      <ul className="divide-y">
        {lists.map((list) => {
          const active = selected.includes(list.id)
          return (
            <li key={list.id} className={cn('flex items-start gap-3 px-3 py-2.5', active && 'bg-muted')}>
              <input
                type="checkbox"
                checked={active}
                onChange={() => toggle(list.id)}
                aria-label={`Wybierz listę z ${list.collectorName}, ${dateTime.format(new Date(list.receivedAt))}`}
                className="mt-1 size-4 shrink-0 accent-foreground"
              />
              <button
                type="button"
                onClick={() => onSelect([list.id])}
                className="grid min-w-0 flex-1 gap-1 rounded-md text-left text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                <span className="flex items-center gap-1.5 font-medium">
                  <ScanBarcodeIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                  <span className="min-w-0 truncate">{list.collectorName}</span>
                </span>
                <span className="text-xs text-muted-foreground tabular-nums">
                  {dateTime.format(new Date(list.receivedAt))}, {lineCount(list.lineCount)}
                </span>
                <span className="flex flex-wrap gap-1">
                  {list.protocols.length === 0 ? (
                    <Badge className="bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100">
                      czeka na protokół
                    </Badge>
                  ) : (
                    list.protocols.map((protocol) => (
                      <Badge
                        key={protocol.id}
                        title={`${protocol.createdBy ?? ''} ${dateTime.format(new Date(protocol.createdAt))}`.trim()}
                        className="bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100"
                      >
                        <CheckIcon aria-hidden />
                        {protocolLabel(protocol.kind)}
                      </Badge>
                    ))
                  )}
                </span>
              </button>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Usuń listę z ${list.collectorName}`}
                title="Usuń listę"
                onClick={() => removeList(list)}
                disabled={remove.isPending}
              >
                <Trash2Icon aria-hidden />
              </Button>
            </li>
          )
        })}
      </ul>
    </Card>
  )
}

function Details({ storeId, ids, lists }: { storeId: string; ids: string[]; lists: CollectorList[] }) {
  const details = useCollectorListDetails(storeId, ids)
  if (details.isPending) return <LoadingPanel className="h-96 rounded-xl" />
  if (details.isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać listy"
        description={errorMessage(details.error, '')}
        action={{ label: 'Spróbuj ponownie', onClick: () => details.refetch() }}
        inline
      />
    )
  }
  return (
    <div className="grid min-w-0 gap-4">
      <Actions
        storeId={storeId}
        ids={ids}
        lists={lists.filter((list) => ids.includes(list.id))}
        rows={details.data.rows}
      />
      <Rows key={ids.join()} details={details.data} />
    </div>
  )
}

function Actions({
  storeId,
  ids,
  lists,
  rows,
}: {
  storeId: string
  ids: string[]
  lists: CollectorList[]
  rows: CollectorListDetails['rows']
}) {
  const { data: settings } = useProtoSettings(storeId)
  const folder = useProtoFolder(storeId)
  const markSaved = useMarkProtocolSaved(storeId)
  const createFile = useCreateCollectorFile(storeId)
  const returns = useSupplierReturnActions(storeId)
  const navigate = useNavigate()
  const [inventory, setInventory] = useState(false)
  const undo = useUndoProtocol(storeId)
  const [pending, setPending] = useState<ProtocolKind | null>(null)
  const [repeating, setRepeating] = useState<CollectorListOutput | null>(null)
  const [ordering, setOrdering] = useState(false)
  // Do zamówienia tylko artykuły z ilością na plusie (lista bywa różnicą ilości), ilość listy jako liczba kartonów.
  const orderItems = rows
    .filter((row) => row.quantity > 0)
    .map((row) => ({ itm8: row.itm8, ean: row.ean, name: row.name, brand: row.brand, stock: null }))
  const listQuantity = new Map(rows.map((row) => [row.itm8, row.quantity]))
  const [missingFolder, setMissingFolder] = useState<ProtocolKind | null>(null)
  const visible = PROTOCOLS.filter((protocol) => !settings?.hiddenProtocols.includes(protocol.kind))
  const done = lists.flatMap((list) => list.protocols.map((protocol) => ({ list, protocol })))
  const base = `/stores/${storeId}/collector-lists`
  const query = listQuery(ids)

  const confirmSaved = async (kind: ProtocolKind, fileName: string) => {
    await markSaved.mutateAsync({ kind, ids, fileName })
    toast.success(`Zapisano protokół: ${protocolLabel(kind)}`, { description: 'Program sklepu wczyta go sam.' })
  }

  const save = async (kind: ProtocolKind) => {
    setPending(kind)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu; przy agencie sklepu nie jest potrzebna.
      if (!(await ensureFolderAccess(folder.key))) {
        setMissingFolder(kind)
        return
      }
      const { fileName, blob } = await fetchPostedFile(`${base}/protocols/${kind}`, { ids }, 'PROTO.Traite0')
      await saveToFolder(folder.key, fileName, blob)
      setMissingFolder(null)
      await confirmSaved(kind, fileName)
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się utworzyć protokołu.'))
    } finally {
      setPending(null)
    }
  }

  const download = async (kind: ProtocolKind) => {
    setPending(kind)
    try {
      await downloadPostedFile(`${base}/protocols/${kind}`, { ids }, 'PROTO.Traite0')
      setMissingFolder(null)
      toast.success('Pobrano plik protokołu', {
        description: 'Gdy skopiujesz go do folderu programu sklepu, oznacz listę.',
        action: { label: 'Oznacz', onClick: () => void confirmSaved(kind, 'pobrany plik') },
      })
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się pobrać protokołu.'))
    } finally {
      setPending(null)
    }
  }

  // Zwrot trafia do Zakupów; dostawcę podpowiada ostatnia dostawa artykułów.
  const createReturn = () =>
    returns.fromCollector.mutate(
      { listIds: ids },
      {
        onSuccess: (created) =>
          toast.success('Utworzono zwrot do dostawcy', {
            description: created.summary.supplier
              ? `Dostawca: ${created.summary.supplier.name}`
              : 'Wybierz dostawcę w zwrocie.',
            action: {
              label: 'Otwórz',
              onClick: () => navigate(`${supplierReturnsPath}?zwrot=${created.summary.id}`),
            },
          }),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się utworzyć zwrotu.')),
      },
    )

  const file = (request: Promise<void>, fallback: string) =>
    request.catch((error: unknown) => {
      toast.error(errorMessage(error, fallback))
    })

  return (
    <Card className="grid gap-3 p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-medium">
          {lists.length === 1 ? `Protokół z listy: ${lists[0].collectorName}` : `Jeden protokół z ${lists.length} list`}
        </h2>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => void file(downloadFile(`${base}/export/xlsx?${query}`), 'Nie udało się pobrać Excela.')}
          >
            <FileSpreadsheetIcon aria-hidden />
            Excel
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void file(downloadFile(`${base}/export/pdf?${query}`), 'Nie udało się pobrać PDF-a.')}
          >
            <FileTextIcon aria-hidden />
            PDF
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => void file(printFile(`${base}/export/pdf?${query}`), 'Nie udało się wydrukować listy.')}
          >
            <PrinterIcon aria-hidden />
            Drukuj
          </Button>
        </div>
      </div>

      {visible.length === 0 && (
        <p className="text-sm text-muted-foreground">Wszystkie protokoły są ukryte w ustawieniach sklepu.</p>
      )}
      <div className="flex flex-wrap gap-2">
        {visible.map((protocol) => (
          <Button
            key={protocol.kind}
            onClick={() =>
              done.some((item) => item.protocol.kind === protocol.kind)
                ? setRepeating(protocol.kind)
                : void save(protocol.kind)
            }
            loading={pending === protocol.kind}
            disabled={pending !== null}
          >
            <FileOutputIcon aria-hidden />
            {protocol.label}
          </Button>
        ))}
        <Button
          variant="outline"
          disabled={pending !== null || orderItems.length === 0}
          title={orderItems.length === 0 ? 'Na liście nie ma artykułów z ilością do zamówienia' : undefined}
          onClick={() =>
            done.some((item) => item.protocol.kind === 'ORDER') ? setRepeating('ORDER') : setOrdering(true)
          }
        >
          <ShoppingCartIcon aria-hidden />
          Zamówienie
        </Button>
        <Button
          variant="outline"
          disabled={pending !== null}
          onClick={() =>
            done.some((item) => item.protocol.kind === 'INVENTORY') ? setRepeating('INVENTORY') : setInventory(true)
          }
        >
          <ClipboardCheckIcon aria-hidden />
          Inwentaryzacja
        </Button>
        <Button
          variant="outline"
          loading={returns.fromCollector.isPending}
          disabled={pending !== null}
          onClick={() =>
            done.some((item) => item.protocol.kind === 'SUPPLIER_RETURN')
              ? setRepeating('SUPPLIER_RETURN')
              : createReturn()
          }
        >
          <PackageXIcon aria-hidden />
          Zwrot do dostawcy
        </Button>
      </div>

      <InventoryDialog storeId={storeId} listIds={ids} open={inventory} onOpenChange={setInventory} />

      <ZeroStockOrderDialog
        storeId={storeId}
        items={orderItems}
        open={ordering}
        onOpenChange={setOrdering}
        orderPath={`/stores/${storeId}/data-entry/export/order`}
        initialQuantity={(item) => String(Math.min(99, Math.max(1, Math.round(listQuantity.get(item.itm8) ?? 1))))}
        onSaved={async (fileName, orderLines) => {
          const { file } = await createFile.mutateAsync({ kind: 'ORDER', listIds: ids, lines: orderLines })
          await markLoaded(storeId, file.id, fileName)
        }}
        onPrepare={async (orderLines) => {
          await createFile.mutateAsync({ kind: 'ORDER', listIds: ids, lines: orderLines })
          toast.success('Zamówienie czeka w historii', {
            description: 'Plik zrobisz w Kolektory → Zamówienia.',
            action: { label: 'Otwórz', onClick: () => navigate(collectorPaths.orders) },
          })
        }}
        description="Ilości z listy jako liczba kartonów. Odznacz, czego nie zamawiasz, i popraw ilości. Plik trafi do folderu protokołów."
      />

      <AlertDialog open={repeating !== null} onOpenChange={(open) => !open && setRepeating(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {repeating && repeating in OUTPUT_LABELS
                ? `Utworzyć jeszcze raz: ${protocolLabel(repeating).toLowerCase()}?`
                : 'Utworzyć ten protokół jeszcze raz?'}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {repeating === 'ORDER'
                ? 'Z tej listy jest już zamówienie. Program sklepu zamówi te artykuły drugi raz.'
                : repeating === 'INVENTORY'
                  ? 'Z tej listy jest już inwentaryzacja. Nowa trafi do historii jako kolejna.'
                  : repeating === 'SUPPLIER_RETURN'
                    ? 'Z tej listy jest już zwrot do dostawcy. Powstanie drugi zwrot z tymi artykułami.'
                    : `Z tej listy jest już protokół ${repeating ? protocolLabel(repeating) : ''}. Program sklepu wczyta ilości drugi raz.`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Anuluj</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (repeating === 'ORDER') setOrdering(true)
                else if (repeating === 'INVENTORY') setInventory(true)
                else if (repeating === 'SUPPLIER_RETURN') createReturn()
                else if (repeating) void save(repeating as ProtocolKind)
                setRepeating(null)
              }}
            >
              Utwórz jeszcze raz
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {missingFolder && (
        <div className="grid gap-3 rounded-lg bg-amber-200 p-3 text-sm text-amber-950 dark:bg-amber-900/60 dark:text-amber-100">
          <p>Agent sklepu nie działa, a ta przeglądarka nie ma jeszcze folderu protokołów. Wybierz go raz.</p>
          <div className="rounded-lg bg-card p-3 text-foreground">
            <SavedFolder folder={folder} onPicked={() => void save(missingFolder)} />
          </div>
          <div>
            <Button variant="outline" size="sm" onClick={() => void download(missingFolder)}>
              Pobierz plik zamiast zapisu
            </Button>
          </div>
        </div>
      )}

      {done.length > 0 && (
        <ul className="grid gap-1 border-t pt-3 text-sm">
          {done.map(({ list, protocol }) => (
            <li key={protocol.id} className="flex flex-wrap items-center justify-between gap-2">
              <span className="min-w-0 text-muted-foreground">
                <span className="font-medium text-foreground">{protocolLabel(protocol.kind)}</span>
                {lists.length > 1 && ` (${list.collectorName})`}, {dateTime.format(new Date(protocol.createdAt))}
                {protocol.createdBy && `, ${protocol.createdBy}`}
              </span>
              <Button
                variant="ghost"
                size="sm"
                disabled={undo.isPending}
                title="Lista znów będzie czekać na protokół; pliku z programu sklepu to nie usuwa"
                onClick={() =>
                  undo.mutate(
                    { listId: list.id, protocolId: protocol.id },
                    {
                      onSuccess: () => toast.success('Lista znów czeka na protokół'),
                      onError: (error) => toast.error(errorMessage(error, 'Nie udało się cofnąć oznaczenia.')),
                    },
                  )
                }
              >
                <Undo2Icon aria-hidden />
                Cofnij oznaczenie
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function Rows({ details }: { details: CollectorListDetails }) {
  const [shown, setShown] = useState(PAGE)
  const more = useLoadMore<HTMLDivElement>(
    () => setShown((count) => count + PAGE),
    shown < details.rows.length,
    Math.min(shown, details.rows.length),
  )
  return (
    <Card className="gap-0 overflow-hidden p-0">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>ITM8</TableHead>
            <TableHead>EAN</TableHead>
            <TableHead>Nazwa</TableHead>
            <TableHead>Marka</TableHead>
            <TableHead className="text-right">Zakup netto</TableHead>
            <TableHead className="text-right">Zakup × ilość</TableHead>
            <TableHead className="text-right">Sprzedaż szt.</TableHead>
            <TableHead className="text-right">Sprz. × ilość</TableHead>
            <TableHead className="text-right">Marża %</TableHead>
            <TableHead className="text-right">Ilość</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {details.rows.slice(0, shown).map((row) => (
            <TableRow key={row.itm8}>
              <TableCell className="tabular-nums">{row.itm8.replace(/^0+/, '')}</TableCell>
              <TableCell className="tabular-nums">{row.ean}</TableCell>
              <TableCell className="min-w-56 whitespace-normal">
                <span className="flex items-center gap-1.5">
                  <span className="font-medium">{row.name}</span>
                  {row.known ? (
                    <ArticleStatsButton article={{ itm8: row.itm8, name: row.name }} />
                  ) : (
                    <Badge className="bg-rose-200 text-rose-950 dark:bg-rose-900/70 dark:text-rose-100">
                      spoza kartoteki
                    </Badge>
                  )}
                </span>
                {row.scans.length > 0 && (
                  <span className="block text-xs text-muted-foreground tabular-nums">
                    {row.scans
                      .map((scan) =>
                        scan.entered === scan.quantity
                          ? `${quantity.format(scan.entered)} × ${scan.code}`
                          : `${quantity.format(scan.entered)} szt. ${scan.code} = ${quantity.format(scan.quantity)}`,
                      )
                      .join(', ')}
                  </span>
                )}
              </TableCell>
              <TableCell>{row.brand}</TableCell>
              <TableCell className="text-right tabular-nums">{amount(row.purchasePrice)}</TableCell>
              <TableCell className="text-right tabular-nums">{amount(row.purchaseValue)}</TableCell>
              <TableCell className="text-right tabular-nums">{amount(row.salePrice)}</TableCell>
              <TableCell className="text-right tabular-nums">{amount(row.saleValue)}</TableCell>
              <TableCell
                className={cn(
                  'text-right tabular-nums',
                  row.marginPercent != null && row.marginPercent < 0 && 'font-medium text-red-700 dark:text-red-300',
                )}
              >
                {row.marginPercent == null ? '–' : `${money.format(row.marginPercent)}%`}
              </TableCell>
              <TableCell className="text-right text-base font-semibold tabular-nums">
                {quantity.format(row.quantity)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
        <TableFooter>
          <TableRow>
            <TableCell colSpan={5}>
              Razem: {details.rows.length} {details.rows.length === 1 ? 'artykuł' : 'artykułów'}
            </TableCell>
            <TableCell className="text-right tabular-nums">{amount(details.totalPurchaseValue)}</TableCell>
            <TableCell />
            <TableCell className="text-right tabular-nums">{amount(details.totalSaleValue)}</TableCell>
            <TableCell />
            <TableCell className="text-right tabular-nums">{quantity.format(details.totalQuantity)}</TableCell>
          </TableRow>
        </TableFooter>
      </Table>
      <div ref={more} />
    </Card>
  )
}

function amount(value: number | null) {
  return value == null ? '–' : money.format(value)
}
