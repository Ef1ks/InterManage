import { useDeferredValue, useState } from 'react'
import {
  ArrowRightLeftIcon,
  BarcodeIcon,
  CheckCircle2Icon,
  ChevronRightIcon,
  DownloadIcon,
  FileSpreadsheetIcon,
  FolderInputIcon,
  PencilIcon,
} from 'lucide-react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { FullPageMessage } from '@/components/FullPageMessage'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { PROTO_FOLDER } from '@/features/deli-order/api'
import {
  incomingTransferExportPath,
  useIncomingTransfer,
  useIncomingTransfers,
  useMarkTransferEntered,
} from '@/features/documents/api'
import { BackToTypes } from '@/features/documents/DocumentsList'
import { ReceiveTransferDialog, receivedQuantities } from '@/features/documents/ReceiveTransferDialog'
import {
  countFormat,
  documentPaths,
  formatDocumentDate,
  formatMoney,
  formatPrice,
  formatQuantity,
} from '@/features/documents/format'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError, downloadFile } from '@/lib/api'
import { folderSavingSupported, saveApiFileToFolder, useSavedFolder } from '@/lib/saved-folders'
import { matchesSearch } from '@/lib/search'
import type { IncomingTransfer } from '@/lib/types'
import { cn } from '@/lib/utils'

const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })

/**
 * W sklepie wysyłającym ruch międzymagazynowy to wydanie, więc ceny i wartości zakupu mają minus. Sklep docelowy
 * wprowadza go jako przyjęcie, dlatego pokazujemy je bez znaku.
 */
const unsigned = (value: number | null) => (value == null ? null : Math.abs(value))

/** Baner nad kafelkami Dokumentów: ruchy międzymagazynowe innych sklepów czekające na wprowadzenie. */
export function IncomingTransfersNotice({ storeId }: { storeId: string }) {
  const { data } = useIncomingTransfers(storeId)
  if (!data || data.pending.length === 0) return null
  const shown = data.pending.slice(0, 5)

  return (
    <Card className="mb-6 gap-0 border-amber-300 py-0 dark:border-amber-500/40">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b px-4 py-3">
        <ArrowRightLeftIcon aria-hidden className="size-5 shrink-0 text-amber-700 dark:text-amber-400" />
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold">
            Do wprowadzenia u siebie: {countFormat.format(data.pending.length)}{' '}
            {data.pending.length === 1 ? 'ruch międzymagazynowy' : 'ruchy międzymagazynowe'}
          </h2>
          <p className="text-xs text-muted-foreground">Inne sklepy wystawiły je dla tego sklepu.</p>
        </div>
        <Button variant="outline" render={<Link to={documentPaths.incoming} />}>
          Wszystkie
        </Button>
      </div>
      <ul className="divide-y">
        {shown.map((transfer) => (
          <li key={transfer.id}>
            <TransferLink transfer={transfer} />
          </li>
        ))}
      </ul>
    </Card>
  )
}

function TransferLink({ transfer }: { transfer: IncomingTransfer }) {
  return (
    <Link
      to={documentPaths.incomingTransfer(transfer.id)}
      className="group flex items-center gap-3 px-4 py-2.5 text-sm tabular-nums transition-colors outline-none hover:bg-muted/60 focus-visible:bg-muted"
    >
      <span className="min-w-0 flex-1">
        <span className="font-medium">{transfer.number}</span>
        <span className="text-muted-foreground"> · od {storeLabel(transfer.fromStore)}</span>
      </span>
      <span className="hidden text-muted-foreground sm:inline">{formatDocumentDate(transfer.documentDate)}</span>
      <span className="hidden text-muted-foreground sm:inline">{countFormat.format(transfer.lineCount)} linii</span>
      <span className="font-medium">{formatMoney(Math.abs(transfer.purchaseValue))} zł</span>
      <ChevronRightIcon
        aria-hidden
        className="size-4 text-muted-foreground transition-transform duration-150 group-hover:translate-x-0.5"
      />
    </Link>
  )
}

