import { useMemo, useState } from 'react'
import { FileSpreadsheetIcon, LoaderCircleIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FormActions } from '@/components/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { usePurchaseFileLines } from '@/features/deli-prices/api'
import { formatMoney } from '@/features/deli-prices/format'
import { ApiError, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProductFileFolder } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import type { PurchaseFileLine } from '@/lib/types'

/** Plik CADBEZP, który zmienia w kartotece tylko cenę zakupu: na taką, jaka wychodzi z przepisu. */
export function PurchaseFileButton({ storeId }: { storeId: string }) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <FileSpreadsheetIcon />
        Ceny zakupu do kartoteki
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-4 p-6 sm:max-w-3xl">
          {open && <FileForm storeId={storeId} onClose={() => setOpen(false)} />}
        </DialogContent>
      </Dialog>
    </>
  )
}

/** Na start zaznaczone tylko zmienione ceny, którym można ufać. */
function checkedAtStart(line: PurchaseFileLine) {
  return warning(line) === null && line.purchasePrice !== line.currentPrice
}

function warning(line: PurchaseFileLine) {
  if (line.missingPriceCount > 0) return 'składnik bez ceny'
  if (line.countedInPieces) return 'na sztuki, koszt na 1 kg'
  return null
}

function FileForm({ storeId, onClose }: { storeId: string; onClose: () => void }) {
  const { data: lines = [], isPending, isError } = usePurchaseFileLines(storeId)
  const folder = useProductFileFolder(storeId)
  // Kalkulacje ze zmienionym zaznaczeniem względem startowego.
  const [changed, setChanged] = useState<Set<string>>(() => new Set())
  const [saving, setSaving] = useState(false)

  const isChecked = (line: PurchaseFileLine) => checkedAtStart(line) !== changed.has(line.calculationId)
  const selected = lines.filter(isChecked)
  const allChecked = lines.length > 0 && selected.length === lines.length
  // Plik przepisuje artykuł na dostawcę, więc każdy dostawca z kartoteki ma swój plik.
  const bySupplier = useMemo(() => {
    const groups = new Map<string, PurchaseFileLine[]>()
    selected.forEach((line) => {
      const key = line.supplier ?? ''
      groups.set(key, [...(groups.get(key) ?? []), line])
    })
    return [...groups.values()]
    // `selected` powstaje przy każdym renderze, a zależy tylko od tych dwóch wartości.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lines, changed])

  const toggle = (line: PurchaseFileLine) =>
    setChanged((current) => {
      const next = new Set(current)
      if (next.has(line.calculationId)) next.delete(line.calculationId)
      else next.add(line.calculationId)
      return next
    })
  const setAll = (checked: boolean) =>
    setChanged(new Set(lines.filter((line) => checkedAtStart(line) !== checked).map((line) => line.calculationId)))

  const save = async () => {
    setSaving(true)
    try {
      // Zgoda na folder zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(folder.key)
      const files: string[] = []
      for (const group of bySupplier) {
        const { fileName, blob } = await fetchPostedFile(
          `/stores/${storeId}/price-calculations/cadbezp`,
          { calculationIds: group.map((line) => line.calculationId) },
          'CADBEZP.xls',
        )
        if (folderReady) await saveToFolder(folder.key, fileName, blob)
        else saveBlob(blob, fileName)
        files.push(group[0]?.supplier ?? 'bez dostawcy')
      }
      onClose()
      toast.success(`Zapisano ceny zakupu: ${selected.length}`, {
        description: (
          <span className="grid gap-0.5">
            {files.map((file) => (
              <span key={file} className="break-all">
                Dostawca: {file}
              </span>
            ))}
            {!folderReady && <span>Pobrane: folder nie jest wybrany w tej przeglądarce.</span>}
          </span>
        ),
        duration: 10_000,
      })
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Ceny zakupu z przepisów do kartoteki</DialogTitle>
        <DialogDescription>
          Cena zakupu z przepisu, a pozostałe dane jak w kartotece, także cena sprzedaży i dostawca. Każdy dostawca
          zapisuje się osobno.
        </DialogDescription>
      </DialogHeader>

      {isPending ? (
        <p className="flex items-center justify-center gap-2 py-10 text-sm text-muted-foreground">
          <LoaderCircleIcon aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
          Wczytuję kalkulacje…
        </p>
      ) : isError ? (
        <p className="py-10 text-center text-sm text-destructive">Nie udało się wczytać kalkulacji</p>
      ) : lines.length === 0 ? (
        <p className="py-10 text-center text-sm text-muted-foreground">Żadna kalkulacja nie ma jeszcze przepisu.</p>
      ) : (
        <div className="max-h-[55vh] overflow-y-auto overscroll-contain rounded-lg border">
          <label className="sticky top-0 z-10 flex cursor-pointer items-center gap-3 border-b bg-popover px-3 py-2 text-sm font-medium">
            <input
              type="checkbox"
              checked={allChecked}
              onChange={(event) => setAll(event.target.checked)}
              className="size-4 cursor-pointer accent-foreground"
            />
            Zaznacz wszystkie ({lines.length})
          </label>
          <ul className="divide-y">
            {lines.map((line) => (
              <li key={line.calculationId}>
                <label className="flex cursor-pointer items-center gap-3 px-3 py-2 hover:bg-muted">
                  <input
                    type="checkbox"
                    checked={isChecked(line)}
                    onChange={() => toggle(line)}
                    className="size-4 shrink-0 cursor-pointer accent-foreground"
                  />
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-center gap-x-2 text-sm font-medium">
                      <span className="min-w-0 truncate">{line.name}</span>
                      {warning(line) && (
                        <Badge className="bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100">
                          {warning(line)}
                        </Badge>
                      )}
                    </span>
                    <span className="block truncate text-xs text-muted-foreground tabular-nums">
                      {[`EAN ${line.ean}`, line.supplier].filter(Boolean).join(' · ')}
                    </span>
                  </span>
                  <span className="shrink-0 text-right text-sm tabular-nums">
                    <span className="block font-medium">{formatMoney(line.purchasePrice)}</span>
                    <span className="block text-xs text-muted-foreground">teraz {formatMoney(line.currentPrice)}</span>
                  </span>
                </label>
              </li>
            ))}
          </ul>
        </div>
      )}

      <FormActions>
        <Button type="button" variant="outline" size="lg" onClick={onClose} disabled={saving}>
          Anuluj
        </Button>
        <Button size="lg" loading={saving} disabled={selected.length === 0} onClick={() => void save()}>
          Zapisz plik ({selected.length})
        </Button>
      </FormActions>
    </>
  )
}
