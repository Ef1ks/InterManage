import { useEffect, useMemo, useRef, useState } from 'react'
import { CircleAlertIcon, LoaderCircleIcon, PencilIcon, PlusIcon, Trash2Icon, XIcon } from 'lucide-react'
import { useBlocker, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { FormAlert, SegmentedField } from '@/components/form'
import { HowTo } from '@/components/HowTo'
import { SearchInput } from '@/components/SearchInput'
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
import { Card } from '@/components/ui/card'
import { useCan } from '@/features/auth/api'
import {
  MIN_SEARCH_LENGTH,
  useDeleteCalculation,
  usePriceProductSearch,
  useSaveCalculation,
} from '@/features/deli-prices/api'
import { FinishedProductPicker } from '@/features/deli-prices/FinishedProductPicker'
import { RefreshPricesButton } from '@/features/deli-prices/RefreshPricesButton'
import {
  DEFAULT_MARKUP_PERCENT,
  DEFAULT_VAT_RATE,
  formatDay,
  formatMoney,
  formatQuantity,
  ingredientsWord,
  isPieceUnit,
  isWeightUnit,
  parsePieceWeight,
  parsePrice,
  parseQuantity,
  pricePaths,
  salePrice,
  toInputValue,
  VAT_RATES,
} from '@/features/deli-prices/format'
import { ApiError } from '@/lib/api'
import type { CalculationMode, FinishedProduct, PriceCalculation, PriceProduct } from '@/lib/types'
import { cn } from '@/lib/utils'

/** {@code pieceWeight}: średnia waga 1 sztuki w kg, tylko dla składników na sztuki (pusty tekst = nieznana). */
type Row = { product: PriceProduct; quantity: string; pieceWeight: string }

const MODES: { value: CalculationMode; label: string }[] = [
  { value: 'PER_KG', label: 'Skład na 1 kg' },
  { value: 'PER_BATCH', label: 'Skład na cały przepis' },
]

const VAT_OPTIONS = VAT_RATES.map((rate) => ({ value: String(rate), label: `${rate}%` }))

/**
 * Kreator kalkulacji gotowego wyrobu z kartoteki: sposób wpisywania składu, składniki (panel po prawej) z cenami
 * z ostatniej dostawy PZ albo z kartoteki i ich ilości. Koszt 1 kg i cena sprzedaży (narzut + VAT, w górę do ,99 zł)
 * przeliczają się na bieżąco tym samym wzorem co na serwerze; zamiast wyliczonej można ustawić własną cenę.
 * Ceny składników zostają takie, jak przy dodaniu, dopóki ktoś nie kliknie "Odśwież ceny".
 */
export function CalculationEditor({ storeId, calculation }: { storeId: string; calculation?: PriceCalculation }) {
  const canEdit = useCan('DELI_PRICES', 'EDIT')
  const navigate = useNavigate()
  const save = useSaveCalculation(storeId)
  const { remove, restore } = useDeleteCalculation(storeId)
  const markupPercent = calculation?.markupPercent ?? DEFAULT_MARKUP_PERCENT

  const [product, setProduct] = useState<FinishedProduct | null>(calculation?.product ?? null)
  const [mode, setMode] = useState<CalculationMode>(calculation?.mode ?? 'PER_KG')
  const [batchYield, setBatchYield] = useState(toInputValue(calculation?.batchYield))
  const [vatRate, setVatRate] = useState(calculation?.vatRate ?? DEFAULT_VAT_RATE)
  // Pusty tekst = cena wyliczona.
  const [manualPrice, setManualPrice] = useState(toInputValue(calculation?.manualPrice))
  const [editingPrice, setEditingPrice] = useState(false)
  const [rows, setRows] = useState<Row[]>(
    () =>
      calculation?.items.map((item) => ({
        product: item,
        quantity: toInputValue(item.quantity),
        pieceWeight: toInputValue(item.pieceWeight),
      })) ?? [],
  )
  const [submitted, setSubmitted] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  // Nowo dodany składnik dostaje fokus w polu ilości, gdy tylko pole się pojawi.
  const focusProductId = useRef<string | null>(null)
  // Po zapisie albo usunięciu przechodzimy dalej bez pytania o niezapisane zmiany.
  const leaving = useRef(false)

  const current = snapshot(product?.productId ?? null, mode, batchYield, vatRate, manualPrice, rows)
  const [initial] = useState(current)
  const dirty = canEdit && current !== initial
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

  const yieldParsed = parseQuantity(batchYield)
  const totals = useMemo(() => {
    let total = 0
    let weight = 0
    let missing = 0
    // Składniki na sztuki bez wagi sztuki - waga surowców jest bez nich zaniżona.
    let unweighed = 0
    for (const row of rows) {
      const quantity = parseQuantity(row.quantity).value
      if (row.product.unitCost == null) missing++
      if (quantity === undefined) continue
      if (row.product.unitCost != null) total += row.product.unitCost * quantity
      if (isWeightUnit(row.product.unit)) weight += quantity
      if (isPieceUnit(row.product.unit)) {
        const pieceWeight = parsePieceWeight(row.pieceWeight).value
        if (pieceWeight) weight += quantity * pieceWeight
        else unweighed++
      }
    }
    return { total, weight, missing, unweighed }
  }, [rows])
  const costPerKg =
    mode === 'PER_KG' ? totals.total : yieldParsed.value !== undefined ? totals.total / yieldParsed.value : null
  const calculatedPrice = salePrice(costPerKg, markupPercent, vatRate)
  // Z 3 kg surowców wychodzi np. 1 kg wyrobu (33%); liczymy tylko, gdy znamy wagę wszystkich składników.
  const batchYieldPercent =
    yieldParsed.value !== undefined && totals.weight > 0 && totals.unweighed === 0
      ? Math.round((yieldParsed.value / totals.weight) * 1000) / 10
      : null
  const manualParsed = parsePrice(manualPrice)
  const hasManualPrice = manualPrice.trim() !== ''

  const addedIds = useMemo(() => new Set(rows.map((row) => row.product.productId)), [rows])
  const add = (ingredient: PriceProduct) => {
    setRows((current) => [
      ...current,
      { product: ingredient, quantity: '', pieceWeight: toInputValue(ingredient.pieceWeight) },
    ])
    focusProductId.current = ingredient.productId
  }
  const updateRow = (productId: string, change: Partial<Row>) =>
    setRows((current) => current.map((row) => (row.product.productId === productId ? { ...row, ...change } : row)))
  const removeRow = (productId: string) =>
    setRows((current) => current.filter((row) => row.product.productId !== productId))

  const productError = submitted && !product ? 'Wybierz wyrób z kartoteki' : undefined
  const yieldError = submitted && mode === 'PER_BATCH' ? yieldParsed.error : undefined
  const priceError = hasManualPrice && (submitted || !editingPrice) ? manualParsed.error : undefined

  const submit = () => {
    setSubmitted(true)
    setFormError(null)
    const quantities = rows.map((row) => parseQuantity(row.quantity))
    const pieceWeights = rows.map((row) =>
      isPieceUnit(row.product.unit) ? parsePieceWeight(row.pieceWeight) : { value: null },
    )
    if (
      !product ||
      (mode === 'PER_BATCH' && yieldParsed.error) ||
      (hasManualPrice && manualParsed.error) ||
      quantities.some((q) => q.error) ||
      pieceWeights.some((w) => w.error)
    ) {
      setFormError('Popraw zaznaczone pola.')
      return
    }
    if (rows.length === 0) {
      setFormError('Dodaj co najmniej jeden składnik z kartoteki po prawej.')
      return
    }
    save.mutate(
      {
        id: calculation?.id,
        request: {
          productId: product.productId,
          mode,
          batchYield: mode === 'PER_BATCH' ? (yieldParsed.value ?? null) : null,
          vatRate,
          manualPrice: hasManualPrice ? (manualParsed.value ?? null) : null,
          items: rows.map((row, index) => ({
            productId: row.product.productId,
            quantity: quantities[index].value!,
            pieceWeight: pieceWeights[index].value ?? null,
          })),
        },
      },
      {
        onSuccess: (saved) => {
          toast.success(calculation ? 'Zapisano kalkulację' : `Utworzono kalkulację ${saved.name}`)
          if (!calculation) {
            leaving.current = true
            navigate(pricePaths.calculation(saved.id), { replace: true })
          }
        },
        onError: (error) => setFormError(error instanceof ApiError ? error.message : 'Nie udało się zapisać'),
      },
    )
  }

  const deleteCalculation = () => {
    if (!calculation) return
    remove.mutate(calculation.id, {
      onSuccess: () => {
        leaving.current = true
        navigate(pricePaths.list)
        toast.success(`Usunięto kalkulację ${calculation.name}`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(calculation.id, {
                onError: (error) =>
                  toast.error(error instanceof ApiError ? error.message : 'Nie udało się przywrócić kalkulacji'),
              }),
          },
        })
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć'),
    })
  }

  return (
    <>
      <BackLink to={pricePaths.list}>
        Wszystkie kalkulacje
      </BackLink>

      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">{calculation ? calculation.name : 'Nowa kalkulacja'}</h1>
          {calculation?.pricesRefreshedAt && (
            <p className="mt-1 text-sm text-muted-foreground tabular-nums">
              Ceny składników z {formatDay(calculation.pricesRefreshedAt)}
            </p>
          )}
        </div>
        {calculation && canEdit && (
          <div className="flex flex-wrap gap-2">
            <RefreshPricesButton
              storeId={storeId}
              calculationId={calculation.id}
              disabled={dirty}
              title={dirty ? 'Najpierw zapisz zmiany' : undefined}
            />
            <Button variant="ghost" loading={remove.isPending} onClick={deleteCalculation}>
              <Trash2Icon />
              Usuń kalkulację
            </Button>
          </div>
        )}
      </div>

      {canEdit && (
        <HowTo
          id="deli-price-calculation"
          title="Jak policzyć cenę wyrobu"
          className="mb-6"
          steps={[
            <>
              Wybierz <strong className="font-semibold text-foreground">wyrób z kartoteki</strong> (działy 24, 36, 46) - jego
              EAN trafi do eksportu.
            </>,
            <>
              Zdecyduj, czy wpisujesz skład <strong className="font-semibold text-foreground">na 1 kg</strong>, czy{' '}
              <strong className="font-semibold text-foreground">na cały przepis</strong> - wtedy podaj, ile kg z niego
              wychodzi.
            </>,
            <>
              Dodaj składniki z panelu po prawej i wpisz ich ilości. Cena każdego jest z ostatniej dostawy, a gdy go nie
              było w dostawach - z kartoteki. Przy warzywach na sztuki podaj, ile waży 1 szt., a wliczą się do wagi
              surowców.
            </>,
            <>
              Cena sprzedaży to koszt 1 kg + {markupPercent}% narzutu + VAT, w górę do ,99 zł. Później zaktualizujesz ją
              przyciskiem „Odśwież ceny”.
            </>,
          ]}
        />
      )}

      <div className={cn('grid items-start gap-6', canEdit && 'lg:grid-cols-[minmax(0,1fr)_24rem]')}>
        <div className="grid gap-4">
          <Card className="gap-4 p-5">
            <div className="grid gap-1.5">
              <h2 className="text-sm font-medium">Wyrób</h2>
              {canEdit && (
                <FinishedProductPicker
                  storeId={storeId}
                  calculationId={calculation?.id}
                  value={product}
                  onChange={setProduct}
                  invalid={productError !== undefined}
                />
              )}
              {productError && <p className="text-sm text-destructive">{productError}</p>}
              {product && (
                <p className="text-sm text-muted-foreground tabular-nums">
                  <span className="font-medium text-foreground">{product.name}</span>
                  {product.brand && ` · ${product.brand}`} · EAN {product.ean} · na półce {formatMoney(product.salePrice)}
                </p>
              )}
            </div>
            <div className="flex flex-wrap items-center gap-x-4 gap-y-3">
              <SegmentedField label="Jak wpisujesz skład" options={MODES} value={mode} onChange={canEdit ? setMode : () => {}} />
              {mode === 'PER_BATCH' && (
                <div className="flex items-center gap-2 text-sm animate-in fade-in-0 duration-150 motion-reduce:animate-none">
                  <span className="text-muted-foreground">Z przepisu wychodzi</span>
                  <NumberInput
                    label="Ile kg wyrobu wychodzi z przepisu"
                    unit="kg"
                    value={batchYield}
                    onChange={setBatchYield}
                    invalid={yieldError !== undefined}
                    disabled={!canEdit}
                  />
                </div>
              )}
            </div>
            <p className="text-sm text-muted-foreground">
              {mode === 'PER_KG'
                ? 'Wpisz, ile każdego składnika idzie na 1 kg gotowego wyrobu.'
                : 'Wpisz ilości na cały przepis i wagę gotowego wyrobu (po upieczeniu, ugotowaniu).'}
              {yieldError && <span className="ml-1 text-destructive">{yieldError}.</span>}
            </p>
            <div className="flex flex-wrap items-center gap-3 text-sm">
              <span className="text-muted-foreground">VAT</span>
              <SegmentedField
                label="Stawka VAT"
                options={VAT_OPTIONS}
                value={String(vatRate)}
                onChange={(value) => canEdit && setVatRate(Number(value))}
              />
            </div>
          </Card>

          <Card className="gap-0 py-0">
            <div className="flex items-center justify-between gap-3 border-b px-4 py-3">
              <h2 className="text-base font-semibold">Składniki</h2>
              <span className="text-sm text-muted-foreground tabular-nums">
                {rows.length} {ingredientsWord(rows.length)}
              </span>
            </div>
            {rows.length === 0 ? (
              <p className="px-4 py-10 text-center text-sm text-muted-foreground">
                {canEdit
                  ? 'Wyszukaj produkty w kartotece po prawej i dodaj je do przepisu.'
                  : 'Ta kalkulacja nie ma składników.'}
              </p>
            ) : (
              <ul className="divide-y">
                {rows.map((row) => (
                  <IngredientRow
                    key={row.product.productId}
                    row={row}
                    showError={submitted}
                    disabled={!canEdit}
                    inputRef={(element) => {
                      if (element && focusProductId.current === row.product.productId) {
                        focusProductId.current = null
                        element.focus()
                      }
                    }}
                    onQuantity={(quantity) => updateRow(row.product.productId, { quantity })}
                    onPieceWeight={(pieceWeight) => updateRow(row.product.productId, { pieceWeight })}
                    onRemove={() => removeRow(row.product.productId)}
                  />
                ))}
              </ul>
            )}
          </Card>

          <Card className="sticky bottom-4 z-10 gap-3 p-4 shadow-[0_8px_24px_-12px_rgb(0_0_0/0.25)]">
            {formError && <FormAlert>{formError}</FormAlert>}
            <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
              <dl className="flex flex-wrap items-baseline gap-x-6 gap-y-1 tabular-nums">
                <div>
                  <dt className="text-xs text-muted-foreground">
                    {hasManualPrice ? 'Twoja cena 1 kg' : 'Cena sprzedaży 1 kg'}
                  </dt>
                  <dd className="flex items-center gap-2">
                    {editingPrice ? (
                      <NumberInput
                        ref={(element) => element?.focus()}
                        label="Własna cena sprzedaży 1 kg"
                        unit="zł"
                        value={manualPrice}
                        onChange={setManualPrice}
                        onBlur={() => setEditingPrice(false)}
                        onEnter={() => setEditingPrice(false)}
                        invalid={hasManualPrice && manualParsed.error !== undefined}
                      />
                    ) : (
                      <span className="text-2xl font-semibold tracking-tight">
                        {hasManualPrice ? formatMoney(manualParsed.value) : formatMoney(calculatedPrice)}
                      </span>
                    )}
                    {canEdit && !editingPrice && (
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={hasManualPrice ? 'Zmień własną cenę' : 'Ustaw własną cenę'}
                        title={hasManualPrice ? 'Zmień własną cenę' : 'Ustaw własną cenę'}
                        onClick={() => {
                          if (!hasManualPrice) setManualPrice(toInputValue(calculatedPrice))
                          setEditingPrice(true)
                        }}
                      >
                        <PencilIcon />
                      </Button>
                    )}
                  </dd>
                </div>
                {hasManualPrice && (
                  <div>
                    <dt className="text-xs text-muted-foreground">Wyliczona</dt>
                    <dd className="flex items-center gap-2 font-medium">
                      {formatMoney(calculatedPrice)}
                      {canEdit && (
                        <Button
                          variant="link"
                          className="h-auto p-0 text-xs"
                          onClick={() => {
                            setManualPrice('')
                            setEditingPrice(false)
                          }}
                        >
                          Wróć do wyliczonej
                        </Button>
                      )}
                    </dd>
                  </div>
                )}
                <div>
                  <dt className="text-xs text-muted-foreground">Koszt 1 kg</dt>
                  <dd className="font-medium">{formatMoney(costPerKg)}</dd>
                </div>
                {mode === 'PER_BATCH' && (
                  <div>
                    <dt className="text-xs text-muted-foreground">Koszt przepisu</dt>
                    <dd className="font-medium">{formatMoney(totals.total)}</dd>
                  </div>
                )}
                {totals.weight > 0 && (
                  <div title="Waga składników przed obróbką - gotowego wyrobu wychodzi zwykle mniej">
                    <dt className="text-xs text-muted-foreground">Waga surowców</dt>
                    <dd className="font-medium">
                      {totals.unweighed > 0 && '> '}
                      {formatQuantity(totals.weight)} kg
                    </dd>
                  </div>
                )}
                {mode === 'PER_BATCH' && batchYieldPercent !== null && (
                  <div title="Ile gotowego wyrobu wychodzi z wagi surowców">
                    <dt className="text-xs text-muted-foreground">Wydajność</dt>
                    <dd className="font-medium">{formatQuantity(batchYieldPercent)}%</dd>
                  </div>
                )}
                {product?.salePrice != null && (
                  <div>
                    <dt className="text-xs text-muted-foreground">Na półce</dt>
                    <dd className="font-medium text-muted-foreground">{formatMoney(product.salePrice)}</dd>
                  </div>
                )}
              </dl>
              {canEdit && (
                <Button size="xl" loading={save.isPending} disabled={!dirty && calculation !== undefined} onClick={submit}>
                  {calculation ? 'Zapisz zmiany' : 'Zapisz kalkulację'}
                </Button>
              )}
            </div>
            <p className="text-xs text-muted-foreground">
              {priceError ? (
                <span className="text-destructive">Twoja cena: {priceError}.</span>
              ) : hasManualPrice ? (
                'Do eksportu idzie Twoja cena. Przy odświeżaniu cen zapytamy, czy ją zostawić, gdy wyliczona się zmieni.'
              ) : (
                `Koszt 1 kg + ${markupPercent}% narzutu + VAT ${vatRate}%, zaokrąglone w górę do ,99 zł.`
              )}
            </p>
            {totals.unweighed > 0 && totals.weight > 0 && (
              <p className="text-xs text-muted-foreground">
                Waga surowców bez {totals.unweighed === 1 ? 'jednego składnika' : `${totals.unweighed} składników`} na
                sztuki - podaj przy nich, ile waży 1 szt.
              </p>
            )}
            {totals.missing > 0 && (
              <p className="flex items-center gap-1.5 text-sm text-amber-700 dark:text-amber-400">
                <CircleAlertIcon aria-hidden className="size-4 shrink-0" />
                {totals.missing === 1 ? 'Jeden składnik nie ma' : `${totals.missing} składniki nie mają`} ceny ani w
                dostawach, ani w kartotece - koszt jest zaniżony.
              </p>
            )}
          </Card>
        </div>

        {canEdit && <ProductPanel storeId={storeId} addedIds={addedIds} onAdd={add} />}
      </div>

      <AlertDialog open={blocker.state === 'blocked'} onOpenChange={(open) => !open && blocker.reset?.()}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Wyjść bez zapisywania?</AlertDialogTitle>
            <AlertDialogDescription>Zmiany w tej kalkulacji nie zostały zapisane i przepadną.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Zostań</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={() => blocker.proceed?.()}>
              Wyjdź bez zapisu
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

/** Stan kreatora do porównania z zapisanym (czy są niezapisane zmiany). */
function snapshot(
  productId: string | null,
  mode: CalculationMode,
  batchYield: string,
  vatRate: number,
  manualPrice: string,
  rows: Row[],
) {
  return JSON.stringify([
    productId,
    mode,
    mode === 'PER_BATCH' ? batchYield.trim() : '',
    vatRate,
    manualPrice.trim(),
    rows.map((row) => [row.product.productId, row.quantity.trim(), row.pieceWeight.trim()]),
  ])
}

/** "2,60 zł / kg · dostawa 23.10.2026" albo "· z kartoteki". */
function PriceLabel({ product }: { product: PriceProduct }) {
  if (product.unitCost == null) {
    return <span className="text-amber-700 dark:text-amber-400">brak ceny</span>
  }
  return (
    <>
      {formatMoney(product.unitCost)} / {product.unit}
      <span className="text-muted-foreground/80">
        {product.priceSource === 'DELIVERY' && product.priceDate
          ? ` · dostawa ${formatDay(product.priceDate)}`
          : ' · z kartoteki'}
      </span>
    </>
  )
}

function IngredientRow({
  row,
  showError,
  disabled,
  inputRef,
  onQuantity,
  onPieceWeight,
  onRemove,
}: {
  row: Row
  showError: boolean
  disabled: boolean
  inputRef: (element: HTMLInputElement | null) => void
  onQuantity: (value: string) => void
  onPieceWeight: (value: string) => void
  onRemove: () => void
}) {
  const { product } = row
  const parsed = parseQuantity(row.quantity)
  const error = showError || (row.quantity.trim() !== '' && parsed.error) ? parsed.error : undefined
  const cost = parsed.value !== undefined && product.unitCost != null ? product.unitCost * parsed.value : null
  // Małe ilości łatwiej sprawdzić w gramach: 0,015 kg = 15 g.
  const grams = isWeightUnit(product.unit) && parsed.value !== undefined && parsed.value < 1 ? parsed.value * 1000 : null
  // Sztuki liczą się w koszcie jak dotąd, a ze średnią wagą sztuki wchodzą też do wagi surowców.
  const pieces = isPieceUnit(product.unit)
  const pieceWeight = parsePieceWeight(row.pieceWeight)
  const pieceWeightError =
    pieceWeight.error && (showError || row.pieceWeight.trim() !== '') ? pieceWeight.error : undefined
  const piecesWeight = pieces && parsed.value !== undefined && pieceWeight.value ? parsed.value * pieceWeight.value : null

  return (
    <li className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-2 px-4 py-3 sm:grid-cols-[minmax(0,1fr)_auto_6rem_auto]">
      <div className="min-w-0">
        <div className="truncate text-sm font-medium" title={product.name ?? undefined}>
          {product.name ?? 'Bez nazwy'}
          {product.brand && <span className="ml-1.5 font-normal text-muted-foreground">{product.brand}</span>}
        </div>
        <div
          className="text-xs text-muted-foreground tabular-nums"
          title={product.priceDocumentNumber ? `Przyjęcie towaru ${product.priceDocumentNumber}` : undefined}
        >
          <PriceLabel product={product} />
        </div>
        {pieces && (
          <div className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
            <span>1 szt. waży ok.</span>
            <NumberInput
              size="sm"
              label={`Średnia waga 1 szt.: ${product.name ?? product.itm8}`}
              unit="kg"
              value={row.pieceWeight}
              onChange={onPieceWeight}
              invalid={pieceWeightError !== undefined}
              disabled={disabled}
            />
            {pieceWeightError && <span className="text-destructive">{pieceWeightError}</span>}
          </div>
        )}
      </div>
      <div className="justify-self-end">
        <NumberInput
          ref={inputRef}
          label={`Ilość: ${product.name ?? product.itm8}`}
          unit={product.unit}
          value={row.quantity}
          onChange={onQuantity}
          invalid={error !== undefined}
          disabled={disabled}
        />
        <div className="mt-1 h-4 text-right text-xs tabular-nums">
          {error ? (
            <span className="text-destructive">{error}</span>
          ) : grams !== null ? (
            <span className="text-muted-foreground">{formatQuantity(grams)} g</span>
          ) : (
            piecesWeight !== null && (
              <span className="text-muted-foreground">ok. {formatQuantity(piecesWeight)} kg</span>
            )
          )}
        </div>
      </div>
      <div className="text-right text-sm font-medium tabular-nums max-sm:col-start-1 max-sm:row-start-2 max-sm:text-left">
        {cost !== null ? formatMoney(cost) : <span className="text-muted-foreground">-</span>}
      </div>
      {!disabled && (
        <Button
          variant="ghost"
          size="icon-sm"
          aria-label={`Usuń ${product.name ?? 'składnik'} z przepisu`}
          onClick={onRemove}
          className="justify-self-end"
        >
          <XIcon />
        </Button>
      )}
    </li>
  )
}

function NumberInput({
  ref,
  size = 'default',
  label,
  unit,
  value,
  onChange,
  onBlur,
  onEnter,
  invalid,
  disabled,
}: {
  ref?: (element: HTMLInputElement | null) => void
  size?: 'default' | 'sm'
  label: string
  unit: string
  value: string
  onChange: (value: string) => void
  onBlur?: () => void
  onEnter?: () => void
  invalid?: boolean
  disabled?: boolean
}) {
  return (
    <label
      className={cn(
        'flex shrink-0 items-center border border-input bg-background transition-[border-color,box-shadow] duration-150',
        size === 'sm' ? 'h-7 w-24 rounded-lg pr-2' : 'h-10 w-32 rounded-xl pr-3',
        'focus-within:border-ring focus-within:ring-3 focus-within:ring-ring/30',
        invalid && 'border-destructive focus-within:border-destructive focus-within:ring-destructive/20',
        disabled && 'opacity-60',
      )}
    >
      <input
        ref={ref}
        type="text"
        inputMode="decimal"
        autoComplete="off"
        aria-label={`${label} (${unit})`}
        aria-invalid={invalid || undefined}
        placeholder="0"
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        onBlur={onBlur}
        onKeyDown={(event) => event.key === 'Enter' && onEnter?.()}
        className={cn(
          'h-full w-full min-w-0 bg-transparent text-right font-medium tabular-nums outline-none placeholder:text-muted-foreground/50',
          size === 'sm' ? 'pl-2 text-xs text-foreground' : 'pl-3 text-sm',
        )}
      />
      <span aria-hidden className={cn('text-xs text-muted-foreground', size === 'sm' ? 'ml-1 w-4' : 'ml-1.5 w-7')}>
        {unit}
      </span>
    </label>
  )
}

/** Kartoteka sklepu po prawej: wyszukiwanie składników z ceną (ostatnia dostawa albo kartoteka) i dodawanie ich. */
function ProductPanel({
  storeId,
  addedIds,
  onAdd,
}: {
  storeId: string
  addedIds: Set<string>
  onAdd: (product: PriceProduct) => void
}) {
  const [query, setQuery] = useState('')
  const { data: results = [], isFetching, isError, longEnough } = usePriceProductSearch(storeId, query)
  const typedEnough = query.trim().replace(/\s+/g, '').length >= MIN_SEARCH_LENGTH

  return (
    <Card className="gap-3 p-4 lg:sticky lg:top-4">
      <div>
        <h2 className="text-base font-semibold">Składniki z kartoteki</h2>
        <p className="text-sm text-muted-foreground">Cena z ostatniej dostawy, a bez dostawy - z kartoteki.</p>
      </div>
      <div className="relative">
        <SearchInput value={query} onChange={setQuery} placeholder="Szukaj: nazwa, marka, EAN, ITM8" />
        {isFetching && typedEnough && (
          <LoaderCircleIcon
            aria-hidden
            className="absolute top-1/2 right-9 size-4 -translate-y-1/2 animate-spin text-muted-foreground motion-reduce:animate-none"
          />
        )}
      </div>
      <div aria-live="polite" className="-mx-1 max-h-[min(32rem,60vh)] overflow-y-auto overscroll-contain px-1">
        {!typedEnough || !longEnough ? (
          <p className="py-6 text-center text-sm text-muted-foreground">
            Wpisz co najmniej {MIN_SEARCH_LENGTH} znaki. Słowa w dowolnej kolejności, np. „smiet 30”.
          </p>
        ) : isError ? (
          <p className="py-6 text-center text-sm text-muted-foreground">Nie udało się wyszukać produktów.</p>
        ) : results.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted-foreground">
            {isFetching ? 'Szukam…' : 'Nie ma takiego produktu w kartotece sklepu.'}
          </p>
        ) : (
          <ul className="grid gap-0.5">
            {results.map((product) => {
              const added = addedIds.has(product.productId)
              return (
                <li key={product.productId}>
                  <button
                    type="button"
                    disabled={added}
                    onClick={() => onAdd(product)}
                    className="grid w-full grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 rounded-lg px-2.5 py-2 text-left text-sm transition-colors outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/40 disabled:cursor-default disabled:opacity-50 disabled:hover:bg-transparent"
                  >
                    <span className="min-w-0">
                      {/* Ta sama nazwa bywa u kilku marek, więc marka nie może zniknąć przy długiej nazwie. */}
                      <span className="flex min-w-0 items-baseline gap-1.5">
                        <span className="truncate font-medium" title={product.name ?? undefined}>
                          {product.name ?? 'Bez nazwy'}
                        </span>
                        {product.brand && (
                          <span className="max-w-[45%] shrink-0 truncate text-muted-foreground" title={product.brand}>
                            {product.brand}
                          </span>
                        )}
                      </span>
                      <span className="block truncate text-xs text-muted-foreground tabular-nums">
                        <PriceLabel product={product} />
                      </span>
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {added ? 'dodany' : <PlusIcon aria-label="Dodaj do przepisu" className="size-4" />}
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </Card>
  )
}
