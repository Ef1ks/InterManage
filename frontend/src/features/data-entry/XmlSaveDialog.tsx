import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { FileCodeIcon, RotateCcwIcon } from 'lucide-react'
import { toast } from 'sonner'

import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ApiError, api, downloadFile, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProductFileFolder, useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, type SavedFolderSpec, saveApiFileToFolder, saveToFolder } from '@/lib/saved-folders'

import { formatMoney } from '@/features/documents/format'
import { formatDateTime } from '@/features/ksef/format'

import { type ExportPayload, type PriceControlPayload, type ProductFilePayload, usePriceControlFiles } from './api'
import { differs, type InvoiceTotals, signedMoney } from './invoice-totals'
import { savePriceControlFile } from './price-control'
import type { DataEntryRow } from './rows'

/** Pliki XML faktur mają własny folder, wybrany raz w przeglądarce. */
const XML_FOLDER: SavedFolderSpec = { key: 'data-entry-xml', label: 'Plik XML faktury' }

export type XmlProblems = {
  invalidEan: DataEntryRow[]
  tooLong: DataEntryRow[]
  noSubfamily: DataEntryRow[]
}

type Props = {
  storeId: string
  payload: ExportPayload | null
  /** Nowe i zmienione artykuły do pliku CADBEZP; {@code null}, gdy takich nie ma. */
  productFile: ProductFilePayload | null
  /** Artykuły z kartoteki ze zmienioną ceną sprzedaży do pliku CTRLPRIX; {@code null}, gdy takich nie ma. */
  priceControl: PriceControlPayload | null
  problems: XmlProblems
  /** Sumy tabeli i faktury: różnica wymaga potwierdzenia zapisu, ale go nie blokuje. */
  totals: InvoiceTotals
  /**
   * Ten sam EAN w kilku wierszach z różnymi cenami. Program sklepu łączy pozycje pliku z jednym EAN-em: sumuje ilości
   * i bierze cenę pierwszej (21 × 13,29 i 9 × 0,03 przyjął jako 30 × 13,29), więc przed zapisem trzeba je połączyć.
   */
  priceConflicts: DataEntryRow[][]
  onMergeDuplicates: () => void
  open: boolean
  onOpenChange: (open: boolean) => void
  onGoTo: (row: DataEntryRow, key: 'ean' | 'name' | 'subfamily') => void
}

/**
 * Zapis tabeli do pliku XML faktury dla programu sklepu. Plik trafia do folderu wybranego raz w przeglądarce; bez
 * folderu (albo w przeglądarce bez tej funkcji) się pobiera. Błędny EAN nowego artykułu blokuje zapis; pozostałe braki
 * tabeli i różnica sum z fakturą są nad przyciskiem, ale nie blokują zapisu: przycisk pyta wtedy „Zapisz mimo …”. Nowe i zmienione artykuły trafiają po zapisie XML do
 * pliku CADBEZP (.xls) w osobnym folderze (ścieżka z ustawień sklepu), a zmienione ceny sprzedaży do pliku CTRLPRIX w
 * folderze plików PROTO; ich błąd nie cofa zapisanego pliku XML. Na końcu jeden komunikat z listą zapisanych plików.
 */
