import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { FileCodeIcon } from 'lucide-react'
import { toast } from 'sonner'

import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ApiError, api, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProductFileFolder } from '@/lib/proto-files'
import { ensureFolderAccess, type SavedFolderSpec, saveToFolder } from '@/lib/saved-folders'

import { formatMoney } from '@/features/documents/format'

import type { ExportPayload, ProductFilePayload } from './api'
import { differs, type InvoiceTotals, signedMoney } from './invoice-totals'
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
  problems: XmlProblems
  /** Sumy tabeli i faktury: różnica wymaga potwierdzenia zapisu, ale go nie blokuje. */
  totals: InvoiceTotals
  open: boolean
  onOpenChange: (open: boolean) => void
  onGoTo: (row: DataEntryRow, key: 'ean' | 'name' | 'subfamily') => void
}

/**
 * Zapis tabeli do pliku XML faktury dla programu sklepu. Plik trafia do folderu wybranego raz w przeglądarce; bez
 * folderu (albo w przeglądarce bez tej funkcji) się pobiera. Braki tabeli i różnica sum z fakturą są nad przyciskiem,
 * ale nie blokują zapisu: przycisk pyta wtedy „Zapisz mimo …”. Nowe i zmienione artykuły trafiają po zapisie XML do
 * pliku CADBEZP (.xls) w osobnym folderze (ścieżka z ustawień sklepu); jego błąd nie cofa zapisanego pliku XML.
 */
export function XmlSaveDialog({ storeId, payload, productFile, problems, totals, open, onOpenChange, onGoTo }: Props) {
  const [saving, setSaving] = useState(false)
  const queryClient = useQueryClient()
  const productFolder = useProductFileFolder(storeId)
  const count = payload?.rows.length ?? 0

  const save = async () => {
    if (!payload) return
    setSaving(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(XML_FOLDER.key)
      const productFolderReady = productFile ? await ensureFolderAccess(productFolder.key) : false
      const { fileName, blob, headers } = await fetchPostedFile(
        `/stores/${storeId}/data-entry/export/xml`,
        payload,
        'faktura.xml',
      )
      // Zapis na dysku przed historią: nieudany zapis kończy się tutaj i plik nie trafia do historii.
      if (folderReady) await saveToFolder(XML_FOLDER.key, fileName, blob)
      else saveBlob(blob, fileName)
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
      if (!inHistory)
        toast.warning(`${folderReady ? 'Zapisano' : 'Pobrano'} ${fileName}, ale plik nie trafił do historii`)
      else if (folderReady) toast.success(`Zapisano ${fileName}`)
      else toast.success('Pobrano plik XML', { description: 'Folder na pliki XML nie jest wybrany w tej przeglądarce.' })
      if (productFile) await saveProductFile(productFile, productFolderReady)
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
  const saveProductFile = async (file: ProductFilePayload, folderReady: boolean) => {
    try {
      const { fileName, blob } = await fetchPostedFile(
        `/stores/${storeId}/data-entry/export/product-file`,
        file,
        'CADBEZP.xls',
      )
      if (folderReady) await saveToFolder(productFolder.key, fileName, blob)
      else saveBlob(blob, fileName)
      toast.success(`${folderReady ? 'Zapisano' : 'Pobrano'} ${fileName}`, {
        description: `Nowe i zmienione artykuły: ${file.rows.length}.`,
      })
    } catch (error) {
      toast.error('Nie udało się zapisać pliku nowych artykułów', {
        description: `${error instanceof ApiError ? `${error.message} ` : ''}Plik XML jest zapisany.`,
      })
    }
  }

  const issues = [
    {
      rows: problems.invalidEan,
      text: 'Błędny EAN nowego artykułu (zła długość albo cyfra kontrolna)',
      key: 'ean' as const,
      show: 'Pokaż pierwszy',
    },
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

        <Button size="xl" loading={saving} disabled={count === 0} onClick={save}>
          <FileCodeIcon />
          {totals.mismatch ? 'Zapisz mimo różnicy' : issues.length > 0 ? 'Zapisz mimo to' : 'Zapisz plik XML'}
        </Button>
      </DialogContent>
    </Dialog>
  )
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
