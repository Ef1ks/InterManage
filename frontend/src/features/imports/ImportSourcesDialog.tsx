import { useState } from 'react'
import { ClipboardCopyIcon, FolderIcon, FolderOpenIcon } from 'lucide-react'
import { toast } from 'sonner'

import { StoreLocationField } from '@/components/StoreLocationField'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DAILY_REPORTS,
  importFolderLocationKey,
  importLocationKey,
  isMacroReport,
  macroPathError,
  MASK_EXAMPLES,
  REPORT_EXTENSIONS,
  REPORT_TITLES,
  sourceFolderKey,
  type DailyReport,
  type MacroReport,
} from '@/features/imports/import-sources'
import {
  buildReportSaveMacro,
  lastDocumentDate,
  macroPlayerVersion,
  opensItself,
  pathExample,
  LIST_NUMBER_REPORTS,
  listNumberError,
  PRINT_NUMBER_REPORTS,
  printNumberError,
  type ReportMacroSettings,
} from '@/features/imports/report-macros'
import { useDocumentImports, useSalesImports } from '@/features/imports/api'
import { useImportSettings } from '@/features/imports/use-import-settings'
import { ApiError } from '@/lib/api'
import { folderSavingSupported, useSavedFolder } from '@/lib/saved-folders'
import { parentFolder, useStoreLocations } from '@/lib/store-locations'
import type { ImportGuide } from '@/lib/types'

/**
 * Ustawienia „Odśwież dane”: folder każdego codziennego raportu (ścieżka wspólna dla sklepu, uchwyt wybrany raz w tej
 * przeglądarce) i opcjonalna maska nazwy pliku, gdy w folderze leżą też inne pliki. Maski, ścieżki zapisu makr
 * i numery z Mercalys też są ścieżkami sklepu. Listy dostawców i nomenklatur wgrywa się tylko ręcznie.
 */
