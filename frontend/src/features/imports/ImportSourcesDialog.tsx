import { useState } from 'react'
import { ClipboardCopyIcon, FolderIcon, FolderOpenIcon } from 'lucide-react'
import { toast } from 'sonner'

import { TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DAILY_REPORTS,
  isMacroReport,
  macroPathError,
  MASK_EXAMPLES,
  readMacroPaths,
  readMacroPrintNumbers,
  readMasks,
  REPORT_EXTENSIONS,
  REPORT_TITLES,
  sourceFolderKey,
  writeMacroPaths,
  writeMacroPrintNumbers,
  writeMasks,
  type DailyReport,
  type MacroPaths,
  type MacroPrintNumbers,
  type MacroReport,
  type SourceMasks,
} from '@/features/imports/import-sources'
import {
  buildReportSaveMacro,
  lastDocumentDate,
  macroPlayerVersion,
  opensItself,
  pathExample,
  PRINT_NUMBER_REPORTS,
  printNumberError,
  type ReportMacroSettings,
} from '@/features/imports/report-macros'
import { useDocumentImports, useSalesImports } from '@/features/imports/api'
import { ApiError } from '@/lib/api'
import { folderSavingSupported, useSavedFolder } from '@/lib/saved-folders'
import type { ImportGuide } from '@/lib/types'

/**
 * Ustawienia „Odśwież dane”: folder każdego codziennego raportu (wybrany raz w tej przeglądarce) i opcjonalna maska
 * nazwy pliku, gdy w folderze leżą też inne pliki. Listy dostawców i nomenklatur wgrywa się tylko ręcznie.
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
            którego jeszcze nie było. Ustawienia zostają w tej przeglądarce, osobno dla każdego sklepu.
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
  const [masks, setMasks] = useState<SourceMasks>(() => readMasks(storeId))
  const [macroPaths, setMacroPaths] = useState<MacroPaths>(() => readMacroPaths(storeId))

  const changeMacroPath = (kind: MacroReport, path: string) => {
    const next = { ...macroPaths, [kind]: path }
    setMacroPaths(next)
    writeMacroPaths(storeId, next)
  }

  const [printNumbers, setPrintNumbers] = useState<MacroPrintNumbers>(() => readMacroPrintNumbers(storeId))

  const changePrintNumber = (kind: MacroReport, number: string) => {
    const next = { ...printNumbers, [kind]: number }
    setPrintNumbers(next)
    writeMacroPrintNumbers(storeId, next)
  }

  const changeMask = (kind: DailyReport, mask: string) => {
    const next = { ...masks, [kind]: mask }
    setMasks(next)
    writeMasks(storeId, next)
  }

  return (
    <ul className="grid gap-3">
      {DAILY_REPORTS.map((kind) => (
        <SourceRow
          key={kind}
          storeId={storeId}
          kind={kind}
          suggestedPath={guides.find((guide) => guide.kind === kind)?.folderPath ?? null}
          mask={masks[kind] ?? ''}
          onMaskChange={(mask) => changeMask(kind, mask)}
          macro={isMacroReport(kind) ? { path: macroPaths[kind] ?? '', printNumber: printNumbers[kind] ?? '' } : null}
          onMacroPathChange={(path) => isMacroReport(kind) && changeMacroPath(kind, path)}
          onPrintNumberChange={(number) => isMacroReport(kind) && changePrintNumber(kind, number)}
        />
      ))}
    </ul>
  )
}

function SourceRow({
  storeId,
  kind,
  suggestedPath,
  mask,
  onMaskChange,
  macro,
  onMacroPathChange,
  onPrintNumberChange,
}: {
  storeId: string
  kind: DailyReport
  suggestedPath: string | null
  mask: string
  onMaskChange: (mask: string) => void
  /** Ustawienia zapisu makrem; null = raport bez makra. */
  macro: ReportMacroSettings | null
  onMacroPathChange: (path: string) => void
  onPrintNumberChange: (number: string) => void
}) {
  const { name, loaded, pick } = useSavedFolder(sourceFolderKey(storeId, kind), 'read')
  const [picking, setPicking] = useState(false)
  const title = REPORT_TITLES[kind]

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
    <li className="grid gap-3 rounded-xl border bg-muted/40 p-3 sm:grid-cols-[minmax(0,1fr)_14rem] sm:items-center">
      <div className="flex min-w-0 items-center gap-3">
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
              'Nie wybrano folderu: raport wgrywasz ręcznie'
            )}
          </div>
        </div>
        <Button type="button" variant="outline" size="sm" loading={picking} disabled={!loaded} onClick={choose}>
          {name ? 'Zmień' : 'Wybierz folder'}
        </Button>
      </div>
      <TextField
        label="Nazwa pliku (opcjonalnie)"
        autoComplete="off"
        value={mask}
        onChange={(event) => onMaskChange(event.target.value)}
        hint={`Np. ${MASK_EXAMPLES[kind]} (* = dowolne znaki)`}
      />
      {macro && isMacroReport(kind) && (
        <MacroFields
          storeId={storeId}
          kind={kind}
          settings={macro}
          mask={mask}
          onPathChange={onMacroPathChange}
          onPrintNumberChange={onPrintNumberChange}
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
  onPathChange,
  onPrintNumberChange,
}: {
  storeId: string
  kind: MacroReport
  settings: ReportMacroSettings
  mask: string
  onPathChange: (path: string) => void
  onPrintNumberChange: (number: string) => void
}) {
  const withPrintNumber = PRINT_NUMBER_REPORTS.includes(kind)
  const pathError = macroPathError(kind, settings.path, mask)
  const numberError = withPrintNumber ? printNumberError(settings.printNumber ?? '') : null
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
        withPrintNumber ? 'sm:grid-cols-[minmax(0,1fr)_9rem_auto]' : 'sm:grid-cols-[minmax(0,1fr)_auto]'
      }`}
    >
      <TextField
        label="Zapis z Mercalys (ścieżka pliku)"
        autoComplete="off"
        spellCheck={false}
        className="font-mono"
        value={settings.path}
        onChange={(event) => onPathChange(event.target.value)}
        error={pathError ?? undefined}
        hint={pathError ? undefined : `Makro zapisze tu raport z Mercalys, np. ${pathExample(kind)}`}
      />
      {withPrintNumber && (
        <TextField
          label="Nr wydruku"
          autoComplete="off"
          inputMode="numeric"
          maxLength={2}
          value={settings.printNumber ?? ''}
          onChange={(event) => onPrintNumberChange(event.target.value.replace(/\D/g, ''))}
          error={settings.path.trim() ? (numberError ?? undefined) : undefined}
          hint="Wydruk Ustawialny w Mercalys"
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
