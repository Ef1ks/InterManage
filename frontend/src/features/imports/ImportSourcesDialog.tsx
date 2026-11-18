import { useState } from 'react'
import { ClipboardCopyIcon, FolderIcon, FolderOpenIcon } from 'lucide-react'
import { toast } from 'sonner'

import { SelectField, TextField } from '@/components/form'
import { StoreLocationField } from '@/components/StoreLocationField'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import {
  DAILY_REPORTS,
  importLocationKey,
  isMacroReport,
  isPeriodReport,
  macroPathError,
  MASK_EXAMPLES,
  OPENED_ON_KEY,
  partLengthOptions,
  REPORT_EXTENSIONS,
  REPORT_TITLES,
  sourceFolderKey,
  type DailyReport,
  type MacroReport,
} from '@/features/imports/import-sources'
import {
  buildReportSaveMacro,
  HISTORY_START,
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
import { useDocumentImports, useImportCoverage, useSalesImports } from '@/features/imports/api'
import { useImportSettings } from '@/features/imports/use-import-settings'
import { ApiError } from '@/lib/api'
import { todayIso } from '@/lib/dates'
import { folderSavingSupported, useSavedFolder } from '@/lib/saved-folders'
import { parentFolder, useSaveStoreLocations, useStoreLocations } from '@/lib/store-locations'
import type { ImportGuide } from '@/lib/types'

/**
 * Ustawienia „Odśwież dane”: folder każdego codziennego raportu (wybrany raz w tej przeglądarce) i opcjonalna maska nazwy pliku, gdy w folderze leżą też inne pliki. Maski, ścieżki zapisu makr
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
            którego jeszcze nie było. Nazwy plików, ścieżki zapisu makr i numery z Mercalys są wspólne dla całego
            sklepu. Folder przeglądarka pamięta u siebie, więc na nowym komputerze wybierz go raz: folder z zapisu makra
            skopiujemy do schowka.
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
      <OpenedOnField storeId={storeId} />
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
  const [mask, setMask] = useState('')
  const [macro, setMacro] = useState<ReportMacroSettings>({ path: '', printNumber: '', listNumber: '' })
  // Podpowiedź do schowka: folder pliku z makra, bez niego folder z instrukcji raportu.
  const suggestedPath = parentFolder(macro.path) ?? guidePath

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
        {/* Bez czekania na odczyt zapamiętanego folderu: wybór nowego go nie potrzebuje. */}
        <Button type="button" variant="outline" size="sm" loading={picking} onClick={choose}>
          {loaded && !name ? 'Wybierz folder' : 'Zmień'}
        </Button>
      </div>
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
  const withPartLength = isPeriodReport(kind)
  const locations = useStoreLocations(storeId)
  const partLength = locations.data?.[importLocationKey(kind, 'part')] ?? ''
  const openedOn = locations.data?.[OPENED_ON_KEY] || null
  const macroSettings = { ...settings, partLength }
  const pathError = macroPathError(kind, settings.path, mask)
  const numberError =
    (withPrintNumber ? printNumberError(settings.printNumber ?? '') : null) ??
    (withListNumber ? listNumberError(settings.listNumber ?? '') : null)
  // Okres raportu sprzedaży zależy od ostatniego dnia w bazie (ta sama lista co na stronie importu, z pamięci)
  const sales = useSalesImports(storeId)
  const documents = useDocumentImports(storeId)
  const coverage = useImportCoverage(storeId)
  const waitsForSales =
    (kind === 'SALES' && (sales.isPending || coverage.isPending)) ||
    (kind === 'DOCUMENTS' && (documents.isPending || coverage.isPending))
  const ready = settings.path.trim() !== '' && pathError === null && numberError === null && !waitsForSales

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(
        buildReportSaveMacro([{ kind, settings: macroSettings }], {
          lastSaleDate: sales.data?.lastSaleDate,
          lastDocumentDate: lastDocumentDate(documents.data),
          salesCovered: coverage.data?.sales,
          documentsCovered: coverage.data?.documents,
          openedOn,
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
            : withPartLength
              ? 'sm:grid-cols-[minmax(0,1fr)_12rem_auto]'
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
      {withPartLength && <PartLengthField storeId={storeId} kind={kind} value={partLength} />}
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

/**
 * Dzień otwarcia sklepu, wspólny dla sklepu: makro nie drukuje sprzedaży ani dokumentów sprzed niego (puste raporty
 * albo dni z niepełnym startem sklepu). Puste = od HISTORY_START.
 */
function OpenedOnField({ storeId }: { storeId: string }) {
  const locations = useStoreLocations(storeId)
  const save = useSaveStoreLocations(storeId)
  const saved = locations.data?.[OPENED_ON_KEY] ?? ''
  const [text, setText] = useState(displayDate(saved))
  const [error, setError] = useState<string | null>(null)

  // Zapis z innego komputera (po odświeżeniu) wraca do pola.
  const [shown, setShown] = useState(saved)
  if (shown !== saved) {
    setShown(saved)
    setText(displayDate(saved))
    setError(null)
  }

  const commit = (raw: string) => {
    const typed = raw.trim()
    const next = typed ? parseTypedDate(typed) : ''
    if (next === null) {
      setError('Wpisz datę jako DD.MM.RRRR, np. 15.03.2025')
      return
    }
    if (next && (next < HISTORY_START || next > todayIso())) {
      setError(`Data od ${displayDate(HISTORY_START)} do dziś`)
      return
    }
    setError(null)
    setText(displayDate(next))
    if (next === saved) return
    save.mutate(
      { [OPENED_ON_KEY]: next },
      {
        onSuccess: () =>
          toast.success(next ? 'Zapisano dzień otwarcia sklepu' : 'Usunięto dzień otwarcia sklepu', {
            description: 'Skopiuj makro ponownie, żeby wzięło nowy okres.',
          }),
        onError: (e) => toast.error(e instanceof ApiError ? e.message : 'Nie udało się zapisać ustawienia'),
      },
    )
  }

  return (
    <li className="grid gap-3 rounded-xl border bg-muted/40 p-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-start">
      <TextField
        label="Sklep otwarty od (DD.MM.RRRR)"
        autoComplete="off"
        inputMode="numeric"
        maxLength={10}
        value={text}
        onChange={(event) => setText(event.target.value)}
        onBlur={() => commit(text)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            commit(text)
          }
        }}
        error={error ?? undefined}
        hint="Makro sprzedaży i dokumentów nie będzie drukować dni sprzed tej daty. Puste = bez ograniczenia."
      />
      {saved && (
        <Button
          variant="outline"
          size="lg"
          disabled={save.isPending}
          onClick={() => {
            setText('')
            commit('')
          }}
        >
          Wyczyść
        </Button>
      )}
    </li>
  )
}

/** RRRR-MM-DD jako DD.MM.RRRR (puste zostaje puste). */
function displayDate(iso: string) {
  return iso ? `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}` : ''
}

/** Wpisana data (DD.MM.RRRR, DD-MM-RRRR, DD/MM/RRRR, DDMMRRRR albo RRRR-MM-DD) jako RRRR-MM-DD; zła = null. */
function parseTypedDate(typed: string): string | null {
  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(typed)
  const local = /^(\d{1,2})[.\-/ ](\d{1,2})[.\-/ ](\d{4})$/.exec(typed) ?? /^(\d{2})(\d{2})(\d{4})$/.exec(typed)
  const [year, month, day] = iso
    ? [Number(iso[1]), Number(iso[2]), Number(iso[3])]
    : local
      ? [Number(local[3]), Number(local[2]), Number(local[1])]
      : [0, 0, 0]
  if (!year) return null
  const date = new Date(year, month - 1, day)
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

/**
 * Długość jednej części zaległego okresu (sprzedaż, dokumenty), wspólna dla sklepu. Krótsza część, gdy raport za kilka
 * miesięcy ma milion wierszy i Mercalys albo Excel go nie wygeneruje. Domyślnie limit Mercalys.
 */
function PartLengthField({ storeId, kind, value }: { storeId: string; kind: MacroReport; value: string }) {
  const save = useSaveStoreLocations(storeId)
  const options = partLengthOptions(kind)
  const fallback = options[options.length - 1].value
  const current = options.some((option) => option.value === value) ? value : fallback

  return (
    <SelectField
      label="Zaległy okres po"
      options={options}
      value={current}
      disabled={save.isPending}
      onChange={(next) => {
        if (!next || next === current) return
        save.mutate(
          { [importLocationKey(kind, 'part')]: next === fallback ? '' : next },
          { onError: (e) => toast.error(e instanceof ApiError ? e.message : 'Nie udało się zapisać ustawienia') },
        )
      }}
      hint="Długość jednej części przy uzupełnianiu starych okresów"
    />
  )
}