/** Wszystkie ruchy międzymagazynowe od innych sklepów: czekające na wprowadzenie i ostatnio wprowadzone. */
export function IncomingTransfersPage({ storeId }: { storeId: string }) {
  const { data, isPending, isError, refetch } = useIncomingTransfers(storeId)

  return (
    <>
      <BackToTypes />
      <h2 className="mb-1 text-xl font-semibold tracking-tight">Od innych sklepów</h2>
      <p className="mb-4 max-w-2xl text-sm text-muted-foreground">
        Ruchy międzymagazynowe, które sklepy zaprzyjaźnione wystawiły dla tego sklepu. Wprowadź je u siebie i oznacz
        jako wprowadzone.
      </p>
      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać dokumentów"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : isPending ? (
        <div aria-hidden className="h-40 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : (
        <div className="grid gap-6">
          <TransferGroup
            title="Do wprowadzenia"
            transfers={data.pending}
            empty="Nic nie czeka na wprowadzenie."
          />
          {data.entered.length > 0 && <TransferGroup title="Ostatnio wprowadzone" transfers={data.entered} />}
        </div>
      )}
    </>
  )
}

function TransferGroup({ title, transfers, empty }: { title: string; transfers: IncomingTransfer[]; empty?: string }) {
  return (
    <section>
      <h3 className="mb-2 text-sm font-semibold">{title}</h3>
      <Card className="gap-0 py-0">
        {transfers.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">{empty}</p>
        ) : (
          <ul className="divide-y">
            {transfers.map((transfer) => (
              <li key={transfer.id}>
                <TransferLink transfer={transfer} />
              </li>
            ))}
          </ul>
        )}
      </Card>
    </section>
  )
}

