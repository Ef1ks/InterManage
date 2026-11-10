import { Popover } from '@base-ui/react/popover'
import {
  BadgePercentIcon,
  CalendarDaysIcon,
  FileOutputIcon,
  ListPlusIcon,
  ScanBarcodeIcon,
  Trash2Icon,
  WandSparklesIcon,
  XIcon,
} from 'lucide-react'
import { type ClipboardEvent, type KeyboardEvent, useState } from 'react'

import { CopyButton } from '@/components/CopyButton'
import { DateField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Calendar } from '@/components/ui/calendar'
import { Card } from '@/components/ui/card'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { parseNumber } from '@/features/data-entry/grid-values'
import { formatDocumentDate, formatMoney, formatPercent, formatPrice } from '@/features/documents/format'
import { cn } from '@/lib/utils'
import type { PromoMode, PromoRow, PromoTile } from '@/store/promoSetupDraftSlice'

import { hasPeriod, pastedCodes, promoMargin, rowEndError } from './promo-rows'

const NO_DISCOUNT = 'brak'

const fieldClass =
  'h-8 w-full min-w-0 rounded-lg border border-input bg-background px-2 text-sm transition-[border-color,box-shadow] duration-150 outline-none focus:border-ring focus:ring-3 focus:ring-ring/30 aria-invalid:border-destructive dark:bg-input/30'

const levelLabel = (level: number) => `-${String(level).replace('.', ',')}%`

type Props = {
  /** {@code macro}: własny koniec przy każdym artykule i „Stwórz automatycznie”; {@code pro}: okres i plik .PRO. */
  variant: PromoMode
  tile: PromoTile
  levels: number[]
  selected: string | null
  onSelect: (row: PromoRow) => void
  onDates: (changes: { dateFrom?: string; dateTo?: string }) => void
  onCodes: (codes: string[]) => void
  /** Wpisany tekst to nie kod: lista towarów z tym wyszukiwaniem. */
  onSearch: (text: string) => void
  onList: () => void
  onPrice: (row: PromoRow, price: number | null) => void
  onDiscount: (row: PromoRow, discount: number | null) => void
  /** Ta sama przecena dla wszystkich artykułów kafelka. */
  onTileDiscount: (discount: number) => void
  onRemoveRow: (row: PromoRow) => void
  /** Tryb .PRO: kod taryfy, usunięcie kafelka i jego plik. */
  tariff?: string
  canGenerate?: boolean
  onRemove?: () => void
  onGenerate?: () => void
  /** Tryb makra: koniec promocji artykułu i ten sam koniec dla wszystkich. */
  onRowEnd?: (row: PromoRow, dateTo: string) => void
  onAllEnds?: () => void
  /** Tryb makra: powód, dla którego nie da się jeszcze ustawić promocji ({@code null} = można). */
  createBlocked?: string | null
  onCreate?: () => void
}

/**
 * Promocja: artykuły z cenami. W trybie .PRO kafelek ma jeden okres i daje plik, w trybie makra każdy artykuł ma
 * własny koniec, a koniec kafelka podpowiada go nowym artykułom. Enter i strzałki w cenie przechodzą między wierszami.
 */
