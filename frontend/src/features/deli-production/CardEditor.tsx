import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { ArrowUpIcon, ScanBarcodeIcon, Trash2Icon, XIcon } from 'lucide-react'
import { useBlocker, useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { DateField, TextField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import {
  cardPdfPath,
  productionPaths,
  useDeleteCard,
  useProductionBase,
  useProductionCard,
  useRestoreCard,
  useSaveCard,
} from '@/features/deli-production/api'
import { expiryDate, findByCode, matchesName, quantityFormat, randomStartHour } from '@/features/deli-production/format'
import { PdfButtons } from '@/features/deli-production/PdfButtons'
import { ApiError, printFile } from '@/lib/api'
import { formatShortDate, todayIso } from '@/lib/dates'
import type { ProductionArticle, ProductionCard, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

type Row = {
  key: string
  articleId: string
  name: string
  code: string | null
  quantity: string
  startHour: string
  shelfLifeDays: number | null
}

const cellInput =
  'h-9 w-full min-w-20 rounded-md border border-input bg-background px-2.5 text-right tabular-nums outline-none transition-[border-color,box-shadow] duration-150 focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/30 aria-invalid:border-destructive dark:bg-input/30'

/** Nowa karta albo zapisana: edycja w bieżącym sklepie, karta sklepu zaprzyjaźnionego tylko do podglądu. */
export function CardEditor({ store }: { store: StoreSummary }) {
  const { cardId } = useParams()
  const { data: card, isPending, isError } = useProductionCard(store.id, cardId)

  if (cardId && isPending) {
    return <p className="py-10 text-center text-sm text-muted-foreground">Wczytywanie karty…</p>
  }
  if (cardId && (isError || !card)) {
    return (
      <FullPageMessage
        title="Nie znaleziono karty"
        description="Karta nie istnieje albo należy do sklepu, który nie jest zaprzyjaźniony."
        inline
      />
    )
  }
  return <CardScreen key={card ? `${card.id}:${card.updatedAt}` : 'new'} store={store} card={card ?? null} />
}

function CardScreen({ store, card }: { store: StoreSummary; card: ProductionCard | null }) {
  const canEdit = useCan('DELI_PRODUCTION', 'EDIT')
  if (card && (!card.editable || !canEdit || card.deletedAt)) {
    return <CardView store={store} card={card} />
  }
  return <CardForm store={store} card={card} />
}

function CardHeading({ card }: { card: ProductionCard | null }) {
  return (
    <div className="min-w-0">
      <BackLink to={productionPaths.cards}>Karty produkcji</BackLink>
      <h1 className="mt-2 text-2xl font-semibold tracking-tight text-balance">
        {card ? `Karta produkcji · ${formatShortDate(card.productionDate)}` : 'Nowa karta produkcji'}
      </h1>
      {card && (
        <p className="mt-1 text-sm text-muted-foreground">
          {card.storeNumber} · {card.storeName}
          {card.createdBy && ` · zapisał(a) ${card.createdBy}`}
        </p>
      )}
    </div>
  )
}

/** Podgląd: karta sklepu zaprzyjaźnionego, usunięta albo bez uprawnienia do zmian. */
function CardView({ store, card }: { store: StoreSummary; card: ProductionCard }) {
  const canEdit = useCan('DELI_PRODUCTION', 'EDIT')
  const restore = useRestoreCard(store.id)

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <CardHeading card={card} />
        {!card.deletedAt && (
          <PdfButtons path={cardPdfPath(store.id, card.id)} fileName={`karta-produkcji-${card.productionDate}.pdf`} />
        )}
      </div>
      {card.deletedAt && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-300 bg-amber-100 px-4 py-3 text-sm text-amber-950 dark:border-amber-500/40 dark:bg-amber-950/60 dark:text-amber-100">
          <span>Ta karta jest usunięta.</span>
          {canEdit && card.editable && (
            <Button
              variant="outline"
              loading={restore.isPending}
              onClick={() =>
                restore.mutate(card.id, {
                  onSuccess: () => toast.success('Przywrócono kartę'),
                  onError: (error) =>
                    toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić karty'),
                })
              }
            >
              Przywróć
            </Button>
          )}
        </div>
      )}
      <div className="overflow-x-auto rounded-xl border bg-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-12 text-right">Lp.</TableHead>
              <TableHead>Asortyment</TableHead>
              <TableHead className="text-right">Ilość</TableHead>
              <TableHead className="text-right">Proces</TableHead>
              <TableHead className="text-right">Temperatury</TableHead>
              <TableHead className="text-right">Ważność</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {card.lines.map((line, index) => (
              <TableRow key={line.id}>
                <TableCell className="text-right text-muted-foreground tabular-nums">{index + 1}</TableCell>
                <TableCell>
                  <div className="font-medium">{line.name}</div>
                  {line.code && <div className="text-xs text-muted-foreground tabular-nums">{line.code}</div>}
                </TableCell>
                <TableCell className="text-right font-medium tabular-nums">
                  {quantityFormat.format(line.quantity)}
                </TableCell>
                <TableCell className="text-right tabular-nums">
                  {line.startHour}:00–{line.endHour}:00
                </TableCell>
                <TableCell className="text-right text-sm text-muted-foreground tabular-nums">
                  {[
                    line.bakingTemperature != null && `proces ${line.bakingTemperature}°C`,
                    line.internalTemperature != null && `wewn. ${line.internalTemperature}°C`,
                    line.coolingTemperature != null && `schł. ${line.coolingTemperature}°C`,
                  ]
                    .filter(Boolean)
                    .join(' · ') || '–'}
                </TableCell>
                <TableCell className="text-right tabular-nums">
                  {line.expiryDate ? formatShortDate(line.expiryDate) : '–'}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

function CardForm({ store, card }: { store: StoreSummary; card: ProductionCard | null }) {
  const navigate = useNavigate()
  const { data: base, isPending: basePending } = useProductionBase(store.id)
  const save = useSaveCard(store.id, card?.id)
  const remove = useDeleteCard(store.id)
  const restore = useRestoreCard(store.id)

  const [date, setDate] = useState(card?.productionDate ?? todayIso())
  const [rows, setRows] = useState<Row[]>(() =>
    (card?.lines ?? []).map((line) => ({
      key: line.id,
      articleId: line.articleId,
      name: line.name,
      code: line.code,
      quantity: String(line.quantity).replace('.', ','),
      startHour: String(line.startHour),
      shelfLifeDays: line.shelfLifeDays,
    })),
  )
  const [query, setQuery] = useState('')
  const [invalid, setInvalid] = useState<Set<string>>(new Set())
  const [flashKey, setFlashKey] = useState<string | null>(null)

  const scanInput = useRef<HTMLInputElement>(null)
  const quantityInputs = useRef(new Map<string, HTMLInputElement>())
  const hourInputs = useRef(new Map<string, HTMLInputElement>())
  const saveButton = useRef<HTMLButtonElement>(null)
  const leaving = useRef(false)

  const initial = useMemo(() => snapshot(card?.productionDate ?? todayIso(), rowsOf(card)), [card])
  const dirty = snapshot(date, rows) !== initial && (card != null || rows.length > 0)

  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      dirty && !leaving.current && currentLocation.pathname !== nextLocation.pathname,
  )
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  useEffect(() => {
    if (!flashKey) return
    const timer = window.setTimeout(() => setFlashKey(null), 900)
    return () => window.clearTimeout(timer)
  }, [flashKey])

  const articles = useMemo(() => base?.articles ?? [], [base])
  const shelfLife = useMemo(() => {
    const bySchema = new Map(base?.schemas.map((schema) => [schema.id, schema.shelfLifeDays]))
    return (article: ProductionArticle) => bySchema.get(article.schemaId) ?? null
  }, [base])

  const suggestions = useMemo(() => {
    const text = query.trim()
    if (text.length < 2 || /^\d+$/.test(text)) return []
    return articles.filter((article) => matchesName(article, text)).slice(0, 8)
  }, [articles, query])

  const add = (article: ProductionArticle, force = false) => {
    const existing = rows.find((row) => row.articleId === article.id)
    if (existing && !force) {
      setFlashKey(existing.key)
      toast.info(`${article.name} już jest na karcie`, {
        action: { label: 'Dodaj drugi raz', onClick: () => add(article, true) },
      })
      return
    }
    const row: Row = {
      key: crypto.randomUUID(),
      articleId: article.id,
      name: article.name,
      code: article.code,
      quantity: '',
      startHour: String(randomStartHour()),
      shelfLifeDays: shelfLife(article),
    }
    setRows((current) => [...current, row])
    setFlashKey(row.key)
  }

  const [highlight, setHighlight] = useState(0)
  const [listClosed, setListClosed] = useState(false)
  const listOpen = suggestions.length > 0 && !listClosed
  const active = Math.min(highlight, suggestions.length - 1)
  const listRef = useRef<HTMLUListElement>(null)

  useEffect(() => {
    listRef.current?.querySelector(`[data-index="${active}"]`)?.scrollIntoView({ block: 'nearest' })
  }, [active, listOpen])

  const changeQuery = (value: string) => {
    setQuery(value)
    setHighlight(0)
    setListClosed(false)
  }

  const onScan = (event: KeyboardEvent<HTMLInputElement>) => {
    if (listOpen && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
      event.preventDefault()
      const step = event.key === 'ArrowDown' ? 1 : -1
      setHighlight((active + step + suggestions.length) % suggestions.length)
      return
    }
    if (listOpen && event.key === 'Escape') {
      event.preventDefault()
      setListClosed(true)
      return
    }
    if (event.key !== 'Enter') return
    event.preventDefault()
    const text = query.trim()
    if (text === '') {
      focusFirstEmpty()
      return
    }
    // Wybrana strzałkami podpowiedź ma pierwszeństwo, bez wyboru zeskanowany kod przed pierwszą podpowiedzią.
    const article = listOpen && active > 0 ? suggestions[active] : (findByCode(articles, text) ?? suggestions[0])
    if (!article) {
      toast.error(`Nie ma w bazie artykułu „${text}”`, {
        description: 'Dodaj go w zakładce Baza artykułów albo wyszukaj po nazwie.',
      })
      return
    }
    add(article)
    changeQuery('')
  }

  const scrollToTop = () => {
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    window.scrollTo({ top: 0, behavior: reduced ? 'auto' : 'smooth' })
    scanInput.current?.focus({ preventScroll: true })
  }

  const focusFirstEmpty = () => {
    const target = rows.find((row) => row.quantity.trim() === '') ?? rows[0]
    if (target) quantityInputs.current.get(target.key)?.focus()
  }

  /** Enter / strzałka w dół: to samo pole w następnym wierszu; Shift+Enter / strzałka w górę: w poprzednim. */
  const moveFrom = (event: KeyboardEvent<HTMLInputElement>, index: number, inputs: Map<string, HTMLInputElement>) => {
    const down = (event.key === 'Enter' && !event.shiftKey) || event.key === 'ArrowDown'
    const up = (event.key === 'Enter' && event.shiftKey) || event.key === 'ArrowUp'
    if (!down && !up) return
    event.preventDefault()
    const next = rows[index + (down ? 1 : -1)]
    if (next) {
      const input = inputs.get(next.key)
      input?.focus()
      input?.select()
    } else if (down) {
      saveButton.current?.focus()
    }
  }

  const update = (key: string, patch: Partial<Row>) => {
    setRows((current) => current.map((row) => (row.key === key ? { ...row, ...patch } : row)))
    if (invalid.has(key)) {
      setInvalid((current) => {
        const copy = new Set(current)
        copy.delete(key)
        return copy
      })
    }
  }

  const removeRow = (row: Row) => {
    const index = rows.indexOf(row)
    setRows((current) => current.filter((item) => item.key !== row.key))
    toast(`Usunięto ${row.name} z karty`, {
      action: {
        label: 'Przywróć',
        onClick: () => setRows((current) => [...current.slice(0, index), row, ...current.slice(index)]),
      },
    })
  }

  const submit = () => {
    const wrong = new Set(
      rows
        .filter((row) => parseQuantity(row.quantity) == null || parseHour(row.startHour) == null)
        .map((row) => row.key),
    )
    setInvalid(wrong)
    if (rows.length === 0) {
      toast.error('Karta nie ma pozycji. Zeskanuj kody wyprodukowanych artykułów.')
      scanInput.current?.focus()
      return
    }
    if (wrong.size > 0) {
      toast.error(`Uzupełnij ilość i godzinę (${wrong.size})`)
      const first = rows.find((row) => wrong.has(row.key))
      if (first) {
        const input =
          parseQuantity(first.quantity) == null
            ? quantityInputs.current.get(first.key)
            : hourInputs.current.get(first.key)
        input?.focus()
      }
      return
    }
    save.mutate(
      {
        productionDate: date,
        lines: rows.map((row) => ({
          articleId: row.articleId,
          quantity: parseQuantity(row.quantity)!,
          startHour: parseHour(row.startHour)!,
        })),
      },
      {
        onSuccess: (saved) => {
          toast.success('Zapisano kartę produkcji', {
            action: {
              label: 'Drukuj',
              onClick: () =>
                printFile(cardPdfPath(store.id, saved.id)).catch((error) =>
                  toast.error(error instanceof ApiError ? error.message : 'Nie udało się wydrukować'),
                ),
            },
          })
          if (!card) {
            leaving.current = true
            navigate(productionPaths.card(saved.id), { replace: true })
          }
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać karty'),
      },
    )
  }

  const deleteCard = () => {
    if (!card) return
    remove.mutate(card.id, {
      onSuccess: () => {
        leaving.current = true
        navigate(productionPaths.cards)
        toast.success('Usunięto kartę', {
          action: { label: 'Przywróć', onClick: () => restore.mutate(card.id) },
        })
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć karty'),
    })
  }

  const filled = rows.filter((row) => parseQuantity(row.quantity) != null).length

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <CardHeading card={card} />
        <div className="flex flex-wrap items-center gap-2">
          {card && (
            <>
              <PdfButtons
                path={cardPdfPath(store.id, card.id)}
                fileName={`karta-produkcji-${card.productionDate}.pdf`}
              />
              <Button variant="ghost" loading={remove.isPending} onClick={deleteCard}>
                <Trash2Icon />
                Usuń
              </Button>
            </>
          )}
        </div>
      </div>

      <section className="grid gap-4 rounded-xl border bg-card p-5 md:grid-cols-[16rem_1fr]">
        <DateField label="Data produkcji" value={date} onChange={setDate} />
        <div className="relative">
          <TextField
            ref={scanInput}
            label="Zeskanuj kod albo wpisz nazwę"
            autoComplete="off"
            autoFocus={!card}
            value={query}
            onChange={(event) => changeQuery(event.target.value)}
            onKeyDown={onScan}
            onBlur={() => setListClosed(true)}
            onFocus={() => setListClosed(false)}
            role="combobox"
            aria-expanded={listOpen}
            aria-controls="production-article-suggestions"
            aria-activedescendant={listOpen ? `production-article-${suggestions[active].id}` : undefined}
            disabled={basePending}
            hint={
              base && base.articles.length === 0
                ? 'Baza nie ma artykułów: dodaj je w zakładce Baza artykułów.'
                : 'Enter dodaje artykuł; pusty Enter przechodzi do wpisywania ilości.'
            }
          />
          <ScanBarcodeIcon
            aria-hidden
            className="pointer-events-none absolute top-6 right-3.5 size-5 -translate-y-1/2 text-muted-foreground"
          />
          {listOpen && (
            <ul
              ref={listRef}
              id="production-article-suggestions"
              role="listbox"
              className="absolute inset-x-0 top-[3.25rem] z-20 max-h-72 overflow-y-auto rounded-lg border bg-popover p-1 shadow-lg"
            >
              {suggestions.map((article, index) => (
                <li
                  key={article.id}
                  id={`production-article-${article.id}`}
                  role="option"
                  aria-selected={index === active}
                >
                  <button
                    type="button"
                    tabIndex={-1}
                    data-index={index}
                    className={cn(
                      'flex w-full items-center justify-between gap-3 rounded-md px-2.5 py-2 text-left text-sm',
                      index === active ? 'bg-muted' : 'hover:bg-muted/60',
                    )}
                    onMouseDown={(event) => event.preventDefault()}
                    onMouseMove={() => index !== active && setHighlight(index)}
                    onClick={() => {
                      add(article)
                      changeQuery('')
                      scanInput.current?.focus()
                    }}
                  >
                    <span className="truncate">{article.name}</span>
                    {article.code && (
                      <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{article.code}</span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>

      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-base font-semibold">
            Pozycje{' '}
            <span className="font-normal text-muted-foreground tabular-nums">
              {rows.length > 0 ? `${filled} z ${rows.length} z ilością` : '0'}
            </span>
          </h2>
          {rows.length > 0 && (
            <Button variant="outline" onClick={focusFirstEmpty}>
              Wpisz ilości
            </Button>
          )}
        </div>

        <div className="overflow-x-auto rounded-xl border bg-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-12 text-right">Lp.</TableHead>
                <TableHead>Asortyment</TableHead>
                <TableHead className="w-32 text-right">Ilość</TableHead>
                <TableHead className="w-28 text-right">Rozpoczęcie</TableHead>
                <TableHead className="w-28 text-right">Ważność</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Usuń</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="py-12 text-center text-sm text-muted-foreground">
                    Zeskanuj kody z formularza. Artykuły pojawią się tutaj w kolejności skanowania.
                  </TableCell>
                </TableRow>
              )}
              {rows.map((row, index) => {
                const expiry = expiryDate(date, row.shelfLifeDays)
                return (
                  <TableRow
                    key={row.key}
                    className={cn(
                      'transition-colors duration-700',
                      flashKey === row.key && 'bg-sky-100 duration-0 dark:bg-sky-950/60',
                    )}
                  >
                    <TableCell className="text-right text-muted-foreground tabular-nums">{index + 1}</TableCell>
                    <TableCell>
                      <div className="font-medium">{row.name}</div>
                      {row.code && <div className="text-xs text-muted-foreground tabular-nums">{row.code}</div>}
                    </TableCell>
                    <TableCell>
                      <input
                        ref={(element) => {
                          if (element) quantityInputs.current.set(row.key, element)
                          else quantityInputs.current.delete(row.key)
                        }}
                        aria-label={`Ilość: ${row.name}`}
                        inputMode="decimal"
                        autoComplete="off"
                        aria-invalid={invalid.has(row.key) && parseQuantity(row.quantity) == null ? true : undefined}
                        className={cellInput}
                        value={row.quantity}
                        onChange={(event) => update(row.key, { quantity: event.target.value })}
                        onFocus={(event) => event.target.select()}
                        onKeyDown={(event) => moveFrom(event, index, quantityInputs.current)}
                      />
                    </TableCell>
                    <TableCell>
                      <div className="relative">
                        <input
                          ref={(element) => {
                            if (element) hourInputs.current.set(row.key, element)
                            else hourInputs.current.delete(row.key)
                          }}
                          aria-label={`Godzina rozpoczęcia: ${row.name}`}
                          inputMode="numeric"
                          autoComplete="off"
                          aria-invalid={invalid.has(row.key) && parseHour(row.startHour) == null ? true : undefined}
                          className={cn(cellInput, 'pr-9')}
                          value={row.startHour}
                          onChange={(event) => update(row.key, { startHour: event.target.value.replace(/\D/g, '') })}
                          onFocus={(event) => event.target.select()}
                          onKeyDown={(event) => moveFrom(event, index, hourInputs.current)}
                        />
                        <span className="pointer-events-none absolute top-1/2 right-2.5 -translate-y-1/2 text-sm text-muted-foreground">
                          :00
                        </span>
                      </div>
                    </TableCell>
                    <TableCell className="text-right text-sm tabular-nums">
                      {expiry ? formatShortDate(expiry) : <span className="text-muted-foreground">–</span>}
                    </TableCell>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="icon"
                        aria-label={`Usuń ${row.name} z karty`}
                        title="Usuń z karty"
                        onClick={() => removeRow(row)}
                      >
                        <XIcon />
                      </Button>
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </div>
      </section>

      <div className="sticky bottom-0 z-10 -mx-4 flex flex-wrap items-center justify-end gap-3 border-t bg-background/95 px-4 py-3 backdrop-blur supports-backdrop-filter:bg-background/80 md:-mx-6 md:px-6">
        <Button
          variant="outline"
          size="icon-lg"
          className="mr-auto size-12 rounded-xl"
          aria-label="Na górę"
          title="Na górę (do skanowania)"
          onClick={scrollToTop}
        >
          <ArrowUpIcon />
        </Button>
        {dirty && <span className="text-sm text-muted-foreground">Niezapisane zmiany</span>}
        <Button ref={saveButton} size="xl" loading={save.isPending} onClick={submit}>
          {card ? 'Zapisz zmiany' : 'Zapisz kartę'}
        </Button>
      </div>

      <AlertDialog open={blocker.state === 'blocked'} onOpenChange={(open) => !open && blocker.reset?.()}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Wyjść bez zapisywania?</AlertDialogTitle>
            <AlertDialogDescription>Zmiany na tej karcie nie zostały zapisane i przepadną.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Zostań</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={() => blocker.proceed?.()}>
              Wyjdź bez zapisu
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function rowsOf(card: ProductionCard | null) {
  return (card?.lines ?? []).map((line) => ({
    articleId: line.articleId,
    quantity: String(line.quantity).replace('.', ','),
    startHour: String(line.startHour),
  }))
}

function snapshot(date: string, rows: Pick<Row, 'articleId' | 'quantity' | 'startHour'>[]) {
  return JSON.stringify([date, rows.map((row) => [row.articleId, row.quantity.trim(), row.startHour.trim()])])
}

/** Ilość większa od zera, do 3 miejsc po przecinku; inaczej null. */
function parseQuantity(value: string) {
  const text = value.trim().replace(/\s/g, '').replace(',', '.')
  if (!/^\d+(\.\d{1,3})?$/.test(text)) return null
  const number = Number(text)
  return number > 0 ? number : null
}

function parseHour(value: string) {
  if (!/^\d{1,2}$/.test(value.trim())) return null
  const hour = Number(value)
  return hour <= 22 ? hour : null
}