/** Ruch międzymagazynowy innego sklepu z liniami, do przepisania u siebie, i oznaczenie „wprowadzony”. */
export function IncomingTransferDetail({ storeId }: { storeId: string }) {
  const params = useParams()
  const id = params.transferId ?? ''
  const { data: transfer, isPending, isError } = useIncomingTransfer(storeId, id)
  const mark = useMarkTransferEntered(storeId, id)
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const [query, setQuery] = useState('')
  const deferredQuery = useDeferredValue(query)
  const [receiving, setReceiving] = useState(false)

  const back = (
    <BackLink to={documentPaths.incoming}>
      Od innych sklepów
    </BackLink>
  )

  if (isError) {
    return (
      <>
        {back}
        <FullPageMessage
          title="Nie znaleziono dokumentu"
          description="Sklep wysyłający mógł cofnąć wskazanie tego sklepu."
          inline
        />
      </>
    )
  }
  if (isPending) {
    return (
      <>
        {back}
        <div aria-hidden className="h-64 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      </>
    )
  }

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')
  const setEntered = (entered: boolean) =>
    mark.mutate(entered, {
      onSuccess: () =>
        entered
          ? toast.success('Oznaczono jako wprowadzony')
          : toast.success('Cofnięto oznaczenie', {
              action: { label: 'Przywróć', onClick: () => mark.mutate(true, { onError }) },
            }),
      onError,
    })

  const received = receivedQuantities(transfer)
  const lines = transfer.lines.filter((line) =>
    matchesSearch([line.name, line.brand, line.ean, line.itm8], deferredQuery),
  )

  return (
    <>
      {back}
      <div className="mb-4 flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div>
          <p className="text-sm text-muted-foreground">Ruch międzymagazynowy od {storeLabel(transfer.fromStore)}</p>
          <h2 className="text-xl font-semibold tracking-tight tabular-nums">{transfer.number}</h2>
          <p className="text-sm text-muted-foreground tabular-nums">
            {formatDocumentDate(transfer.documentDate)} · {countFormat.format(transfer.lineCount)} linii · zakup{' '}
            {formatMoney(Math.abs(transfer.purchaseValue))} zł · sprzedaż brutto {formatMoney(transfer.saleValueGross)} zł
          </p>
        </div>
      </div>

      <div
        className={cn(
          'mb-6 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border px-4 py-3',
          transfer.enteredAt
            ? 'border-emerald-300/70 bg-emerald-50/60 dark:border-emerald-500/30 dark:bg-emerald-500/5'
            : 'border-amber-300 bg-amber-50/60 dark:border-amber-500/40 dark:bg-amber-500/5',
        )}
      >
        {transfer.enteredAt ? (
          <CheckCircle2Icon aria-hidden className="size-5 shrink-0 text-emerald-700 dark:text-emerald-400" />
        ) : (
          <ArrowRightLeftIcon aria-hidden className="size-5 shrink-0 text-amber-700 dark:text-amber-400" />
        )}
        <div className="min-w-0 flex-1 text-sm">
          <p className="font-semibold">
            {transfer.enteredAt ? 'Wprowadzony w tym sklepie' : 'Wprowadź ten dokument u siebie'}
          </p>
          <p className="text-muted-foreground tabular-nums">
            {transfer.enteredAt
              ? `${dateFormat.format(new Date(transfer.enteredAt))}${transfer.enteredBy ? ` · ${transfer.enteredBy}` : ''}`
              : `Wskazał(a) ${transfer.createdBy ?? 'sklep wysyłający'}, ${dateFormat.format(new Date(transfer.createdAt))}`}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {canEdit &&
            (transfer.enteredAt ? (
              <>
                <Button variant="ghost" loading={mark.isPending} onClick={() => setEntered(false)}>
                  Cofnij
                </Button>
                <Button variant="outline" onClick={() => setReceiving(true)}>
                  <PencilIcon />
                  Popraw przyjęte ilości
                </Button>
              </>
            ) : (
              <Button onClick={() => setReceiving(true)}>
                <CheckCircle2Icon />
                Wpisz przyjęte ilości
              </Button>
            ))}
          <TransferDownloads storeId={storeId} transferId={transfer.id} />
        </div>
      </div>
      {canEdit && (
        <ReceiveTransferDialog storeId={storeId} transfer={transfer} open={receiving} onOpenChange={setReceiving} />
      )}

      <SearchInput
        className="mb-3 w-full sm:w-96"
        value={query}
        onChange={setQuery}
        placeholder="Szukaj: nazwa, marka, EAN, ITM8"
      />
      <Card className="overflow-hidden py-0">
        <div className="overflow-x-auto">
          <Table className="tabular-nums">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="pl-4">Lp.</TableHead>
                <TableHead>Towar</TableHead>
                <TableHead>EAN</TableHead>
                <TableHead>ITM8</TableHead>
                <TableHead className="text-right">Ilość</TableHead>
                <TableHead className="text-right">Przyjęto</TableHead>
                <TableHead className="text-right">Cena zakupu</TableHead>
                <TableHead className="text-right">Wartość zakupu</TableHead>
                <TableHead className="pr-4 text-right">Cena sprzedaży</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {lines.length === 0 ? (
                <TableRow className="hover:bg-transparent">
                  <TableCell colSpan={9} className="py-6 text-center text-muted-foreground">
                    Brak linii pasujących do wyszukiwania.
                  </TableCell>
                </TableRow>
              ) : (
                lines.map((line) => (
                  <TableRow key={line.id}>
                    <TableCell className="pl-4 text-muted-foreground">{line.lineNumber}</TableCell>
                    <TableCell className="max-w-80 whitespace-normal">
                      {line.name ?? '–'}
                      {line.brand && <span className="block text-xs text-muted-foreground">{line.brand}</span>}
                    </TableCell>
                    <TableCell>{line.ean ?? '–'}</TableCell>
                    <TableCell className="text-muted-foreground">{line.itm8}</TableCell>
                    <TableCell className="text-right">{formatQuantity(Math.abs(line.quantity))}</TableCell>
                    <ReceivedCell sent={Math.abs(line.quantity)} received={received.get(line.id)} />

                    <TableCell className="text-right">{formatOptional(unsigned(line.purchasePrice), formatPrice)}</TableCell>
                    <TableCell className="text-right">{formatOptional(unsigned(line.purchaseValue), formatMoney)}</TableCell>
                    <TableCell className="pr-4 text-right">{formatOptional(line.salePriceDocument, formatPrice)}</TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      </Card>
    </>
  )
}

/** Przyjęta ilość; różna od dokumentu na bursztynowo, z różnicą pod spodem. */
function ReceivedCell({ sent, received }: { sent: number; received: number | undefined }) {
  const value = received ?? sent
  const difference = value - sent
  return (
    <TableCell className={cn('text-right font-medium', difference !== 0 && 'bg-amber-50/70 dark:bg-amber-500/10')}>
      {formatQuantity(value)}
      {difference !== 0 && (
        <span className="block text-xs font-normal text-amber-800 dark:text-amber-300">
          {difference > 0 ? '+' : '−'}
          {formatQuantity(Math.abs(difference))}
        </span>
      )}
    </TableCell>
  )
}

const DOWNLOADS = [
  { format: 'proto', label: 'Plik PROTO6', hint: 'EAN z przyjętą ilością dla programu sklepu', icon: BarcodeIcon },
  { format: 'xlsx', label: 'Excel', hint: 'Ilość w dokumencie, przyjęta i różnica', icon: FileSpreadsheetIcon },
] as const

/** Pobranie pliku przyjęcia w przyjętych ilościach albo zapis PROTO6 w folderze programu sklepu. */
function TransferDownloads({ storeId, transferId }: { storeId: string; transferId: string }) {
  const [pending, setPending] = useState(false)
  const folder = useSavedFolder(PROTO_FOLDER.key)

  const run = async (action: () => Promise<void>) => {
    setPending(true)
    try {
      await action()
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać pliku')
    } finally {
      setPending(false)
    }
  }

  const download = (format: 'proto' | 'xlsx') =>
    run(() =>
      downloadFile(
        incomingTransferExportPath(storeId, transferId, format),
        format === 'proto' ? 'przyjecie.Traite0' : 'przyjecie.xlsx',
      ),
    )

  // Bez wybranego folderu najpierw okno wyboru (zapamiętane na kolejne razy), potem zapis.
  const saveToFolder = () =>
    run(async () => {
      if (!folder.name) {
        if (PROTO_FOLDER.suggestedPath) navigator.clipboard?.writeText(PROTO_FOLDER.suggestedPath).catch(() => {})
        if (!(await folder.pick())) return
      }
      const path = incomingTransferExportPath(storeId, transferId, 'proto')
      toast.success(`Zapisano ${await saveApiFileToFolder(PROTO_FOLDER.key, path, 'przyjecie.Traite0')}`)
    })

  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="outline" loading={pending} />}>
        <DownloadIcon />
        Pobierz
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        {DOWNLOADS.map(({ format, label, hint, icon: Icon }) => (
          <DropdownMenuItem key={format} onClick={() => download(format)} className="items-start gap-2.5 py-2">
            <Icon className="mt-0.5" />
            <span className="grid">
              <span>{label}</span>
              <span className="text-xs text-muted-foreground">{hint}</span>
            </span>
          </DropdownMenuItem>
        ))}
        {folderSavingSupported && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={saveToFolder} className="items-start gap-2.5 py-2">
              <FolderInputIcon className="mt-0.5" />
              <span className="grid">
                <span>{folder.name ? `Zapisz PROTO6 w ${folder.name}` : 'Zapisz PROTO6 w folderze…'}</span>
                <span className="text-xs text-muted-foreground">
                  {folder.name
                    ? 'Folder zapamiętany w przeglądarce'
                    : `Wybierz folder raz, np. ${PROTO_FOLDER.suggestedPath} (ścieżka w schowku)`}
                </span>
              </span>
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

const formatOptional =(value: number | null, format: (value: number) => string) => (value == null ? '–' : format(value))