export function PromoTileCard(props: Props) {
  const { variant, tile, tariff, levels, selected, canGenerate, onDates, onList, onTileDiscount, onRemove } = props
  const macro = variant === 'macro'
  const periodError = tile.dateTo && !hasPeriod(tile) ? 'Koniec przed początkiem' : undefined
  const articleRows = tile.rows.filter((row) => row.article)
  // Wspólna przecena widoczna w wyborze, gdy wszystkie artykuły mają ten sam stopień.
  const common = articleRows[0]?.discount ?? null
  const tileDiscount =
    common !== null && levels.includes(common) && articleRows.every((row) => row.discount === common)
      ? String(common)
      : null
  const levelItems = levels.map((level) => ({ value: String(level), label: levelLabel(level) }))

  return (
    <Card className="gap-3 px-4 py-4">
      {macro ? (
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <h2 className="font-semibold">Promocje do ustawienia</h2>
          <span className="text-sm text-muted-foreground tabular-nums">artykuły: {articleRows.length}</span>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
          <h2 className="font-semibold">Promocja {tile.number}</h2>
          {tariff && (
            <span className="inline-flex items-center gap-1 text-sm text-muted-foreground tabular-nums">
              kod taryfy {tariff}
              <CopyButton value={tariff} label={`Kopiuj kod taryfy ${tariff}`} />
            </span>
          )}
          <Button
            variant="ghost"
            size="icon-sm"
            className="ml-auto"
            aria-label={`Usuń promocję ${tile.number}`}
            title="Usuń promocję"
            onClick={onRemove}
          >
            <Trash2Icon />
          </Button>
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-2">
        {/* W trybie makra początek to dzień ustawienia: Mercalys podpowiada dzisiejszy i makro go nie zmienia. */}
        {!macro && (
          <DateField label="Początek promocji" value={tile.dateFrom} onChange={(dateFrom) => onDates({ dateFrom })} />
        )}
        <DateField
          label={macro ? 'Koniec dla nowych artykułów' : 'Koniec promocji'}
          value={tile.dateTo}
          min={tile.dateFrom}
          error={periodError}
          hint={
            macro && tile.dateTo && articleRows.length > 0 ? (
              <button
                type="button"
                className="font-medium text-foreground underline-offset-2 hover:underline"
                onClick={props.onAllEnds}
              >
                Ustaw ten koniec wszystkim artykułom
              </button>
            ) : undefined
          }
          onChange={(dateTo) => onDates({ dateTo })}
        />
      </div>

      <div className="overflow-x-auto rounded-lg border">
        <table className={cn('w-full text-sm', macro ? 'min-w-[44rem]' : 'min-w-[38rem]')}>
          <thead className="bg-muted text-left text-xs text-muted-foreground">
            <tr>
              <th className="w-32 px-2 py-1.5 font-medium">EAN / ITM8</th>
              <th className="px-2 py-1.5 font-medium">Nazwa i marka</th>
              <th className="w-16 px-2 py-1.5 text-right font-medium" title="Cena netto z ostatniego przyjęcia">
                Ost. zakup
              </th>
              <th className="w-16 px-2 py-1.5 text-right font-medium" title="Cena z kartoteki, bez trwającej promocji">
                Cena kat.
              </th>
              <th className="w-20 px-2 py-1.5 text-right font-medium">Cena promo</th>
              <th className="w-17 px-2 py-1.5 text-right font-medium">Marża</th>
              <th className="w-23 px-2 py-1.5 font-medium">Przecena</th>
              {macro && <th className="w-26 px-2 py-1.5 font-medium">Koniec</th>}
              <th className="w-9" aria-label="Usuń" />
            </tr>
          </thead>
          <tbody className="divide-y">
            {tile.rows.map((row) => (
              <Row key={row.id} {...props} row={row} active={row.article?.itm8 === selected && selected !== null} />
            ))}
            <CodeRow {...props} />
          </tbody>
        </table>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Button variant="outline" onClick={onList}>
          <ListPlusIcon />
          Lista towarów
        </Button>
        {levels.length > 0 && (
          <Select
            items={levelItems}
            value={tileDiscount}
            disabled={articleRows.length === 0}
            onValueChange={(value) => value && onTileDiscount(Number(value))}
          >
            <SelectTrigger
              aria-label={`Przecena wszystkich artykułów promocji ${tile.number}`}
              title="Ta sama przecena dla wszystkich artykułów tej promocji"
              className="h-9"
            >
              <BadgePercentIcon />
              <SelectValue placeholder="Przeceń wszystkie" />
            </SelectTrigger>
            <SelectContent>
              {levelItems.map((item) => (
                <SelectItem key={item.value} value={item.value}>
                  Wszystkie {item.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
        {macro ? (
          <Button
            size="lg"
            className="ml-auto"
            disabled={!props.onCreate || Boolean(props.createBlocked)}
            title={props.createBlocked ?? undefined}
            onClick={props.onCreate}
          >
            <WandSparklesIcon />
            Stwórz automatycznie
          </Button>
        ) : (
          <Button
            variant="outline"
            className="ml-auto"
            disabled={!canGenerate}
            title={canGenerate ? undefined : 'Dodaj artykuły i ustaw okres promocji'}
            onClick={props.onGenerate}
          >
            <FileOutputIcon />
            Wygeneruj plik promocji
          </Button>
        )}
      </div>
      {macro && props.createBlocked && articleRows.length > 0 && (
        <p className="text-right text-xs text-muted-foreground">{props.createBlocked}</p>
      )}
      {levels.length === 0 && (
        <p className="text-xs text-muted-foreground">
          Dodaj automatyczne przeceny pod kafelkami, żeby wybierać obniżkę.
        </p>
      )}
    </Card>
  )
}

function Row({
  row,
  active,
  variant,
  tile,
  levels,
  onSelect,
  onPrice,
  onDiscount,
  onRowEnd,
  onRemoveRow,
}: Props & { row: PromoRow; active: boolean }) {
  const { article } = row
  const margin = promoMargin(row)
  const discount = row.discount !== null && levels.includes(row.discount) ? String(row.discount) : NO_DISCOUNT
  const items = [
    { value: NO_DISCOUNT, label: 'ręcznie' },
    ...levels.map((level) => ({ value: String(level), label: levelLabel(level) })),
  ]
  const priceTooHigh = row.price !== null && article?.regularPrice != null && row.price >= article.regularPrice

  return (
    <tr
      data-promo-row
      onClick={() => onSelect(row)}
      className={cn(
        'transition-colors duration-100',
        article?.itm8 && 'cursor-pointer hover:bg-muted/50',
        active && 'bg-sky-200 hover:bg-sky-200 dark:bg-sky-900/60 dark:hover:bg-sky-900/60',
      )}
    >
      <td className="px-2 py-1 tabular-nums">{article?.ean ?? row.code}</td>
      {article ? (
        <>
          <td className="max-w-0 px-2 py-1">
            <p className="truncate font-medium" title={article.name ?? undefined}>
              {article.name}
            </p>
            <p className="truncate text-xs text-muted-foreground" title={article.brand ?? undefined}>
              {article.brand}
              {article.draft && ' · czeka na raport'}
            </p>
          </td>
          <td
            className="px-2 py-1 text-right tabular-nums"
            title={
              article.lastPurchaseDate
                ? `Przyjęcie z ${formatDocumentDate(article.lastPurchaseDate)}`
                : 'Bez przyjęcia, cena z kartoteki'
            }
          >
            {formatPrice(article.lastPurchasePrice)}
          </td>
          <td className="px-2 py-1 text-right tabular-nums">{formatMoney(article.regularPrice)}</td>
        </>
      ) : (
        <>
          <td className="px-2 py-1">
            <span className="rounded-md bg-red-200 px-1.5 py-0.5 text-xs font-medium text-red-950 dark:bg-red-900/70 dark:text-red-100">
              Nie ma w kartotece
            </span>
          </td>
          <td colSpan={2} />
        </>
      )}
      <td className="px-1 py-1" onClick={(event) => event.stopPropagation()}>
        {article && (
          <PriceInput
            // Przecena wpisuje cenę z zewnątrz.
            key={row.price ?? 'pusta'}
            price={row.price}
            tooHigh={priceTooHigh}
            name={article.name ?? row.code}
            onFocus={() => onSelect(row)}
            onChange={(price) => onPrice(row, price)}
          />
        )}
      </td>
      <td className="px-2 py-1 text-right tabular-nums">
        {margin !== null && (
          <span
            className={cn(
              margin < 0 &&
                'rounded-md bg-red-200 px-1.5 py-0.5 font-medium text-red-950 dark:bg-red-900/70 dark:text-red-100',
            )}
          >
            {formatPercent(margin)}
          </span>
        )}
      </td>
      <td className="px-1 py-1" onClick={(event) => event.stopPropagation()}>
        {article && (
          <Select
            items={items}
            value={discount}
            onValueChange={(value) => value && onDiscount(row, value === NO_DISCOUNT ? null : Number(value))}
          >
            <SelectTrigger aria-label={`Przecena: ${article.name ?? row.code}`} size="sm" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {items.map((item) => (
                <SelectItem key={item.value} value={item.value}>
                  {item.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </td>
      {variant === 'macro' && (
        <td className="px-1 py-1" onClick={(event) => event.stopPropagation()}>
          {article && (
            <RowEndInput
              value={row.dateTo ?? ''}
              min={tile.dateFrom}
              error={rowEndError(tile, row)}
              name={article.name ?? row.code}
              onChange={(dateTo) => onRowEnd?.(row, dateTo)}
            />
          )}
        </td>
      )}
      <td className="pr-1" onClick={(event) => event.stopPropagation()}>
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={`Usuń: ${article?.name ?? row.code}`}
          onClick={() => onRemoveRow(row)}
        >
          <XIcon />
        </Button>
      </td>
    </tr>
  )
}

/** Przejście do pola ceny sąsiedniego wiersza tego kafelka; za ostatnim wierszem pole kodu. */
function moveFrom(input: HTMLInputElement, step: 1 | -1) {
  const fields = [...(input.closest('tbody')?.querySelectorAll<HTMLInputElement>('input[data-promo-field]') ?? [])]
  const next = fields[fields.indexOf(input) + step]
  next?.focus()
  next?.select()
}

function PriceInput({
  price,
  tooHigh,
  name,
  onFocus,
  onChange,
}: {
  price: number | null
  tooHigh: boolean
  name: string
  onFocus: () => void
  onChange: (price: number | null) => void
}) {
  const [text, setText] = useState(price === null ? '' : formatMoney(price))
  const parsed = parseNumber(text)

  const commit = () => {
    if (parsed === undefined || parsed === price || (parsed !== null && parsed < 0)) return
    onChange(parsed === null ? null : Math.round(parsed * 100) / 100)
  }

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter' || event.key === 'ArrowDown') {
      event.preventDefault()
      moveFrom(event.currentTarget, 1)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      moveFrom(event.currentTarget, -1)
    }
  }

  return (
    <input
      data-promo-field
      value={text}
      inputMode="decimal"
      autoComplete="off"
      aria-label={`Cena promocji: ${name}`}
      aria-invalid={parsed === undefined || (parsed !== null && parsed < 0) ? true : undefined}
      title={tooHigh ? 'Cena promocji nie jest niższa od ceny z kartoteki' : undefined}
      onChange={(event) => setText(event.target.value)}
      onFocus={(event) => {
        event.currentTarget.select()
        onFocus()
      }}
      onBlur={commit}
      onKeyDown={onKeyDown}
      className={cn(
        fieldClass,
        'text-right tabular-nums',
        tooHigh && 'border-amber-500 bg-amber-200 text-amber-950 dark:bg-amber-900/60 dark:text-amber-100',
      )}
    />
  )
}

const shortDate = (value: string) => {
  const [year, month, day] = value.split('-')
  return `${day}.${month}.${year.slice(2)}`
}

/** Koniec promocji artykułu w wierszu: zwarte pole z kalendarzem marki. */
function RowEndInput({
  value,
  min,
  error,
  name,
  onChange,
}: {
  value: string
  min: string
  error: string | null
  name: string
  onChange: (value: string) => void
}) {
  const [open, setOpen] = useState(false)
  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger
        aria-label={`Koniec promocji: ${name}`}
        aria-invalid={error ? true : undefined}
        title={error ?? undefined}
        className={cn(
          fieldClass,
          'flex items-center justify-between gap-1 text-left tabular-nums data-popup-open:border-ring data-popup-open:ring-3 data-popup-open:ring-ring/30',
          !value && 'text-muted-foreground',
          error && 'border-red-500 bg-red-200 text-red-950 dark:bg-red-900/60 dark:text-red-100',
        )}
      >
        {value ? shortDate(value) : 'wybierz'}
        <CalendarDaysIcon aria-hidden className="size-3.5 shrink-0 opacity-60" />
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Positioner sideOffset={6} align="end" className="isolate z-50">
          <Popover.Popup className="origin-(--transform-origin) rounded-2xl bg-popover p-4 text-popover-foreground shadow-[0_12px_32px_-12px_rgb(0_0_0/0.25)] ring-1 ring-foreground/10 transition-[opacity,scale] duration-150 ease-out data-ending-style:scale-[0.97] data-ending-style:opacity-0 data-starting-style:scale-[0.97] data-starting-style:opacity-0 motion-reduce:transition-none">
            <Calendar
              value={value}
              min={min}
              onChange={(next) => {
                onChange(next)
                setOpen(false)
              }}
            />
          </Popover.Popup>
        </Popover.Positioner>
      </Popover.Portal>
    </Popover.Root>
  )
}

/** Pole na kolejny kod pod artykułami: skan albo wpisany kod z Enterem, wklejona kolumna z Excela. */
function CodeRow({ variant, onCodes, onSearch }: Props) {
  const [text, setText] = useState('')

  const submit = () => {
    const code = text.trim()
    if (!code) return
    setText('')
    if (/^\d+$/.test(code)) onCodes([code])
    else onSearch(code)
  }

  const onPaste = (event: ClipboardEvent<HTMLInputElement>) => {
    const codes = pastedCodes(event.clipboardData.getData('text'))
    if (codes.length < 2) return
    event.preventDefault()
    onCodes(codes.filter((code) => /^\d+$/.test(code)))
  }

  return (
    <tr>
      <td colSpan={variant === 'macro' ? 9 : 8} className="px-1 py-1">
        <div className="relative">
          <ScanBarcodeIcon
            aria-hidden
            className="pointer-events-none absolute top-1/2 left-2 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <input
            data-promo-field
            value={text}
            autoComplete="off"
            aria-label="Dodaj artykuł: EAN, ITM8 albo nazwa"
            placeholder="Zeskanuj albo wpisz EAN, ITM8 lub nazwę i naciśnij Enter"
            onChange={(event) => setText(event.target.value)}
            onPaste={onPaste}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                submit()
              }
              if (event.key === 'ArrowUp') {
                event.preventDefault()
                moveFrom(event.currentTarget, -1)
              }
            }}
            className={cn(fieldClass, 'pl-8')}
          />
        </div>
      </td>
    </tr>
  )
}
