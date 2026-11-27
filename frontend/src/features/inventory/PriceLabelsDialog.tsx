import { FileOutputIcon, MinusIcon, PlusIcon, TagsIcon, Trash2Icon, Undo2Icon, UsersIcon, XIcon } from 'lucide-react'
import { useRef, useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { useCan } from '@/features/auth/api'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import { priceLabelsFilePath, usePriceMarkdownChange, usePriceMarkdowns } from '@/features/inventory/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ApiError, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import type { PriceLabelKind, PriceMarkdown } from '@/lib/types'
import { cn } from '@/lib/utils'

const KINDS: PriceLabelKind[] = ['PRICE_CHANGE', 'MARKDOWN']
/** Format cenówki w nazwie pliku, gdy pole nazwy jest puste (jak `PriceLabelKind` w backendzie). */
const DEFAULT_NAMES: Record<PriceLabelKind, string> = { PRICE_CHANGE: 'BIALFOR67', MARKDOWN: 'PROPROFOR67' }
const PROGRAM_LABEL = 'Cenówka z programu'
const NAMES_LIST_ID = 'price-label-names'
const MAX_COUNT = 9999
const MAX_LINES = 6
const UNDO_STEPS = 50
const UNDO_MERGE_MS = 800
const NAMES_KEY = 'intermanage.priceLabelCustomNames'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/** Program sklepu dzieli nazwę pliku po `_`, więc w nazwie zostają same litery i cyfry. */
const namePart = (text: string) =>
  text
    .replace(/[^A-Za-z0-9]/g, '')
    .toUpperCase()
    .slice(0, 40)

function readCustomNames(): string[] {
  try {
    const saved = JSON.parse(localStorage.getItem(NAMES_KEY) ?? '[]') as unknown
    return Array.isArray(saved) ? saved.filter((name): name is string => typeof name === 'string').slice(0, 10) : []
  } catch {
    return []
  }
}

function writeCustomNames(names: string[]) {
  try {
    localStorage.setItem(NAMES_KEY, JSON.stringify(names))
  } catch {
    // Bez localStorage własne nazwy obowiązują do zamknięcia karty.
  }
}

/** Jedna linia przy artykule: ile sztuk i nazwa pliku (pusta = cenówka z programu). */
type PrintLine = { count: string; name: string }

const parseCount = (text: string) => {
  const value = Number(text)
  return text.trim() !== '' && Number.isInteger(value) && value >= 0 && value <= MAX_COUNT ? value : null
}

const fileLabel = (file: { name: string; kind: PriceLabelKind }) =>
  file.name === DEFAULT_NAMES[file.kind] ? PROGRAM_LABEL : file.name

const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** Przycisk w nagłówku Magazynu i Ustaw promocje z liczbą przecen bez cenówki. */
export function PriceLabelsButton({ storeId }: { storeId: string }) {
  const markdowns = usePriceMarkdowns(storeId)
  const [open, setOpen] = useState(false)
  const waiting = (markdowns.data ?? []).filter((item) => item.labelsGeneratedAt === null).length
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <TagsIcon />
        Cenówki
        {waiting > 0 && (
          <span className="rounded-full bg-foreground px-1.5 text-xs leading-5 font-semibold text-background tabular-nums">
            {waiting}
          </span>
        )}
      </Button>
      <PriceLabelsDialog storeId={storeId} open={open} onOpenChange={setOpen} />
    </>
  )
}

/**
 * Przeceny i zmiany cen czekające na cenówkę. Przy artykule linie „ile sztuk – jaki wydruk” (np. 3 cenówki
 * promocyjne i 5 plakatów); wydruk to nazwa pliku z listy albo wpisana. Każda nazwa daje osobny plik ETIQUETTE
 * w folderze protokołów (przeceny i zmiany cen osobno), a artykuły przechodzą do „W pliku”.
 */
