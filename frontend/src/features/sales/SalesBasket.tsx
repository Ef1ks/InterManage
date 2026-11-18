import { useMemo, useState } from 'react'
import { SettingsIcon, ShoppingBasketIcon, ShoppingCartIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'
import { Link } from 'react-router'

import { SegmentedField } from '@/components/form'
import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { useCan } from '@/features/auth/api'
import { MAX_QUANTITY, ORDER_EAN } from '@/features/data-entry/order-file'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import { useArticleDeliveries, useSalesArticle } from '@/features/sales/api'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { ApiError, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import { cn } from '@/lib/utils'
import { useAppDispatch, useAppSelector } from '@/store'
import {
  addBasketLine,
  type BasketLine,
  type ComparisonSettings,
  MAX_BASKET_SUPPLIERS,
  setBasket,
  setBasketQuantity,
  setComparisonSettings,
} from '@/store/salesBasketSlice'

/** Baza: 538 towar, 88110 alkohol. Zamówienie do bazy idzie plikiem do programu sklepu, nie Excelem. */
const BASE_CODES = new Set(['538', '88110'])
const BASE_NAMES: Record<string, string> = { '538': 'Baza', '88110': 'Baza alkohol' }
/** Rejony alkoholu: z bazy przychodzi on od dostawcy 88110. */
const ALCOHOL_REGIONS = new Set(['70', '71', '72', '73'])
/** Dostawcy z ostatniego roku. */
const SUPPLIER_DAYS = 365

const EMPTY: BasketLine[] = []

const normalizeCode = (code: string | null) => (code ?? '').trim().replace(/^0+(?=\d)/, '')
const supplierKey = (line: Pick<BasketLine, 'supplierCode' | 'supplierName'>) =>
  line.supplierCode || `nazwa:${line.supplierName}`

export function useBasket(storeId: string) {
  return useAppSelector((state) => state.salesBasket.baskets[storeId] ?? EMPTY)
}

export function useComparisonSettings() {
  return useAppSelector((state) => state.salesBasket.settings)
}

/** Artykuł z porównania, który dodajemy do koszyka. */
export type BasketArticle = { itm8: string; name: string | null; ean: string | null; brand: string | null }

type SupplierOption = {
  key: string
  code: string
  name: string
  base: boolean
  price: number | null
  lastDate: string | null
  deliveries: number
  /** Baza z cadencier bez dostawy w ostatnim roku: cena z kartoteki. */
  fromCatalog: boolean
}

const daysAgo = (days: number) => {
  const date = new Date()
  date.setDate(date.getDate() - days)
  return date.toISOString().slice(0, 10)
}

/** Okno „Dodaj do koszyka”: dostawcy artykułu z ostatniego roku z ceną zakupu z ostatniej dostawy od każdego z nich. */
export function AddToBasketDialog({
  storeId,
  article,
  onClose,
}: {
  storeId: string
  article: BasketArticle | null
  onClose: () => void
}) {
  return (
    <Dialog open={article != null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="gap-4 p-6 sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Dodaj do koszyka</DialogTitle>
          <DialogDescription>
            {article?.name ?? 'Artykuł'}
            {article && ` · ITM8 ${article.itm8}`}
            {article?.ean && ` · EAN ${article.ean}`}
          </DialogDescription>
        </DialogHeader>
        {article && <SupplierChoice key={article.itm8} storeId={storeId} article={article} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  )
}

function SupplierChoice({ storeId, article, onDone }: { storeId: string; article: BasketArticle; onDone: () => void }) {
  const dispatch = useAppDispatch()
  const basket = useBasket(storeId)
  const details = useSalesArticle(storeId, article.itm8)
  const deliveries = useArticleDeliveries(storeId, article.itm8)
  const [chosen, setChosen] = useState<string | null>(null)
  const [quantityText, setQuantityText] = useState('1')

  const catalog = details.data ?? null
  const cadencier = catalog?.cadencier ?? false
  const ean = catalog?.ean || article.ean
  const options = useMemo(() => {
    const since = daysAgo(SUPPLIER_DAYS)
    const byKey = new Map<string, SupplierOption>()
    for (const delivery of deliveries.data ?? []) {
      if (delivery.documentDate < since || (!delivery.supplierCode && !delivery.supplierName)) continue
      const code = normalizeCode(delivery.supplierCode)
      const key = code || `nazwa:${delivery.supplierName}`
      const known = byKey.get(key)
      if (known) {
        known.deliveries += 1
        continue
      }
      // Dostawy są od najnowszej, więc pierwsza od dostawcy niesie jego ostatnią cenę.
      byKey.set(key, {
        key,
        code,
        name: delivery.supplierName ?? BASE_NAMES[code] ?? `Dostawca ${code}`,
        base: BASE_CODES.has(code),
        price: delivery.purchasePrice,
        lastDate: delivery.documentDate,
        deliveries: 1,
        fromCatalog: false,
      })
    }
    const list = [...byKey.values()]
    // Artykuł z cadencier zamawia się z bazy, nawet gdy w ostatnim roku przyszedł tylko od innych.
    if (cadencier && !list.some((option) => option.base)) {
      const region = catalog?.nomenclaturePath[0]?.code.slice(0, 2) ?? ''
      const code = ALCOHOL_REGIONS.has(region) ? '88110' : '538'
      list.push({
        key: code,
        code,
        name: BASE_NAMES[code],
        base: true,
        price: catalog?.purchasePrice ?? null,
        lastDate: null,
        deliveries: 0,
        fromCatalog: true,
      })
    }
    return list.sort((a, b) => {
      if (cadencier && a.base !== b.base) return a.base ? -1 : 1
      return (b.lastDate ?? '').localeCompare(a.lastDate ?? '')
    })
  }, [deliveries.data, cadencier, catalog])

  const selected = options.find((option) => option.key === (chosen ?? options[0]?.key)) ?? null
  const quantity = Number(quantityText.replace(',', '.'))
  const baseWithoutEan = selected?.base && !(ean && ORDER_EAN.test(ean))
  const quantityError = !selected
    ? null
    : selected.base
      ? Number.isInteger(quantity) && quantity >= 1 && quantity <= MAX_QUANTITY
        ? null
        : `Do bazy zamawia się kartony: liczba całkowita od 1 do ${MAX_QUANTITY}.`
      : quantity > 0 && Math.round(quantity * 1000) === quantity * 1000
        ? null
        : 'Ilość większa od zera, do 3 miejsc po przecinku.'
  const suppliersInBasket = new Set(basket.map(supplierKey))
  const tooManySuppliers =
    selected != null &&
    !suppliersInBasket.has(selected.code || `nazwa:${selected.name}`) &&
    suppliersInBasket.size >= MAX_BASKET_SUPPLIERS

  const add = () => {
    if (!selected) return
    dispatch(
      addBasketLine({
        storeId,
        line: {
          itm8: article.itm8,
          ean: ean ?? null,
          name: catalog?.name ?? article.name,
          brand: catalog?.brand ?? article.brand,
          supplierCode: selected.code,
          supplierName: selected.name,
          base: selected.base,
          purchasePrice: selected.price,
          quantity,
        },
      }),
    )
    toast.success('Dodano do koszyka', {
      description: `${catalog?.name ?? article.name ?? article.itm8} · ${selected.code ? `${selected.code} ` : ''}${selected.name}`,
    })
    onDone()
  }

  if (deliveries.isPending || details.isPending) {
    return <div aria-hidden className="h-40 loading-shimmer rounded-lg" />
  }

  return (
    <>
      {cadencier && (
        <p className="rounded-lg bg-red-200 px-3 py-2 text-sm font-medium text-red-950 dark:bg-red-950/70 dark:text-red-100">
          Obecny w cadencier: zamawiaj z bazy
        </p>
      )}
      {options.length === 0 ? (
        <p className="rounded-lg border px-3 py-6 text-center text-sm text-muted-foreground">
          Artykuł nie miał dostaw w ostatnim roku, więc nie ma od kogo go zamówić.
        </p>
      ) : (
        <ul className="max-h-[50vh] divide-y overflow-y-auto rounded-lg border" role="radiogroup">
          {options.map((option) => {
            const active = option.key === selected?.key
            return (
              <li key={option.key}>
                <label
                  className={cn(
                    'flex cursor-pointer items-center gap-3 px-3 py-2 text-sm',
                    active ? 'bg-muted' : 'hover:bg-muted/60',
                  )}
                >
                  <input
                    type="radio"
                    name="basket-supplier"
                    className="size-4 shrink-0 accent-primary"
                    checked={active}
                    onChange={() => setChosen(option.key)}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">
                        {option.code && <span className="text-muted-foreground tabular-nums">{option.code} </span>}
                        {option.name}
                      </span>
                      {option.base && (
                        <span className="rounded bg-sky-200 px-1.5 py-0.5 text-xs font-medium text-sky-950 dark:bg-sky-900/60 dark:text-sky-100">
                          baza · plik zamówienia
                        </span>
                      )}
                    </span>
                    <span className="block text-xs text-muted-foreground">
                      {option.fromCatalog
                        ? 'bez dostawy w ostatnim roku, cena zakupu z kartoteki'
                        : `ostatnia dostawa ${formatDocumentDate(option.lastDate!)} · dostaw w roku: ${option.deliveries}`}
                    </span>
                  </span>
                  <span className="shrink-0 text-right tabular-nums">
                    {option.price == null ? (
                      <span className="text-muted-foreground">brak ceny</span>
                    ) : (
                      <>
                        <span className="block font-medium">{formatMoney(option.price)} zł</span>
                        <span className="block text-xs text-muted-foreground">netto</span>
                      </>
                    )}
                  </span>
                </label>
              </li>
            )
          })}
        </ul>
      )}

      {selected && (
        <div className="flex flex-wrap items-end justify-between gap-3">
          <label className="grid gap-1 text-sm">
            <span className="text-muted-foreground">{selected.base ? 'Kartony' : 'Ilość'}</span>
            <Input
              autoFocus
              inputMode="decimal"
              className="h-9 w-28 text-right tabular-nums"
              value={quantityText}
              aria-invalid={quantityError != null}
              onFocus={(event) => event.target.select()}
              onChange={(event) => setQuantityText(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !quantityError && !baseWithoutEan && !tooManySuppliers) add()
              }}
            />
          </label>
          <Button size="lg" disabled={quantityError != null || baseWithoutEan || tooManySuppliers} onClick={add}>
            <ShoppingBasketIcon />
            Dodaj do koszyka
          </Button>
          {(quantityError || baseWithoutEan || tooManySuppliers) && (
            <p className="w-full text-sm text-destructive">
              {baseWithoutEan
                ? 'Artykuł bez EAN-u nie trafi do pliku zamówienia z bazy.'
                : tooManySuppliers
                  ? `W koszyku może być najwyżej ${MAX_BASKET_SUPPLIERS} dostawców. Zamów albo usuń któregoś.`
                  : quantityError}
            </p>
          )}
        </div>
      )}
    </>
  )
}

type SupplierGroup = { key: string; code: string; name: string; base: boolean; lines: BasketLine[] }

function groupBasket(lines: BasketLine[]): SupplierGroup[] {
  const groups = new Map<string, SupplierGroup>()
  for (const line of lines) {
    const key = supplierKey(line)
    const group = groups.get(key) ?? {
      key,
      code: line.supplierCode,
      name: line.supplierName,
      base: line.base,
      lines: [],
    }
    group.lines.push(line)
    groups.set(key, group)
  }
  // Baza na górze, bo jej zamówienie idzie od razu plikiem.
  return [...groups.values()].sort((a, b) => Number(b.base) - Number(a.base) || a.name.localeCompare(b.name, 'pl'))
}

const lineValue = (line: BasketLine) => (line.purchasePrice == null ? 0 : line.purchasePrice * line.quantity)

/** Przycisk koszyka przyklejony do dołu ekranu; otwiera koszyk z dostawcami. */
export function BasketButton({ storeId }: { storeId: string }) {
  const basket = useBasket(storeId)
  const [open, setOpen] = useState(false)
  const suppliers = new Set(basket.map(supplierKey)).size
  return (
    <>
      {basket.length > 0 && (
        <div className="pointer-events-none fixed right-4 bottom-4 z-40 sm:right-6 sm:bottom-6">
          <Button size="xl" className="pointer-events-auto shadow-lg" onClick={() => setOpen(true)}>
            <ShoppingCartIcon />
            Koszyk: {basket.length} {basket.length === 1 ? 'artykuł' : 'art.'} · {suppliers}{' '}
            {suppliers === 1 ? 'dostawca' : 'dostawców'}
          </Button>
        </div>
      )}
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-4 p-6 sm:max-w-4xl">
          <DialogHeader>
            <DialogTitle>Koszyk zakupowy</DialogTitle>
            <DialogDescription>
              Zamówienie z bazy zapisuje się plikiem w folderze protokołów, skąd wczyta je program sklepu. Dla
              pozostałych dostawców pobiera się plik Excel.
            </DialogDescription>
          </DialogHeader>
          {open && <BasketContent storeId={storeId} onEmpty={() => setOpen(false)} />}
        </DialogContent>
      </Dialog>
    </>
  )
}

function BasketContent({ storeId, onEmpty }: { storeId: string; onEmpty: () => void }) {
  const dispatch = useAppDispatch()
  const basket = useBasket(storeId)
  const settings = useComparisonSettings()
  const folder = useProtoFolder(storeId)
  const [busy, setBusy] = useState<string | null>(null)
  const groups = groupBasket(basket)
  const baseGroups = groups.filter((group) => group.base)
  const otherGroups = groups.filter((group) => !group.base)

  const replace = (lines: BasketLine[]) => dispatch(setBasket({ storeId, lines }))

  /** Zdejmuje zamówione albo usunięte pozycje z koszyka; „Przywróć” oddaje cały poprzedni koszyk. */
  const removeLines = (removed: BasketLine[], message: string, description?: string) => {
    const before = basket
    const next = basket.filter((line) => !removed.includes(line))
    replace(next)
    toast.success(message, {
      description,
      duration: 10_000,
      action: { label: 'Przywróć', onClick: () => replace(before) },
    })
    if (next.length === 0) onEmpty()
  }

  const badBaseLine = (line: BasketLine) =>
    !(line.ean && ORDER_EAN.test(line.ean)) ||
    !Number.isInteger(line.quantity) ||
    line.quantity < 1 ||
    line.quantity > MAX_QUANTITY

  const orderBase = async () => {
    const lines = baseGroups.flatMap((group) => group.lines)
    setBusy('base')
    try {
      // Zgoda na folder zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(folder.key)
      // Ten sam EAN u 538 i 88110 to w pliku jedna linia: program sklepu sam rozdziela bazę.
      const byEan = new Map<string, { ean: string; quantity: number }>()
      for (const line of lines) {
        const key = line.ean!.replace(/^0+/, '')
        const known = byEan.get(key)
        if (known) known.quantity += line.quantity
        else byEan.set(key, { ean: line.ean!, quantity: line.quantity })
      }
      const { fileName, blob } = await fetchPostedFile(
        `/stores/${storeId}/reports/zero-stock/order`,
        { lines: [...byEan.values()] },
        'COMMANDE.traite0',
      )
      if (folderReady) await saveToFolder(folder.key, fileName, blob)
      else saveBlob(blob, fileName)
      removeLines(
        lines,
        'Zapisano zamówienie z bazy',
        [`Pozycji: ${byEan.size}`, !folderReady && 'pobrany: folder protokołów nie jest wybrany w tej przeglądarce']
          .filter(Boolean)
          .join(', '),
      )
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zamówienia z bazy')
    } finally {
      setBusy(null)
    }
  }

  const excelBody = (chosen: SupplierGroup[]) => ({
    suppliers: chosen.map((group) => ({
      code: group.code || null,
      name: group.name,
      lines: group.lines.map((line) => ({
        itm8: line.itm8,
        ean: line.ean,
        name: line.name,
        brand: line.brand,
        quantity: line.quantity,
        purchasePrice: line.purchasePrice,
      })),
    })),
  })

  const downloadExcel = async (chosen: SupplierGroup[], key: string) => {
    setBusy(key)
    try {
      const batches = settings.basketFiles === 'single' ? [chosen] : chosen.map((group) => [group])
      for (const batch of batches) {
        const { fileName, blob } = await fetchPostedFile(
          `/stores/${storeId}/reports/basket/xlsx`,
          excelBody(batch),
          'zamowienie.xlsx',
        )
        saveBlob(blob, fileName)
      }
      removeLines(
        chosen.flatMap((group) => group.lines),
        batches.length === 1 ? 'Pobrano zamówienie' : `Pobrano ${batches.length} zamówienia`,
        chosen.map((group) => group.name).join(', '),
      )
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować pliku Excel')
    } finally {
      setBusy(null)
    }
  }

  const baseBlocked = baseGroups.some((group) => group.lines.some(badBaseLine))

  return (
    <>
      <div className="max-h-[60vh] space-y-4 overflow-y-auto pr-1">
        {groups.map((group) => (
          <section key={group.key} className="rounded-lg border">
            <header className="flex flex-wrap items-center justify-between gap-2 border-b bg-muted/60 px-3 py-2">
              <p className="flex flex-wrap items-center gap-2 font-medium">
                {group.code && <span className="text-muted-foreground tabular-nums">{group.code}</span>}
                {group.name}
                {group.base && (
                  <span className="rounded bg-sky-200 px-1.5 py-0.5 text-xs font-medium text-sky-950 dark:bg-sky-900/60 dark:text-sky-100">
                    baza
                  </span>
                )}
              </p>
              <div className="flex items-center gap-3 text-sm tabular-nums">
                <span className="text-muted-foreground">
                  {group.lines.length} art. · {formatMoney(group.lines.reduce((sum, line) => sum + lineValue(line), 0))}{' '}
                  zł netto
                </span>
                {!group.base && (
                  <Button
                    size="sm"
                    variant="outline"
                    loading={busy === group.key}
                    disabled={busy != null}
                    onClick={() => void downloadExcel([group], group.key)}
                  >
                    Pobierz Excel
                  </Button>
                )}
              </div>
            </header>
            <ul className="divide-y">
              {group.lines.map((line) => (
                <BasketRow
                  key={line.itm8}
                  storeId={storeId}
                  line={line}
                  invalid={group.base && badBaseLine(line)}
                  onRemove={() => removeLines([line], 'Usunięto z koszyka', line.name ?? line.itm8)}
                />
              ))}
            </ul>
          </section>
        ))}
      </div>

      <div className="space-y-3 border-t pt-4">
        {baseGroups.length > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-3">
            <SavedFolder folder={folder} className="min-w-0 flex-1" />
            <div className="flex flex-col items-end gap-1">
              <Button
                size="lg"
                loading={busy === 'base'}
                disabled={busy != null || baseBlocked}
                onClick={() => void orderBase()}
              >
                <ShoppingCartIcon />
                Zamów z bazy ({baseGroups.reduce((sum, group) => sum + group.lines.length, 0)})
              </Button>
              {baseBlocked && (
                <p className="text-sm text-destructive">Do bazy: artykuł z EAN-em i od 1 do {MAX_QUANTITY} kartonów.</p>
              )}
            </div>
          </div>
        )}
        {otherGroups.length > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-muted-foreground">
              {settings.basketFiles === 'single'
                ? 'Wszyscy dostawcy w jednym pliku, każdy w swojej sekcji.'
                : 'Osobny plik dla każdego dostawcy.'}{' '}
              Zmienisz to w ustawieniach raportu.
            </p>
            <Button
              size="lg"
              variant="outline"
              loading={busy === 'excel'}
              disabled={busy != null}
              onClick={() => void downloadExcel(otherGroups, 'excel')}
            >
              Pobierz Excel dla dostawców ({otherGroups.length})
            </Button>
          </div>
        )}
      </div>
    </>
  )
}

function BasketRow({
  storeId,
  line,
  invalid,
  onRemove,
}: {
  storeId: string
  line: BasketLine
  invalid: boolean
  onRemove: () => void
}) {
  const dispatch = useAppDispatch()
  const [text, setText] = useState(String(line.quantity).replace('.', ','))
  const commit = () => {
    const quantity = Number(text.replace(',', '.'))
    if (quantity > 0 && quantity !== line.quantity) {
      dispatch(
        setBasketQuantity({
          storeId,
          itm8: line.itm8,
          supplierCode: line.supplierCode,
          supplierName: line.supplierName,
          quantity: Math.round(quantity * 1000) / 1000,
        }),
      )
    } else {
      setText(String(line.quantity).replace('.', ','))
    }
  }
  return (
    <li className="flex items-center gap-3 px-3 py-1.5 text-sm">
      <span className="min-w-0 flex-1">
        <span className="block truncate" title={line.name ?? undefined}>
          {line.name ?? 'Brak w kartotece'}
          {line.brand && <span className="text-muted-foreground"> · {line.brand}</span>}
        </span>
        <span className="block text-xs text-muted-foreground tabular-nums">
          ITM8 {line.itm8}
          {line.ean ? ` · EAN ${line.ean}` : ' · bez EAN-u'}
        </span>
      </span>
      <span className="w-24 shrink-0 text-right text-muted-foreground tabular-nums">
        {line.purchasePrice == null ? 'brak ceny' : `${formatMoney(line.purchasePrice)} zł`}
      </span>
      <Input
        aria-label={`${line.base ? 'Kartony' : 'Ilość'}: ${line.name ?? line.itm8}`}
        inputMode="decimal"
        className="h-8 w-20 shrink-0 text-right tabular-nums"
        value={text}
        aria-invalid={invalid}
        onFocus={(event) => event.target.select()}
        onChange={(event) => setText(event.target.value)}
        onBlur={commit}
        onKeyDown={(event) => event.key === 'Enter' && commit()}
      />
      <span className="w-24 shrink-0 text-right font-medium tabular-nums">
        {line.purchasePrice == null ? '–' : `${formatMoney(lineValue(line))} zł`}
      </span>
      <Button variant="ghost" size="icon-sm" aria-label="Usuń z koszyka" title="Usuń z koszyka" onClick={onRemove}>
        <Trash2Icon />
      </Button>
    </li>
  )
}

/** Ustawienia porównania sprzedaży: kolejność wierszy i pliki zamówień z koszyka (zapamiętane w przeglądarce). */
export function ComparisonSettingsButton() {
  const dispatch = useAppDispatch()
  const settings = useComparisonSettings()
  const canEditBudgets = useCan('SALES', 'EDIT')
  const [open, setOpen] = useState(false)
  const update = (next: Partial<ComparisonSettings>) => dispatch(setComparisonSettings(next))
  return (
    <>
      <Button
        variant="outline"
        size="icon"
        aria-label="Ustawienia raportu"
        title="Ustawienia raportu"
        onClick={() => setOpen(true)}
      >
        <SettingsIcon />
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>Ustawienia porównania sprzedaży</DialogTitle>
            <DialogDescription>Zapamiętane w tej przeglądarce.</DialogDescription>
          </DialogHeader>
          <SegmentedField
            label="Kolejność w tabeli"
            options={[
              { value: 'default', label: 'Sprzedaż' },
              { value: 'decline', label: 'Najpierw spadki' },
              { value: 'growth', label: 'Najpierw wzrosty' },
            ]}
            value={settings.sort}
            onChange={(sort) => update({ sort })}
          />
          <p className="-mt-3 text-sm text-muted-foreground">
            Na każdym poziomie nomenklatury, według zmiany sprzedaży w zł wobec okresu porównania.
          </p>
          <SegmentedField
            label="Zamówienia dla dostawców spoza bazy"
            options={[
              { value: 'perSupplier', label: 'Plik na dostawcę' },
              { value: 'single', label: 'Wszyscy w jednym pliku' },
            ]}
            value={settings.basketFiles}
            onChange={(basketFiles) => update({ basketFiles })}
          />
          <p className="text-sm text-muted-foreground">
            Założona marża rejonów (tabela nad raportem) to budżet rejonów, osobny od marży wymaganej na nomenklaturze.{' '}
            {canEditBudgets ? (
              <Link
                to={storeSettingsPaths.budgets}
                className="font-medium text-foreground underline underline-offset-4"
              >
                Ustaw ją w Ustawieniach sklepu
              </Link>
            ) : (
              'Ustawia ją osoba z prawem zmiany raportów sprzedaży.'
            )}
          </p>
        </DialogContent>
      </Dialog>
    </>
  )
}