export function XmlSaveDialog({
  storeId,
  payload,
  productFile,
  priceControl,
  problems,
  totals,
  priceConflicts,
  onMergeDuplicates,
  open,
  onOpenChange,
  onGoTo,
}: Props) {
  const [saving, setSaving] = useState(false)
  const queryClient = useQueryClient()
  const productFolder = useProductFileFolder(storeId)
  const protoFolder = useProtoFolder(storeId)
  const priceControlFiles = usePriceControlFiles(storeId, open)
  const count = payload?.rows.length ?? 0

  const save = async () => {
    if (!payload) return
    setSaving(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(XML_FOLDER.key)
      const productFolderReady = productFile ? await ensureFolderAccess(productFolder.key) : false
      const protoFolderReady = priceControl ? await ensureFolderAccess(protoFolder.key) : false
      const { fileName, blob, headers } = await fetchPostedFile(
        `/stores/${storeId}/data-entry/export/xml`,
        payload,
        'faktura.xml',
      )
      // Zapis na dysku przed historią: nieudany zapis kończy się tutaj i plik nie trafia do historii.
      if (folderReady) await saveToFolder(XML_FOLDER.key, fileName, blob)
      else saveBlob(blob, fileName)
      const saved: SavedFile[] = [{ fileName, downloaded: !folderReady }]
      const exportId = headers.get('X-Xml-Export-Id')
      let inHistory = false
      if (exportId) {
        try {
          await api(`/stores/${storeId}/data-entry/xml-history/${exportId}/saved`, { method: 'POST', json: payload })
          inHistory = true
          void queryClient.invalidateQueries({ queryKey: ['stores', storeId, 'data-entry', 'xml-history'] })
        } catch {
          // Plik jest już na dysku; brak wpisu w historii nie cofa zapisu.
        }
      }
      if (!inHistory) toast.warning(`Plik ${fileName} nie trafił do historii`)
      if (productFile) saved.push(...(await saveProductFile(productFile, productFolderReady)))
      let supplierCode: string | null = null
      if (priceControl) {
        const result = await savePriceControl(priceControl, protoFolderReady)
        if (result) {
          saved.push(result.file)
          supplierCode = result.supplierCode
        }
      }
      toast.success(`Zapisano pliki${supplierCode ? ` dla dostawcy ${supplierCode}` : ''}`, {
        description: (
          <span className="grid gap-0.5">
            {saved.map((file) => (
              <span key={file.fileName} className="break-all">
                {file.fileName}
                {file.downloaded ? ' (pobrany: folder nie jest wybrany w tej przeglądarce)' : ''}
              </span>
            ))}
          </span>
        ),
        duration: 10_000,
      })
      onOpenChange(false)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku XML', {
        description: 'Plik nie trafił do historii.',
      })
    } finally {
      setSaving(false)
    }
  }

  /** Plik CADBEZP po zapisanym pliku XML: błąd tylko ostrzega, plik XML zostaje. */
  const saveProductFile = async (file: ProductFilePayload, folderReady: boolean): Promise<SavedFile[]> => {
    try {
      const { fileName, blob } = await fetchPostedFile(
        `/stores/${storeId}/data-entry/export/product-file`,
        file,
        'CADBEZP.xls',
      )
      if (folderReady) await saveToFolder(productFolder.key, fileName, blob)
      else saveBlob(blob, fileName)
      return [{ fileName, downloaded: !folderReady }]
    } catch (error) {
      toast.error('Nie udało się zapisać pliku nowych artykułów', {
        description: `${error instanceof ApiError ? `${error.message} ` : ''}Plik XML jest zapisany.`,
      })
      return []
    }
  }

  /** Plik CTRLPRIX (zmienione ceny) w folderze plików PROTO: błąd tylko ostrzega, plik XML zostaje. */
  const savePriceControl = async (file: PriceControlPayload, folderReady: boolean) => {
    try {
      const { fileName, downloaded, supplierCode } = await savePriceControlFile(storeId, file, protoFolder.key, folderReady)
      void queryClient.invalidateQueries({ queryKey: ['stores', storeId, 'data-entry', 'price-control-files'] })
      return { file: { fileName, downloaded }, supplierCode }
    } catch (error) {
      toast.error('Nie udało się zapisać pliku zmian cen (CTRLPRIX)', {
        description: `${error instanceof ApiError ? `${error.message} ` : ''}Plik XML jest zapisany.`,
      })
      return null
    }
  }

  /** Ponowny zapis pliku CTRLPRIX z historii w folderze plików PROTO (bez folderu: pobranie). */
  const saveAgain = async (id: string) => {
    const path = `/stores/${storeId}/data-entry/price-control-files/${id}/file`
    try {
      if (await ensureFolderAccess(protoFolder.key)) {
        const fileName = await saveApiFileToFolder(protoFolder.key, path, 'CTRLPRIX.traite0')
        toast.success(`Zapisano ${fileName}`)
      } else {
        await downloadFile(path, 'CTRLPRIX.traite0')
      }
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku CTRLPRIX')
    }
  }

  // Błędny EAN blokuje zapis: program sklepu założyłby artykuł z kodem, którego nie ma na towarze.
  const invalidEan = problems.invalidEan.length > 0
  const issues = [
    { rows: problems.tooLong, text: 'Nazwa dłuższa niż 30 znaków', key: 'name' as const, show: 'Pokaż pierwszą' },
    { rows: problems.noSubfamily, text: 'Nowy artykuł bez podrodziny', key: 'subfamily' as const, show: 'Pokaż pierwszy' },
  ].filter((issue) => issue.rows.length > 0)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Zapisz plik XML</DialogTitle>
          <DialogDescription>
            Pozycje z tabeli: {count} (bez kaucji i dostawy). Artykuły bez EAN-u trafią do pliku z kodem 0000000000000.
          </DialogDescription>
        </DialogHeader>

        {totals.mismatch && (
          <div
            role="alert"
            className="rounded-xl border border-red-400 bg-red-200 px-4 py-3 text-sm text-red-950 dark:border-red-500/50 dark:bg-red-950/70 dark:text-red-50"
          >
            <p className="font-medium">Suma tabeli nie zgadza się z fakturą</p>
            <p className="mt-1">
              Sprawdź ilości i ceny: program sklepu nie przyjmie dokumentu z błędną ilością. Możesz zapisać mimo to.
            </p>
            <dl className="mt-2 grid grid-cols-[auto_1fr_1fr_1fr] gap-x-4 gap-y-0.5 tabular-nums">
              <span />
              <dt className="text-right text-xs">Tabela</dt>
              <dt className="text-right text-xs">Faktura</dt>
              <dt className="text-right text-xs">Różnica</dt>
              <SumRow label="Netto" value={totals.net} document={totals.invoiceNet} diff={totals.netDiff} />
              <SumRow label="VAT" value={totals.vat} document={totals.invoiceVat} diff={totals.vatDiff} />
            </dl>
          </div>
        )}

        {invalidEan && (
          <div
            role="alert"
            className="rounded-xl border border-red-400 bg-red-200 px-4 py-3 text-sm text-red-950 dark:border-red-500/50 dark:bg-red-950/70 dark:text-red-50"
          >
            <p className="font-medium">Błędny EAN nowego artykułu: {problems.invalidEan.length}</p>
            <p className="mt-1">
              Zła długość albo cyfra kontrolna. Popraw EAN z towaru (albo wyczyść komórkę, gdy towar nie ma kodu), dopiero
              wtedy plik da się zapisać.{' '}
              <button
                type="button"
                className="font-medium underline underline-offset-3"
                onClick={() => onGoTo(problems.invalidEan[0], 'ean')}
              >
                Pokaż pierwszy
              </button>
            </p>
          </div>
        )}

        {priceConflicts.length > 0 && (
          <div
            role="alert"
            className="rounded-xl border border-red-400 bg-red-200 px-4 py-3 text-sm text-red-950 dark:border-red-500/50 dark:bg-red-950/70 dark:text-red-50"
          >
            <p className="font-medium">Ten sam artykuł w kilku cenach</p>
            <p className="mt-1">
              Program sklepu łączy pozycje z tym samym EAN-em: sumuje ilości i bierze cenę z pierwszej. Połącz je w
              jeden wiersz ze średnią ceną, inaczej przyjęcie będzie miało złą wartość.
            </p>
            <ul className="mt-2 space-y-1">
              {priceConflicts.map((group) => (
                <li key={group[0].id} className="tabular-nums">
                  <span className="font-medium">{group[0].name || group[0].ean}</span>:{' '}
                  {group.map((row) => `${formatQuantity(row.quantity)} × ${formatPrice(row.unitNet)}`).join(', ')}
                </li>
              ))}
            </ul>
            <Button variant="outline" size="sm" className="mt-3" onClick={onMergeDuplicates}>
              Połącz w jeden wiersz
            </Button>
          </div>
        )}

        {issues.length > 0 && (
          <div className="rounded-xl border border-amber-300 bg-amber-100 px-4 py-3 text-sm text-amber-950 dark:border-amber-500/40 dark:bg-amber-950/60 dark:text-amber-100">
            <p className="font-medium">Tabela ma braki</p>
            <ul className="mt-1.5 space-y-1.5">
              {issues.map((issue) => (
                <li key={issue.key}>
                  {issue.text}: {issue.rows.length}.{' '}
                  <button
                    type="button"
                    className="font-medium underline underline-offset-3"
                    onClick={() => onGoTo(issue.rows[0], issue.key)}
                  >
                    {issue.show}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        )}

        <SavedFolder folder={XML_FOLDER} />

        {productFile && (
          <div className="grid gap-2">
            <p className="text-sm">
              Nowe i zmienione artykuły ({productFile.rows.length}) trafią też do pliku CADBEZP dla programu sklepu.
            </p>
            <SavedFolder folder={productFolder} />
          </div>
        )}

        {priceControl && (
          <div className="grid gap-2">
            <p className="text-sm">
              Zmienione ceny sprzedaży ({priceControl.rows.length}) trafią do pliku CTRLPRIX w folderze plików PROTO.
            </p>
            <SavedFolder folder={protoFolder} />
          </div>
        )}

        <Button size="xl" loading={saving} disabled={count === 0 || invalidEan} onClick={save}>
          <FileCodeIcon />
          {invalidEan
            ? 'Popraw błędne EAN-y'
            : totals.mismatch
              ? 'Zapisz mimo różnicy'
              : issues.length > 0 || priceConflicts.length > 0
                ? 'Zapisz mimo to'
                : 'Zapisz plik XML'}
        </Button>

        {priceControlFiles.data && priceControlFiles.data.length > 0 && (
          <details className="text-sm">
            <summary className="cursor-pointer text-muted-foreground">
              Ostatnie pliki CTRLPRIX ({priceControlFiles.data.length})
            </summary>
            <ul className="mt-2 divide-y rounded-lg border">
              {priceControlFiles.data.map((file) => (
                <li key={file.id} className="flex items-center gap-3 px-3 py-2">
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium">
                      {file.supplierName ?? file.supplierCode ?? 'Dostawca'}
                      {file.invoiceNumber ? ` · ${file.invoiceNumber}` : ''}
                    </p>
                    <p className="truncate text-xs text-muted-foreground">
                      {formatDateTime(file.createdAt)} · cen: {file.lineCount}
                      {file.createdBy ? ` · ${file.createdBy}` : ''}
                    </p>
                  </div>
                  <Button variant="outline" size="sm" onClick={() => saveAgain(file.id)} title={file.fileName}>
                    <RotateCcwIcon />
                    Zapisz ponownie
                  </Button>
                </li>
              ))}
            </ul>
          </details>
        )}
      </DialogContent>
    </Dialog>
  )
}

type SavedFile = { fileName: string; downloaded: boolean }

function formatQuantity(quantity: number | null) {
  return quantity === null ? '–' : quantity.toLocaleString('pl-PL', { maximumFractionDigits: 3 })
}

function formatPrice(price: number | null) {
  return price === null ? '–' : `${formatMoney(price)} zł`
}

function SumRow({
  label,
  value,
  document,
  diff,
}: {
  label: string
  value: number
  document: number | null
  diff: number | null
}) {
  return (
    <>
      <dt>{label}</dt>
      <dd className="text-right">{formatMoney(value)} zł</dd>
      <dd className="text-right">{document === null ? '–' : `${formatMoney(document)} zł`}</dd>
      <dd className={differs(diff) ? 'text-right font-semibold' : 'text-right'}>
        {diff === null ? '–' : `${signedMoney(diff)} zł`}
      </dd>
    </>
  )
}