function PriceLabelsDialog({
  storeId,
  open,
  onOpenChange,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const markdowns = usePriceMarkdowns(storeId)
  const change = usePriceMarkdownChange(storeId)
  const folder = useProtoFolder(storeId)
  // Ustawianie promocji (DATA_ENTRY:VIEW) dopisuje swoje artykuły i drukuje ich cenówki.
  const canEditInventory = useCan('INVENTORY', 'EDIT')
  const canSetPromotions = useCan('DATA_ENTRY')
  const canEdit = canEditInventory || canSetPromotions
  const [saving, setSaving] = useState(false)
  // Do wydruku zaznaczone jest wszystko, a już utworzone nic, więc pamiętamy tylko przełączone.
  const [toggled, setToggled] = useState<ReadonlySet<string>>(new Set())
  const [lines, setLines] = useState<Record<string, PrintLine[]>>({})
  const [customNames, setCustomNames] = useState(readCustomNames)
  const [bulkLines, setBulkLines] = useState<PrintLine[]>([{ count: '1', name: '' }])

  const items = markdowns.data ?? []
  const pending = items.filter((item) => item.labelsGeneratedAt === null)
  const generated = items.filter((item) => item.labelsGeneratedAt !== null)
  const isChecked = (item: PriceMarkdown) => (item.labelsGeneratedAt === null) !== toggled.has(item.id)
  const pickedPending = pending.filter(isChecked)
  const pickedGenerated = generated.filter(isChecked)

  // Cofanie: stan przed każdą zmianą; kolejne zmiany tego samego pola w krótkim czasie (pisanie, klikanie +) to jeden krok.
  const [history, setHistory] = useState<{ lines: typeof lines; bulkLines: PrintLine[] }[]>([])
  const lastChange = useRef<{ key: string; at: number } | null>(null)

  const record = (key: string) => {
    const now = Date.now()
    const last = lastChange.current
    lastChange.current = { key, at: now }
    if (last && last.key === key && now - last.at < UNDO_MERGE_MS) return
    setHistory((current) => [...current.slice(-(UNDO_STEPS - 1)), { lines, bulkLines }])
  }

  const undo = () => {
    const previous = history.at(-1)
    if (!previous) return
    setLines(previous.lines)
    setBulkLines(previous.bulkLines)
    setHistory((current) => current.slice(0, -1))
    lastChange.current = null
  }

  const linesOf = (item: PriceMarkdown) => lines[item.id] ?? [{ count: '1', name: '' }]

  const setItemLines = (item: PriceMarkdown, next: PrintLine[]) =>
    setLines((current) => ({ ...current, [item.id]: next }))

  const updateLine = (item: PriceMarkdown, index: number, patch: Partial<PrintLine>) => {
    record(`${item.id}:${index}:${Object.keys(patch).join()}`)
    setItemLines(
      item,
      linesOf(item).map((line, at) => (at === index ? { ...line, ...patch } : line)),
    )
  }

  const addLine = (item: PriceMarkdown) => {
    record(`add:${item.id}`)
    lastChange.current = null
    setItemLines(item, [...linesOf(item), { count: '1', name: '' }])
  }

  const removeLine = (item: PriceMarkdown, index: number) => {
    record(`remove:${item.id}`)
    lastChange.current = null
    setItemLines(
      item,
      linesOf(item).filter((_, at) => at !== index),
    )
  }

  // Wpisane nazwy podpowiada przeglądarka przy kolejnym wpisywaniu.
  const rememberName = (name: string) => {
    if (!name || customNames[0] === name) return
    const next = [name, ...customNames.filter((saved) => saved !== name)].slice(0, 10)
    setCustomNames(next)
    writeCustomNames(next)
  }

  const toggle = (id: string) =>
    setToggled((current) => {
      const next = new Set(current)
      if (!next.delete(id)) next.add(id)
      return next
    })

  /** Pliki, które powstaną: nazwa i rodzaj → artykuły z liczbą sztuk. */
  const plan = (chosen: PriceMarkdown[]) => {
    const files = new Map<
      string,
      { name: string; kind: PriceLabelKind; items: { item: PriceMarkdown; count: number }[] }
    >()
    for (const item of chosen) {
      for (const line of linesOf(item)) {
        const count = parseCount(line.count) ?? 0
        if (count === 0) continue
        const name = line.name || DEFAULT_NAMES[item.kind]
        const key = `${name}|${item.kind}`
        const file = files.get(key) ?? { name, kind: item.kind, items: [] }
        const same = file.items.find((entry) => entry.item.id === item.id)
        if (same) same.count = Math.min(MAX_COUNT, same.count + count)
        else file.items.push({ item, count })
        files.set(key, file)
      }
    }
    return [...files.values()]
  }

  const pendingPlan = plan(pickedPending)

  const createFiles = async (chosen: PriceMarkdown[]) => {
    if (chosen.some((item) => linesOf(item).some((line) => parseCount(line.count) === null))) {
      toast.error(`Liczba sztuk musi być od 0 do ${MAX_COUNT}`)
      return
    }
    const files = plan(chosen)
    if (files.length === 0) {
      toast.error('Wpisz, ile sztuk wydrukować')
      return
    }
    setSaving(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu.
      const folderReady = await ensureFolderAccess(folder.key)
      const saved: string[] = []
      let lastName: string | null = null
      for (const file of files.sort(
        (a, b) => a.name.localeCompare(b.name) || KINDS.indexOf(a.kind) - KINDS.indexOf(b.kind),
      )) {
        // Nazwa pliku ma czas co do sekundy: kolejny plik z tą samą nazwą czeka, żeby nie nadpisać poprzedniego.
        if (lastName === file.name) await pause(1100)
        lastName = file.name
        const { fileName, blob } = await fetchPostedFile(
          priceLabelsFilePath(storeId),
          {
            items: file.items.map(({ item, count }) => ({ id: item.id, count })),
            name: file.name === DEFAULT_NAMES[file.kind] ? null : file.name,
          },
          'ETIQUETTE.traite0',
        )
        if (folderReady) await saveToFolder(folder.key, fileName, blob)
        else saveBlob(blob, fileName)
        saved.push(`${fileLabel(file)}: ${file.items.reduce((sum, entry) => sum + entry.count, 0)} szt.`)
      }
      const done = [...new Set(files.flatMap((file) => file.items.map(({ item }) => item.id)))]
      await change.mutateAsync({ action: 'labels-generated', ids: done })
      toast.success('Utworzono cenówki', {
        description: `${saved.join(' · ')}${folderReady ? '' : ' · pobrane, bo nie ma zgody na folder protokołów'}`,
        duration: 10_000,
      })
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się utworzyć cenówek'))
    } finally {
      setSaving(false)
    }
  }

  const remove = (chosen: PriceMarkdown[]) => {
    const ids = chosen.map((item) => item.id)
    if (ids.length === 0) return
    change.mutate(
      { action: 'delete', ids },
      {
        onSuccess: () =>
          toast(
            ids.length === 1
              ? `Usunięto z listy: ${chosen[0].name ?? chosen[0].ean}`
              : `Usunięto z listy: ${ids.length}`,
            {
              action: {
                label: 'Przywróć',
                onClick: () =>
                  change.mutate(
                    { action: 'restore', ids },
                    { onError: () => toast.error('Nie udało się przywrócić cenówek') },
                  ),
              },
            },
          ),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć cenówek')),
      },
    )
  }

  const stepper = (value: string, onChange: (text: string) => void, what: string) => {
    const current = parseCount(value) ?? 0
    const set = (next: number) => onChange(String(Math.min(MAX_COUNT, Math.max(0, next))))
    return (
      <div className="flex items-center rounded-lg border border-input bg-background dark:bg-input/30">
        <Button
          variant="ghost"
          size="icon"
          className="rounded-r-none"
          aria-label={`Mniej: ${what}`}
          disabled={!canEdit || current === 0}
          onClick={() => set(current - 1)}
        >
          <MinusIcon />
        </Button>
        <input
          inputMode="numeric"
          aria-label={`Ile sztuk: ${what}`}
          aria-invalid={parseCount(value) === null}
          value={value}
          disabled={!canEdit}
          onChange={(event) => onChange(event.target.value.replace(/[^0-9]/g, ''))}
          onFocus={(event) => event.target.select()}
          className="h-9 w-12 bg-transparent text-center text-base font-semibold tabular-nums outline-none aria-invalid:text-destructive"
        />
        <Button
          variant="ghost"
          size="icon"
          className="rounded-l-none"
          aria-label={`Więcej: ${what}`}
          disabled={!canEdit}
          onClick={() => set(current + 1)}
        >
          <PlusIcon />
        </Button>
      </div>
    )
  }

  // Nazwa pliku do wpisania; puste pole = cenówka z programu.
  const nameField = (value: string, onChange: (name: string) => void, what: string) => (
    <Input
      list={NAMES_LIST_ID}
      placeholder={PROGRAM_LABEL}
      aria-label={`Nazwa pliku: ${what}`}
      title="Puste pole = cenówka z programu. Możesz wpisać własną nazwę, np. PLAKAT."
      value={value}
      disabled={!canEdit}
      onChange={(event) => onChange(namePart(event.target.value))}
      onBlur={(event) => rememberName(namePart(event.target.value))}
      onKeyDown={(event) => {
        if (event.key === 'Enter') event.currentTarget.blur()
      }}
      className="h-9 w-56 bg-background uppercase placeholder:normal-case"
    />
  )

  // Górny wiersz działa od razu: zmiana w nim zmienia tę samą linię u wszystkich zaznaczonych artykułów.
  const applyToPicked = (change: (own: PrintLine[]) => PrintLine[]) =>
    setLines((current) => {
      const next = { ...current }
      for (const item of pickedPending) next[item.id] = change(current[item.id] ?? [{ count: '1', name: '' }])
      return next
    })

  // Brakujące linie artykułu dostają wartości z górnego wiersza.
  const padTo = (own: PrintLine[], master: PrintLine[], length: number) =>
    own.length >= length ? [...own] : [...own, ...master.slice(own.length, length)]

  const updateBulk = (index: number, patch: Partial<PrintLine>) => {
    record(`bulk:${index}:${Object.keys(patch).join()}`)
    const master = bulkLines.map((line, at) => (at === index ? { ...line, ...patch } : line))
    setBulkLines(master)
    applyToPicked((own) => {
      const next = padTo(own, master, index + 1)
      next[index] = { ...next[index], ...patch }
      return next
    })
  }

  const addBulkLine = () => {
    record('bulk:add')
    lastChange.current = null
    const master = [...bulkLines, { count: '1', name: '' }]
    setBulkLines(master)
    applyToPicked((own) => padTo(own, master, master.length))
  }

  const removeBulkLine = (index: number) => {
    record('bulk:remove')
    lastChange.current = null
    setBulkLines(bulkLines.filter((_, at) => at !== index))
    applyToPicked((own) => (own.length > index && own.length > 1 ? own.filter((_, at) => at !== index) : own))
  }

  const allPendingChecked = pickedPending.length === pending.length
  const checkAllPending = (check: boolean) =>
    setToggled((current) => {
      const next = new Set(current)
      for (const item of pending) {
        if (check) next.delete(item.id)
        else next.add(item.id)
      }
      return next
    })

  const lineRow = (item: PriceMarkdown, line: PrintLine, index: number, count: number) => {
    const what = item.name ?? item.ean
    return (
      <div key={index} className="flex flex-wrap items-center gap-2">
        {stepper(line.count, (text) => updateLine(item, index, { count: text }), what)}
        <span className="text-sm text-muted-foreground">szt.</span>
        {nameField(line.name, (name) => updateLine(item, index, { name }), what)}
        {count > 1 && canEdit && (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Usuń wydruk: ${what}`}
            title="Usuń ten wydruk"
            onClick={() => removeLine(item, index)}
          >
            <XIcon />
          </Button>
        )}
      </div>
    )
  }

  const row = (item: PriceMarkdown) => {
    const itemLines = linesOf(item)
    const checked = isChecked(item)
    return (
      <li key={item.id} className={cn('flex gap-3 border-b px-4 py-3 last:border-b-0', !checked && 'opacity-60')}>
        <input
          type="checkbox"
          checked={checked}
          disabled={!canEdit}
          aria-label={`Drukuj: ${item.name ?? item.ean}`}
          onChange={() => toggle(item.id)}
          className="mt-1 size-5 shrink-0 accent-blue-600"
        />
        <div className="flex min-w-0 flex-1 flex-col gap-2.5">
          <div className="flex items-start gap-3">
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-1">
                <span className="min-w-0 truncate font-medium" title={item.name ?? undefined}>
                  {item.name ?? `ITM8 ${item.itm8}`}
                </span>
                <ArticleStatsButton article={item} />
              </div>
              <div className="text-xs text-muted-foreground tabular-nums">
                {item.ean} ·{' '}
                {item.kind === 'PRICE_CHANGE'
                  ? `zmiana ceny od ${formatDocumentDate(item.validFrom)}`
                  : `promocja ${formatDocumentDate(item.validFrom)} – ${item.validTo ? formatDocumentDate(item.validTo) : ''}`}
              </div>
            </div>
            <div className="text-right whitespace-nowrap tabular-nums">
              {item.kind === 'MARKDOWN' && item.regularPrice != null && (
                <span className="mr-2 text-sm text-muted-foreground line-through">
                  {formatMoney(item.regularPrice)}
                </span>
              )}
              <span className="text-lg font-semibold">{formatMoney(item.newPrice)} zł</span>
            </div>
            {canEdit && (
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Usuń z listy: ${item.name ?? item.ean}`}
                title="Usuń z listy"
                disabled={change.isPending}
                onClick={() => remove([item])}
              >
                <Trash2Icon />
              </Button>
            )}
          </div>
          {checked && (
            <div className="flex flex-col gap-2">
              {itemLines.map((line, index) => lineRow(item, line, index, itemLines.length))}
              {canEdit && itemLines.length < MAX_LINES && (
                <button
                  type="button"
                  onClick={() => addLine(item)}
                  className="flex w-fit items-center gap-1.5 rounded-md px-1 py-1 text-sm font-medium text-blue-700 hover:underline dark:text-blue-300"
                >
                  <PlusIcon className="size-4" />
                  Dodaj inny wydruk (np. plakat)
                </button>
              )}
            </div>
          )}
        </div>
      </li>
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="gap-4 p-6 sm:max-w-3xl"
        onKeyDown={(event) => {
          if (
            (event.ctrlKey || event.metaKey) &&
            !event.shiftKey &&
            event.key.toLowerCase() === 'z' &&
            history.length > 0
          ) {
            event.preventDefault()
            undo()
          }
        }}
      >
        <DialogHeader>
          <DialogTitle>Cenówki</DialogTitle>
          <DialogDescription>
            Ustaw, ile sztuk. Puste pole nazwy = cenówka z programu, możesz wpisać własną (np. PLAKAT).
          </DialogDescription>
        </DialogHeader>

        <section aria-label="Do wydruku" className="flex flex-col gap-3">
          {canEdit && pending.length > 1 && (
            <div className="flex gap-3 rounded-xl border-2 border-blue-300 bg-blue-100 px-4 py-3 text-blue-950 dark:border-blue-800 dark:bg-blue-950/60 dark:text-blue-50">
              <input
                type="checkbox"
                checked={allPendingChecked}
                aria-label="Zaznacz wszystkie"
                ref={(input) => {
                  if (input) input.indeterminate = pickedPending.length > 0 && !allPendingChecked
                }}
                onChange={() => checkAllPending(!allPendingChecked)}
                className="mt-1 size-5 shrink-0 accent-blue-600"
              />
              <div className="flex min-w-0 flex-1 flex-col gap-2.5">
                <div>
                  <div className="flex items-center gap-2 font-semibold">
                    <UsersIcon className="size-4" />
                    Wszystkim naraz ({pickedPending.length} zaznaczonych)
                  </div>
                  <div className="text-xs text-blue-900 dark:text-blue-200">
                    To, co ustawisz tutaj, od razu dostają wszystkie zaznaczone artykuły.
                  </div>
                </div>
                {bulkLines.map((line, index) => (
                  <div key={index} className="flex flex-wrap items-center gap-2">
                    {stepper(line.count, (text) => updateBulk(index, { count: text }), 'wszystkie zaznaczone')}
                    <span className="text-sm text-muted-foreground">szt.</span>
                    {nameField(line.name, (name) => updateBulk(index, { name }), 'wszystkie zaznaczone')}
                    {bulkLines.length > 1 && (
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label="Usuń ten wydruk wszystkim zaznaczonym"
                        title="Usuń ten wydruk wszystkim zaznaczonym"
                        onClick={() => removeBulkLine(index)}
                      >
                        <XIcon />
                      </Button>
                    )}
                  </div>
                ))}
                {bulkLines.length < MAX_LINES && (
                  <button
                    type="button"
                    disabled={pickedPending.length === 0}
                    onClick={addBulkLine}
                    className="flex w-fit items-center gap-1.5 rounded-md px-1 py-1 text-sm font-medium text-blue-800 hover:underline disabled:opacity-50 dark:text-blue-200"
                  >
                    <PlusIcon className="size-4" />
                    Dodaj inny wydruk wszystkim (np. plakat)
                  </button>
                )}
              </div>
            </div>
          )}
          <div className="flex items-center justify-between gap-3">
            <h3 className="text-sm font-semibold">Każdy artykuł osobno ({pending.length})</h3>
            {canEdit && (
              <Button
                variant="outline"
                size="sm"
                disabled={history.length === 0}
                title="Cofnij ostatnią zmianę (Ctrl+Z)"
                onClick={undo}
              >
                <Undo2Icon />
                Cofnij
              </Button>
            )}
          </div>
          <div className="max-h-[48vh] overflow-y-auto rounded-lg border">
            <ul aria-label="Cenówki do wydruku">{pending.map(row)}</ul>
            {pending.length === 0 && (
              <p className="px-3 py-6 text-center text-sm text-muted-foreground">
                {markdowns.isLoading
                  ? 'Wczytywanie…'
                  : 'Nowe cenówki pojawią się po przecenie artykułu albo zmianie ceny we wprowadzaniu danych.'}
              </p>
            )}
          </div>
          {canEdit && pending.length > 0 && (
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-muted-foreground">
                {pendingPlan.length === 0
                  ? 'Nic do wydruku: wpisz liczbę sztuk.'
                  : `Powstanie: ${pendingPlan
                      .map((file) => `${fileLabel(file)} ${file.items.reduce((sum, e) => sum + e.count, 0)} szt.`)
                      .join(', ')}`}
              </p>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  disabled={pickedPending.length === 0 || saving || change.isPending}
                  onClick={() => remove(pickedPending)}
                >
                  <Trash2Icon />
                  Usuń zaznaczone ({pickedPending.length})
                </Button>
                <Button
                  size="lg"
                  loading={saving}
                  disabled={pendingPlan.length === 0 || change.isPending}
                  onClick={() => void createFiles(pickedPending)}
                >
                  <FileOutputIcon />
                  Utwórz cenówki
                </Button>
              </div>
            </div>
          )}
        </section>

        {generated.length > 0 && (
          <section aria-label="W pliku" className="flex flex-col gap-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-sm font-medium">Już utworzone ({generated.length})</h3>
              {canEdit && pickedGenerated.length > 0 && (
                <div className="flex flex-wrap gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={saving || change.isPending}
                    onClick={() => void createFiles(pickedGenerated)}
                  >
                    <FileOutputIcon />
                    Utwórz ponownie ({pickedGenerated.length})
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={saving || change.isPending}
                    onClick={() => remove(pickedGenerated)}
                  >
                    <Trash2Icon />
                    Usuń z listy ({pickedGenerated.length})
                  </Button>
                </div>
              )}
            </div>
            <div className="max-h-[24vh] overflow-y-auto rounded-lg border">
              <ul aria-label="Cenówki już utworzone">{generated.map(row)}</ul>
            </div>
          </section>
        )}
        <datalist id={NAMES_LIST_ID}>
          {customNames.map((name) => (
            <option key={name} value={name} />
          ))}
        </datalist>
      </DialogContent>
    </Dialog>
  )
}
