import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { ChevronDownIcon, FileImageIcon, PlusIcon, SparklesIcon, Trash2Icon, XIcon } from 'lucide-react'
import { toast } from 'sonner'

import { DateField, TextField } from '@/components/form'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import {
  fetchBakeryArticle,
  useAttachWzScan,
  useDeleteWz,
  useDetachWzScan,
  useReadWzScan,
  useRestoreWz,
  useRestoreWzScan,
  useSaveWz,
  wzScanUrl,
  type WzPayload,
} from '@/features/bakery-check/api'
import { CatalogListDialog } from '@/features/data-entry/CatalogListDialog'
import { formatQuantity } from '@/features/documents/format'
import { prepareScan } from '@/features/meat/format'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ApiError } from '@/lib/api'
import { formatShortDate } from '@/lib/dates'
import { matchesSearch } from '@/lib/search'
import type { BakeryArticle, BakeryScanReading, BakeryWz } from '@/lib/types'
import { cn } from '@/lib/utils'

const SAVE_DELAY = 600

const quantityInput =
  'h-8 w-20 shrink-0 rounded-md border border-input bg-background px-2 text-right tabular-nums outline-none transition-[border-color,box-shadow] duration-150 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30 aria-invalid:border-destructive dark:bg-input/30'

/** Ilość z pola: przecinek albo kropka, do 3 miejsc; `undefined` = nie liczba, `null` = puste. */
function parseQuantity(text: string): number | null | undefined {
  const value = text.trim().replace(',', '.')
  if (value === '') return null
  return /^\d{1,9}(\.\d{1,3})?$/.test(value) ? Number(value) : undefined
}

const errorText = (failure: unknown, fallback: string) => (failure instanceof ApiError ? failure.message : fallback)

/**
 * Kafelek jednego WZ. Ilości są tu, a nie w danych z serwera: zapisują się same chwilę po wpisaniu, a odświeżenie
 * weryfikacji nie może przerwać pisania. Układ jak kartka: artykuły alfabetycznie w dwóch kolumnach, Enter idzie w dół.
 */