export function ImportSourcesDialog({
  open,
  onOpenChange,
  storeId,
  guides,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  storeId: string
  guides: ImportGuide[]
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle className="text-lg">Ustawienia importu</DialogTitle>
          <DialogDescription>
            Wskaż, gdzie zapisujesz raporty z systemu sklepu. „Odśwież dane” wgra z każdego folderu najnowszy plik,
            którego jeszcze nie było. Ścieżki, nazwy plików i numery z Mercalys są wspólne dla całego sklepu (na dysku
            sieciowym są takie same na każdym komputerze). Sam folder przeglądarka pamięta u siebie, więc na nowym
            komputerze wybierz go raz: ścieżkę skopiujemy do schowka.
          </DialogDescription>
        </DialogHeader>
        {folderSavingSupported ? (
          open && <SourceList storeId={storeId} guides={guides} />
        ) : (
          <p className="text-sm text-muted-foreground">
            Odczyt plików z folderu działa w Chrome i Edge. Tutaj raporty trzeba wgrywać ręcznie.
          </p>
        )}
        <div className="flex justify-end">
          <Button size="lg" onClick={() => onOpenChange(false)}>
            Gotowe
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}

function SourceList({ storeId, guides }: { storeId: string; guides: ImportGuide[] }) {
  // Przenosi ustawienia zapisane wcześniej tylko w tej przeglądarce do ścieżek sklepu.
  useImportSettings(storeId)
  return (
    <ul className="grid gap-3">
      {DAILY_REPORTS.map((kind) => (
        <SourceRow
          key={kind}
          storeId={storeId}
          kind={kind}
          guidePath={guides.find((guide) => guide.kind === kind)?.folderPath ?? null}
        />
      ))}
    </ul>
  )
}

function SourceRow({ storeId, kind, guidePath }: { storeId: string; kind: DailyReport; guidePath: string | null }) {
  const { name, loaded, pick } = useSavedFolder(sourceFolderKey(storeId, kind), 'read')
  const [picking, setPicking] = useState(false)
  const title = REPORT_TITLES[kind]
  const locations = useStoreLocations(storeId)
  const [mask, setMask] = useState('')
  const [macro, setMacro] = useState<ReportMacroSettings>({ path: '', printNumber: '', listNumber: '' })
  // Folder raportu: wpisany dla sklepu, bez niego folder pliku z makra, na końcu podpowiedź z instrukcji raportu.
  const fallbackPath = parentFolder(macro.path) ?? guidePath
  const suggestedPath = locations.data?.[importFolderLocationKey(kind)] || fallbackPath

  const choose = async () => {
    // Bez await: schowek nie zużywa gestu kliknięcia, którego potrzebuje okno wyboru folderu.
    if (suggestedPath) navigator.clipboard?.writeText(suggestedPath).catch(() => {})
    setPicking(true)
    try {
      const handle = await pick()
      if (handle) toast.success(`${title}: pliki z folderu ${handle.name}`)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się wybrać folderu')
    } finally {
      setPicking(false)
    }
  }

  return (
    <li className="grid gap-3 rounded-xl border bg-muted/40 p-3 sm:grid-cols-[minmax(0,1fr)_14rem] sm:items-start">
      <div className="flex min-w-0 items-center gap-3 sm:col-span-2">
        {name ? (
          <FolderIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        ) : (
          <FolderOpenIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
        )}
        <div className="min-w-0 flex-1 text-sm">
          <div className="font-medium">{title}</div>
          <div className="truncate text-xs text-muted-foreground">
            {!loaded ? (
              'Sprawdzanie folderu…'
            ) : name ? (
              <>
                Folder <span className="font-medium text-foreground">{name}</span> ·{' '}
                {REPORT_EXTENSIONS[kind].join(', ')}
              </>
            ) : suggestedPath ? (
              <>
                Ścieżkę <span className="font-mono">{suggestedPath}</span> skopiujemy do schowka: wklej ją w pasek
                adresu okna wyboru
              </>
            ) : (
              'Nie wybrano folderu w tej przeglądarce: raport wgrywasz ręcznie'
            )}
          </div>
        </div>
        <Button type="button" variant="outline" size="sm" loading={picking} disabled={!loaded} onClick={choose}>
          {name ? 'Zmień' : 'Wybierz folder'}
        </Button>
      </div>
      <StoreLocationField
        storeId={storeId}
        locationKey={importFolderLocationKey(kind)}
        label="Folder raportu w sklepie"
        className="font-mono"
        hint={fallbackPath ? `Bez wpisu: ${fallbackPath}` : 'Wspólny dla wszystkich komputerów sklepu'}
      />
      <StoreLocationField
        storeId={storeId}
        locationKey={importLocationKey(kind, 'mask')}
        label="Nazwa pliku (opcjonalnie)"
        maxLength={200}
        onDraftChange={setMask}
        hint={`Np. ${MASK_EXAMPLES[kind]} (* = dowolne znaki)`}
      />
      {isMacroReport(kind) && (
        <MacroFields
          storeId={storeId}
          kind={kind}
          settings={macro}
          mask={mask}
          onChange={(change) => setMacro((current) => ({ ...current, ...change }))}
        />
      )}
    </li>
  )
}

/** Ścieżka, pod którą makro zapisze raport z Mercalys, numer wydruku (Baza danych) i makro tylko dla tego raportu. */
function MacroFields({
  storeId,
  kind,
  settings,
  mask,
  onChange,
}: {
  storeId: string
  kind: MacroReport
  settings: ReportMacroSettings
  mask: string
  /** Bieżący tekst pól (zapisuje je samo pole, po wyjściu z niego). */
  onChange: (change: Partial<ReportMacroSettings>) => void
}) {
  const withPrintNumber = PRINT_NUMBER_REPORTS.includes(kind)
  const withListNumber = LIST_NUMBER_REPORTS.includes(kind)
  const pathError = macroPathError(kind, settings.path, mask)
  const numberError =
    (withPrintNumber ? printNumberError(settings.printNumber ?? '') : null) ??
    (withListNumber ? listNumberError(settings.listNumber ?? '') : null)
  // Okres raportu sprzedaży zależy od ostatniego dnia w bazie (ta sama lista co na stronie importu, z pamięci)
  const sales = useSalesImports(storeId)
  const documents = useDocumentImports(storeId)
  const waitsForSales = (kind === 'SALES' && sales.isPending) || (kind === 'DOCUMENTS' && documents.isPending)
  const ready = settings.path.trim() !== '' && pathError === null && numberError === null && !waitsForSales

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(
        buildReportSaveMacro([{ kind, settings }], {
          lastSaleDate: sales.data?.lastSaleDate,
          lastDocumentDate: lastDocumentDate(documents.data),
        }),
      )
      toast.success(`Skopiowano makro: ${REPORT_TITLES[kind]}`, {
        description: opensItself(kind)
          ? `Naciśnij Alt+E (odtwarzacz od wersji ${macroPlayerVersion([kind])}). Makro samo otworzy raport w Mercalys i zapisze plik.`
          : `Naciśnij Alt+E (odtwarzacz od wersji ${macroPlayerVersion([kind])}), otwórz raport w Mercalys i kliknij Excel.`,
      })
    } catch {
      toast.error('Nie udało się skopiować makra')
    }
  }

  return (
    <div
      className={`grid gap-3 sm:col-span-2 sm:items-start ${
        withListNumber
          ? 'sm:grid-cols-[minmax(0,1fr)_7rem_7rem_auto]'
          : withPrintNumber
            ? 'sm:grid-cols-[minmax(0,1fr)_9rem_auto]'
            : 'sm:grid-cols-[minmax(0,1fr)_auto]'
      }`}
    >
      <StoreLocationField
        storeId={storeId}
        locationKey={importLocationKey(kind, 'path')}
        label="Zapis z Mercalys (ścieżka pliku)"
        className="font-mono"
        onDraftChange={(path) => onChange({ path })}
        error={pathError ?? undefined}
        hint={pathError ? undefined : `Makro zapisze tu raport z Mercalys, np. ${pathExample(kind)}`}
      />
      {withPrintNumber && (
        <StoreLocationField
          storeId={storeId}
          locationKey={importLocationKey(kind, 'print')}
          label="Nr wydruku"
          numeric
          maxLength={2}
          onDraftChange={(printNumber) => onChange({ printNumber })}
          error={settings.path.trim() ? (printNumberError(settings.printNumber ?? '') ?? undefined) : undefined}
          hint="Wydruk Ustawialny w Mercalys"
        />
      )}
      {withListNumber && (
        <StoreLocationField
          storeId={storeId}
          locationKey={importLocationKey(kind, 'list')}
          label="Nr listy"
          numeric
          maxLength={4}
          onDraftChange={(listNumber) => onChange({ listNumber })}
          error={settings.path.trim() ? (listNumberError(settings.listNumber ?? '') ?? undefined) : undefined}
          hint="Innych list w Mercalys"
        />
      )}
      <Button
        type="button"
        variant="outline"
        size="lg"
        disabled={!ready}
        onClick={() => void copy()}
        title="Makro do schowka tylko dla tego raportu (Alt+E)"
      >
        <ClipboardCopyIcon data-icon="inline-start" />
        Makro
      </Button>
    </div>
  )
}