export function WzCard({
  storeId,
  checkId,
  wz,
  articles,
  scanReading,
  defaultOpen,
}: {
  storeId: string
  checkId: string
  wz: BakeryWz
  articles: BakeryArticle[]
  scanReading: boolean
  defaultOpen: boolean
}) {
  const [open, setOpen] = useState(defaultOpen)
  const [number, setNumber] = useState(wz.number ?? '')
  const [date, setDate] = useState(wz.deliveryDate ?? '')
  const [estimated, setEstimated] = useState(wz.dateEstimated)
  const [quantities, setQuantities] = useState<Record<string, string>>(() =>
    Object.fromEntries(
      wz.lines.filter((line) => line.quantity !== null).map((line) => [line.article.productId, String(line.quantity)]),
    ),
  )
  const [scanned, setScanned] = useState<Record<string, number>>(() =>
    Object.fromEntries(
      wz.lines
        .filter((line) => line.scannedQuantity !== null)
        .map((line) => [line.article.productId, line.scannedQuantity as number]),
    ),
  )
  const [manual, setManual] = useState<BakeryArticle[]>(() =>
    wz.lines.filter((line) => line.manual).map((line) => line.article),
  )
  const [scan, setScan] = useState({ id: wz.scanId, name: wz.scanFileName })
  const [unmatched, setUnmatched] = useState<BakeryScanReading['lines']>([])
  const [filter, setFilter] = useState('')
  const [onlyFilled, setOnlyFilled] = useState(false)
  const [catalogOpen, setCatalogOpen] = useState(false)
  const list = useRef<HTMLDivElement>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  const save = useSaveWz(storeId, checkId, wz.id)
  const remove = useDeleteWz(storeId, checkId)
  const restore = useRestoreWz(storeId, checkId)
  const attachScan = useAttachWzScan(storeId, checkId, wz.id)
  const detachScan = useDetachWzScan(storeId, checkId, wz.id)
  const restoreScan = useRestoreWzScan(storeId, checkId, wz.id)
  const readScan = useReadWzScan(storeId, checkId, wz.id)

  const manualIds = useMemo(() => new Set(manual.map((article) => article.productId)), [manual])
  const rows = useMemo(() => {
    const known = new Map(articles.map((article) => [article.productId, article]))
    wz.lines.forEach((line) => known.set(line.article.productId, line.article))
    manual.forEach((article) => known.set(article.productId, article))
    return [...known.values()].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? '', 'pl'))
  }, [articles, wz.lines, manual])

  const invalid = Object.values(quantities).some((value) => parseQuantity(value) === undefined)
  const filled = Object.entries(quantities).filter(([, value]) => {
    const quantity = parseQuantity(value)
    return quantity !== null && quantity !== undefined && quantity !== 0
  })
  const total = filled.reduce((sum, [, value]) => sum + (parseQuantity(value) ?? 0), 0)

  const payload = (): WzPayload => ({
    number: number.trim() || null,
    deliveryDate: date || null,
    lines: rows
      .filter((row) => manualIds.has(row.productId) || quantities[row.productId])
      .map((row) => ({
        productId: row.productId,
        quantity: parseQuantity(quantities[row.productId] ?? '') ?? null,
        scannedQuantity: scanned[row.productId] ?? null,
        manual: manualIds.has(row.productId),
      })),
  })

  // Najnowszy zapis do wysłania; wyjście ze strony wysyła go od razu.
  const pending = useRef<WzPayload | null>(null)
  const timer = useRef<number | undefined>(undefined)
  const lastSaved = useRef<string | null>(null)
  const saveRef = useRef(save.mutate)
  saveRef.current = save.mutate

  useEffect(() => {
    // Bez zmian (wejście na stronę, cofnięcie wpisu) nie ma czego zapisywać.
    const next = payload()
    const serialized = JSON.stringify(next)
    lastSaved.current ??= serialized
    if (invalid || serialized === lastSaved.current) {
      pending.current = null
      window.clearTimeout(timer.current)
      return
    }
    pending.current = next
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => {
      if (pending.current) {
        lastSaved.current = JSON.stringify(pending.current)
        saveRef.current(pending.current)
      }
      pending.current = null
    }, SAVE_DELAY)
    // payload() składa się z tych wartości.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [number, date, quantities, scanned, manual, invalid])

  useEffect(
    () => () => {
      window.clearTimeout(timer.current)
      if (pending.current) saveRef.current(pending.current)
      pending.current = null
    },
    [],
  )

  const visible = rows.filter(
    (row) =>
      matchesSearch([row.name, row.brand, row.ean, row.itm8], filter) && (!onlyFilled || quantities[row.productId]),
  )

  const inputs = () => [...(list.current?.querySelectorAll<HTMLInputElement>('input[data-quantity]') ?? [])]
  const moveFocus = (event: KeyboardEvent<HTMLInputElement>) => {
    const step =
      event.key === 'ArrowUp' || (event.key === 'Enter' && event.shiftKey)
        ? -1
        : event.key === 'ArrowDown' || event.key === 'Enter'
          ? 1
          : 0
    if (step === 0) return
    event.preventDefault()
    const all = inputs()
    const next = all[all.indexOf(event.currentTarget) + step]
    next?.focus()
    next?.select()
  }

  const addArticle = async (itm8: string) => {
    setCatalogOpen(false)
    try {
      const article = await fetchBakeryArticle(storeId, itm8)
      setManual((current) =>
        current.some((item) => item.productId === article.productId) ? current : [...current, article],
      )
      setOnlyFilled(false)
      setFilter('')
      window.setTimeout(() => {
        const input = list.current?.querySelector<HTMLInputElement>(`input[data-quantity="${article.productId}"]`)
        input?.focus()
      })
    } catch (failure) {
      toast.error(errorText(failure, 'Nie udało się dodać artykułu'))
    }
  }

  const removeArticle = (productId: string) => {
    setManual((current) => current.filter((article) => article.productId !== productId))
    setQuantities(({ [productId]: _removed, ...rest }) => rest)
  }

  const deleteWz = () => {
    remove.mutate(wz.id, {
      onSuccess: () =>
        toast(`Usunięto WZ ${number || 'bez numeru'}`, {
          action: { label: 'Przywróć', onClick: () => restore.mutate(wz.id) },
        }),
      onError: (failure) => toast.error(errorText(failure, 'Nie udało się usunąć WZ')),
    })
  }

  const uploadScan = async (file: File) => {
    const prepared = await prepareScan(file)
    attachScan.mutate(prepared, {
      onSuccess: (saved) => setScan({ id: saved.scanId, name: saved.scanFileName }),
      onError: (failure) => toast.error(errorText(failure, 'Nie udało się wgrać skanu')),
    })
  }

  const removeScan = () => {
    const previous = scan
    detachScan.mutate(undefined, {
      onSuccess: () => {
        setScan({ id: null, name: null })
        toast('Odpięto skan', {
          action: {
            label: 'Przywróć',
            onClick: () => previous.id && restoreScan.mutate(previous.id, { onSuccess: () => setScan(previous) }),
          },
        })
      },
      onError: (failure) => toast.error(errorText(failure, 'Nie udało się odpiąć skanu')),
    })
  }

  /** Propozycja z odczytu trafia do pól: pracownik ją sprawdza, a zapis idzie jak po wpisaniu ręcznym. */
  const applyReading = () => {
    readScan.mutate(undefined, {
      onSuccess: (reading) => {
        const matched = reading.lines.filter((line) => line.productId !== null && line.quantity !== null)
        setQuantities((current) => ({
          ...current,
          ...Object.fromEntries(matched.map((line) => [line.productId as string, String(line.quantity)])),
        }))
        setScanned((current) => ({
          ...current,
          ...Object.fromEntries(matched.map((line) => [line.productId as string, line.quantity as number])),
        }))
        setUnmatched(reading.lines.filter((line) => line.productId === null))
        if (!number && reading.number) setNumber(reading.number)
        if (!date && reading.deliveryDate) setDate(reading.deliveryDate)
        toast.success(`Odczytano pozycji: ${matched.length}. Sprawdź je przed podsumowaniem.`)
      },
      onError: (failure) => toast.error(errorText(failure, 'Nie udało się odczytać skanu')),
    })
  }

  return (
    <article className="rounded-xl border bg-card">
      <div className="flex items-center gap-3 px-3 py-2 sm:px-4">
        <button
          type="button"
          className="flex min-h-10 min-w-0 flex-1 items-center gap-3 rounded-md text-left focus-visible:ring-3 focus-visible:ring-ring/30 focus-visible:outline-none"
          aria-expanded={open}
          onClick={() => setOpen((current) => !current)}
        >
          <ChevronDownIcon
            aria-hidden
            className={cn(
              'size-4 shrink-0 text-muted-foreground transition-transform duration-150 motion-reduce:transition-none',
              !open && '-rotate-90',
            )}
          />
          <span className="min-w-0">
            <span className="block truncate font-medium tabular-nums">WZ {number || 'bez numeru'}</span>
            <span className="block truncate text-xs text-muted-foreground tabular-nums">
              {[
                date ? `${estimated ? 'ok. ' : ''}${formatShortDate(date)}` : 'bez daty',
                filled.length > 0 ? `pozycji ${filled.length} · razem ${formatQuantity(total)}` : 'bez ilości',
              ].join(' · ')}
            </span>
          </span>
        </button>
        <span className="shrink-0 text-xs text-muted-foreground" aria-live="polite">
          {invalid ? (
            <span className="text-destructive">Popraw ilość</span>
          ) : save.isError ? (
            <button type="button" className="text-destructive underline" onClick={() => save.mutate(payload())}>
              Nie zapisano, ponów
            </button>
          ) : save.isPending ? (
            'Zapisywanie…'
          ) : save.isSuccess ? (
            'Zapisano'
          ) : null}
        </span>
        <Button variant="ghost" size="icon" onClick={deleteWz} aria-label={`Usuń WZ ${number}`} title="Usuń WZ">
          <Trash2Icon />
        </Button>
      </div>

      <div hidden={!open} className="border-t px-3 py-3 sm:px-4">
        <div className="grid gap-3 sm:grid-cols-[1fr_16rem]">
          <TextField label="Numer WZ" value={number} onChange={(event) => setNumber(event.target.value)} />
          <DateField
            label="Data dostawy"
            value={date}
            onChange={(value) => {
              setDate(value)
              setEstimated(false)
            }}
            hint={estimated ? 'Szacowana z okresu faktury' : undefined}
            shortcuts={[]}
          />
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <SearchInput
            value={filter}
            onChange={setFilter}
            placeholder="Szukaj w tym WZ"
            className="min-w-48 flex-1"
            onEnter={() => inputs()[0]?.focus()}
          />
          <Button variant={onlyFilled ? 'secondary' : 'outline'} size="sm" onClick={() => setOnlyFilled(!onlyFilled)}>
            Tylko wpisane
          </Button>
          <Button variant="outline" size="sm" onClick={() => setCatalogOpen(true)}>
            <PlusIcon />
            Dodaj artykuł
          </Button>
          <input
            ref={fileInput}
            type="file"
            hidden
            accept="image/*,application/pdf"
            onChange={(event) => {
              const file = event.target.files?.[0]
              event.target.value = ''
              if (file) void uploadScan(file)
            }}
          />
          {scan.id ? (
            <span className="flex items-center">
              <Button
                variant="outline"
                size="sm"
                render={<a href={wzScanUrl(storeId, checkId, wz.id)} target="_blank" rel="noreferrer" />}
                nativeButton={false}
              >
                <FileImageIcon />
                Skan
              </Button>
              <Button
                variant="ghost"
                size="icon-sm"
                onClick={removeScan}
                aria-label="Odepnij skan"
                title="Odepnij skan"
              >
                <XIcon />
              </Button>
            </span>
          ) : (
            <Button
              variant="outline"
              size="sm"
              onClick={() => fileInput.current?.click()}
              loading={attachScan.isPending}
            >
              <FileImageIcon />
              Dodaj skan
            </Button>
          )}
          {scanReading && scan.id && (
            <Button variant="outline" size="sm" onClick={applyReading} loading={readScan.isPending}>
              <SparklesIcon />
              Odczytaj ze skanu
            </Button>
          )}
        </div>

        {unmatched.length > 0 && (
          <div className="mt-3 rounded-lg bg-amber-200 px-3 py-2 text-sm text-amber-950 dark:bg-amber-900 dark:text-amber-50">
            Bez dopasowanego artykułu:{' '}
            {unmatched.map((line) => `${line.name ?? '?'} (${line.quantity ?? '?'})`).join(', ')}. Dodaj je ręcznie.
          </div>
        )}

        {rows.length === 0 ? (
          <p className="mt-3 rounded-lg border px-3 py-6 text-center text-sm text-muted-foreground">
            Na liście nie ma artykułów. Zaznacz je w „Artykuły na WZ” albo dodaj przyciskiem „Dodaj artykuł”.
          </p>
        ) : visible.length === 0 ? (
          <p className="mt-3 px-3 py-6 text-center text-sm text-muted-foreground">Nic nie pasuje do wyszukiwania.</p>
        ) : (
          <div ref={list} className="mt-3 gap-x-8 lg:columns-2">
            {visible.map((row) => {
              const value = quantities[row.productId] ?? ''
              const fromScan = scanned[row.productId] !== undefined && parseQuantity(value) === scanned[row.productId]
              return (
                <div key={row.productId} className="flex break-inside-avoid items-center gap-2 border-b py-1">
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm" title={row.name ?? undefined}>
                      {row.name ?? row.ean}
                    </span>
                    <span className="block truncate text-xs text-muted-foreground tabular-nums">
                      {[row.ean, manualIds.has(row.productId) && 'dopisany', fromScan && 'ze skanu']
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                  </span>
                  <ArticleStatsButton article={row} skipTab />
                  {manualIds.has(row.productId) && (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      onClick={() => removeArticle(row.productId)}
                      aria-label={`Usuń z WZ: ${row.name}`}
                      title="Usuń z tego WZ"
                    >
                      <XIcon />
                    </Button>
                  )}
                  <input
                    data-quantity={row.productId}
                    inputMode="decimal"
                    aria-label={`Ilość: ${row.name}`}
                    aria-invalid={parseQuantity(value) === undefined ? true : undefined}
                    className={cn(
                      quantityInput,
                      fromScan && 'bg-sky-200 text-sky-950 dark:bg-sky-900 dark:text-sky-50',
                    )}
                    value={value}
                    onChange={(event) =>
                      setQuantities((current) => ({ ...current, [row.productId]: event.target.value }))
                    }
                    onKeyDown={moveFocus}
                    onFocus={(event) => event.target.select()}
                  />
                </div>
              )
            })}
          </div>
        )}
      </div>

      {catalogOpen && (
        <CatalogListDialog
          storeId={storeId}
          open
          onOpenChange={setCatalogOpen}
          inTable={new Set(rows.map((row) => row.ean.replace(/^0+/, '')))}
          title="Dodaj artykuł do WZ"
          onPick={(item) => void addArticle(item.itm8)}
          lastSaleDate={null}
        />
      )}
    </article>
  )
}
