import { useState } from 'react'
import { Tabs } from '@base-ui/react/tabs'
import { RotateCcwIcon, SendIcon, XIcon } from 'lucide-react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
import { DateField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useDeliOverview, useSubmitOrder, useSupplierForm } from '@/features/deli-order/api'
import { CategoryNav, categoryAnchorId } from '@/features/deli-order/CategoryNav'
import { deliPaths } from '@/features/deli-order/paths'
import { ProductSearchField } from '@/features/deli-order/ProductSearchField'
import { QuantityInput, UnitToggle, parseQuantity } from '@/features/deli-order/QuantityInput'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { formatLongDate, todayIso } from '@/lib/dates'
import type { DeliSupplier, OrderForm, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'
import { useAppDispatch, useAppSelector } from '@/store'
import {
  EMPTY_DRAFT,
  type DraftExtra,
  clearDraft,
  draftKey,
  hasDraftContent,
  restoreDraft,
  selectSupplier,
  setDraftQuantity,
  updateDraft,
} from '@/store/deliOrderDraftSlice'

/** Składanie zamówienia z formularza dostawcy: własnego albo sklepu, którego zaproszenie przyjęliśmy. */
export function OrderTab({ store }: { store: StoreSummary }) {
  const { data: overview, isPending } = useDeliOverview(store.id)
  const supplierId = useAppSelector((state) => state.deliOrderDraft.supplierByStore[store.id])
  const dispatch = useAppDispatch()
  const suppliers = overview?.suppliers ?? []
  const supplier = suppliers.find((s) => s.store.id === supplierId) ?? suppliers[0]

  if (isPending) {
    return <div aria-hidden className="h-40 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  }
  if (!supplier) {
    return (
      <FullPageMessage
        title="Nie masz jeszcze z czego zamawiać"
        description="Przyjmij zaproszenie innego sklepu do jego formularza albo ułóż własny formularz w zakładce Formularz."
        inline
      />
    )
  }

  return (
    <>
      <HowTo
        id="deli-order"
        title="Jak złożyć zamówienie"
        className="mb-6"
        steps={[
          <>
            Wybierz <strong className="font-semibold text-foreground">dział</strong> w zakładkach i wpisz ilości przy
            produktach. Puste pola się nie liczą.
          </>,
          <>
            Brakuje czegoś? Na dole działu w polu <strong className="font-semibold text-foreground">Spoza formularza</strong>{' '}
            wyszukaj produkt i dopisz ilość.
          </>,
          <>
            Na dole strony wybierz <strong className="font-semibold text-foreground">datę dostawy</strong> i kliknij Wyślij
            zamówienie. Każdy dział dostaje osobną kartkę.
          </>,
        ]}
      />
      {suppliers.length === 1 ? (
        <p className="mb-4 text-sm text-muted-foreground">
          Zamawiasz u <span className="font-medium text-foreground tabular-nums">{storeLabel(supplier.store)}</span>
          {supplier.own && ' (Twój formularz)'}
        </p>
      ) : (
        <div role="radiogroup" aria-label="Zamawiasz u" className="mb-5 flex flex-wrap items-center gap-2">
          <span className="mr-1 text-sm text-muted-foreground">Zamawiasz u</span>
          {suppliers.map((option) => (
            <SupplierChip
              key={option.store.id}
              supplier={option}
              selected={option.store.id === supplier.store.id}
              onSelect={() => dispatch(selectSupplier({ storeId: store.id, supplierId: option.store.id }))}
            />
          ))}
        </div>
      )}
      {/* Każdy dostawca ma własny szkic w Reduxie; klucz zeruje tylko stan widoku (podświetlanie błędów). */}
      <SupplierOrder key={supplier.store.id} storeId={store.id} supplier={supplier} />
    </>
  )
}

function SupplierChip({ supplier, selected, onSelect }: { supplier: DeliSupplier; selected: boolean; onSelect: () => void }) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onSelect}
      className={cn(
        'rounded-xl border px-3.5 py-2 text-left text-sm transition-[background-color,border-color] duration-150 outline-none hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50',
        selected && 'border-foreground/30 bg-muted',
      )}
    >
      <span className="block font-medium tabular-nums">{storeLabel(supplier.store)}</span>
      <span className="block text-xs text-muted-foreground">{supplier.own ? 'Twój formularz' : 'Formularz innego sklepu'}</span>
    </button>
  )
}

function SupplierOrder({ storeId, supplier }: { storeId: string; supplier: DeliSupplier }) {
  const { data: form, isPending, isError, refetch } = useSupplierForm(storeId, supplier.store.id)
  const submit = useSubmitOrder(storeId, supplier.store.id)
  const navigate = useNavigate()
  // Wpisane ilości są w Reduxie: wyjście z zakładki czy modułu ich nie kasuje.
  const key = draftKey(storeId, supplier.store.id)
  const draft = useAppSelector((state) => state.deliOrderDraft.drafts[key]) ?? EMPTY_DRAFT
  const dispatch = useAppDispatch()
  const [showErrors, setShowErrors] = useState(false)
  const { quantities, extras, activeSectionId } = draft
  // Szkic sprzed kilku dni może mieć datę, której nie da się już wybrać.
  const deliveryDate = draft.deliveryDate >= todayIso() ? draft.deliveryDate : ''
  const update = (change: Partial<typeof draft>) => dispatch(updateDraft({ key, change }))

  if (isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać formularza"
        action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
        inline
      />
    )
  }
  if (isPending) {
    return <div aria-hidden className="h-40 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  }

  const units = unitsByItem(form)
  const sectionIds = new Set(form.sections.map((section) => section.id))
  // Formularz mógł się zmienić od wpisania ilości: pozycje, których już w nim nie ma, pomijamy.
  const lines = Object.entries(quantities)
    .filter(([itemId, value]) => value.trim() !== '' && units[itemId])
    .map(([itemId, value]) => ({ itemId, parsed: parseQuantity(value, units[itemId]) }))
  const extraLines = extras
    .filter((extra) => extra.quantity.trim() !== '' && sectionIds.has(extra.sectionId))
    .map((extra) => ({ extra, parsed: parseQuantity(extra.quantity, extra.unit) }))
  // Ilość 0 nie jest błędem - taka pozycja po prostu nie trafia do zamówienia.
  const invalid = [...lines, ...extraLines].filter((line) => line.parsed.error)
  const filled = lines.filter((line) => !line.parsed.error && line.parsed.value! > 0)
  const filledExtras = extraLines.filter((line) => !line.parsed.error && line.parsed.value! > 0)
  const positions = filled.length + filledExtras.length
  const activeSection = form.sections.find((section) => section.id === activeSectionId) ?? form.sections[0]

  const onSubmit = () => {
    setShowErrors(true)
    if (invalid.length > 0 || positions === 0 || !deliveryDate) return
    submit.mutate(
      {
        deliveryDate,
        items: filled.map((line) => ({ itemId: line.itemId, quantity: line.parsed.value! })),
        extras: filledExtras.map(({ extra, parsed }) => ({
          productId: extra.product.id,
          sectionId: extra.sectionId,
          unit: extra.unit,
          quantity: parsed.value!,
        })),
      },
      {
        onSuccess: (order) => {
          toast.success('Zamówienie wysłane', {
            description: `Do sklepu ${storeLabel(supplier.store)}, dostawa ${formatLongDate(order.deliveryDate)}.`,
          })
          dispatch(clearDraft(key))
          navigate(deliPaths.orderDetail(order.id))
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się wysłać zamówienia'),
      },
    )
  }

  if (form.sections.length === 0) {
    return <FullPageMessage title="Formularz jest pusty" description="Dostawca jeszcze go nie przygotował." inline />
  }

  const onClear = () => {
    dispatch(clearDraft(key))
    setShowErrors(false)
    toast('Wyczyszczono zamówienie', {
      action: { label: 'Cofnij', onClick: () => dispatch(restoreDraft({ key, draft })) },
    })
  }

  return (
    <>
      <div className="mb-4 flex min-h-9 flex-wrap items-center justify-between gap-x-4 gap-y-2">
        <p className="text-sm text-muted-foreground">
          {hasDraftContent(draft)
            ? 'Wpisane ilości zostają, gdy przejdziesz do innej zakładki. Znikną po wysłaniu albo wyczyszczeniu.'
            : 'Wpisane ilości zostaną zapamiętane, nawet gdy przejdziesz do innej zakładki.'}
        </p>
        {hasDraftContent(draft) && (
          <Button variant="outline" onClick={onClear}>
            <RotateCcwIcon />
            Wyczyść zamówienie
          </Button>
        )}
      </div>

      <Tabs.Root
        value={activeSection?.id ?? null}
        onValueChange={(value) => update({ activeSectionId: value as string })}
      >
        <div className="mb-5 flex items-end gap-3 border-b">
          <span className="hidden shrink-0 pb-3 text-sm text-muted-foreground sm:block">Dział:</span>
          <Tabs.List aria-label="Działy" className="-mb-px flex min-w-0 gap-1 overflow-x-auto">
            {form.sections.map((section) => {
              const count =
                section.categories
                  .flatMap((category) => category.products)
                  .filter((product) => (quantities[product.id] ?? '').trim() !== '').length +
                extras.filter((extra) => extra.sectionId === section.id && extra.quantity.trim() !== '').length
              return (
                <Tabs.Tab
                  key={section.id}
                  value={section.id}
                  className="flex shrink-0 items-center gap-2 border-b-2 border-transparent px-4 pt-2 pb-2.5 text-base font-medium whitespace-nowrap text-muted-foreground transition-colors duration-150 outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 data-active:border-foreground data-active:text-foreground"
                >
                  {section.name}
                  {count > 0 && (
                    <span className="rounded-full bg-foreground px-1.5 text-[0.7rem] leading-4 font-semibold text-background tabular-nums">
                      {count}
                    </span>
                  )}
                </Tabs.Tab>
              )
            })}
          </Tabs.List>
        </div>

        {form.sections.map((section) => (
          <Tabs.Panel key={section.id} value={section.id} className="grid grid-cols-1 gap-5 outline-none">
            {section.categories.length === 0 && (
              <p className="text-sm text-muted-foreground">W tym dziale nie ma jeszcze produktów.</p>
            )}
            {section.categories.map((category) => (
              <section
                key={category.id}
                id={categoryAnchorId(category.id)}
                aria-label={category.name}
                className="scroll-mt-20"
              >
                <h2 className="mb-2 text-base font-semibold">{category.name}</h2>
                <Card className="gap-0 divide-y py-0">
                  {category.products.length === 0 && (
                    <p className="px-4 py-5 text-center text-sm text-muted-foreground">Brak produktów w tej kategorii.</p>
                  )}
                  {category.products.map((product) => {
                    const value = quantities[product.id] ?? ''
                    const error = showErrors && value.trim() !== '' ? parseQuantity(value, product.orderUnit).error : undefined
                    return (
                      <div key={product.id} className="flex items-center gap-4 px-4 py-2.5">
                        <div className="min-w-0 flex-1">
                          <div className="truncate text-sm font-medium">{product.name ?? 'Bez nazwy'}</div>
                          <div className="truncate text-xs text-muted-foreground tabular-nums">
                            {product.brand && `${product.brand} · `}EAN {product.ean}
                          </div>
                          {error && <div className="text-xs text-destructive">{error}</div>}
                        </div>
                        <QuantityInput
                          unit={product.orderUnit}
                          value={value}
                          invalid={Boolean(error)}
                          label={product.name ?? product.ean}
                          onChange={(value) => dispatch(setDraftQuantity({ key, itemId: product.id, value }))}
                        />
                      </div>
                    )
                  })}
                </Card>
              </section>
            ))}
            <ExtrasSection
              storeId={storeId}
              supplier={supplier}
              sectionId={section.id}
              extras={extras}
              showErrors={showErrors}
              onChange={(next) => update({ extras: next })}
            />
          </Tabs.Panel>
        ))}
      </Tabs.Root>
      {activeSection && (
        <CategoryNav
          aboveStickyFooter
          items={activeSection.categories.map((category) => ({
            id: category.id,
            name: category.name,
            count: category.products.length,
          }))}
        />
      )}

      <div className="sticky bottom-0 z-10 -mx-4 mt-6 border-t bg-background/95 px-4 py-4 backdrop-blur-sm md:-mx-6 md:px-6">
        {/* Na telefonie data i przycisk w jednym wierszu, status pod nimi - stopka nie zasłania produktów. */}
        <div className="flex flex-wrap items-start gap-x-3 gap-y-2 sm:flex-nowrap sm:gap-x-4">
          <DateField
            className="min-w-0 flex-1 sm:w-80 sm:flex-none"
            label="Data dostawy"
            value={deliveryDate}
            onChange={(value) => update({ deliveryDate: value })}
            min={todayIso()}
            error={showErrors && !deliveryDate ? 'Wybierz, na kiedy ma przyjść zamówienie' : undefined}
          />
          <p
            className="order-last w-full text-sm text-muted-foreground tabular-nums sm:order-none sm:w-auto sm:flex-1 sm:self-center sm:text-right"
            aria-live="polite"
          >
            {positions > 0 && <span className="font-medium text-foreground">Pozycji: {positions}</span>}
            {positions > 0 && ' · '}
            {showErrors && invalid.length > 0 ? (
              <span className="text-destructive">popraw ilości zaznaczone na czerwono ({invalid.length})</span>
            ) : positions === 0 ? (
              'Wpisz ilość przy co najmniej jednym produkcie'
            ) : !deliveryDate ? (
              'wybierz datę dostawy'
            ) : (
              `dostawa ${formatLongDate(deliveryDate)}`
            )}
          </p>
          <Button size="xl" className="shrink-0" loading={submit.isPending} onClick={onSubmit}>
            <SendIcon />
            Wyślij<span className="max-sm:hidden"> zamówienie</span>
          </Button>
        </div>
      </div>
    </>
  )
}

function unitsByItem(form: OrderForm) {
  const units: Record<string, 'KG' | 'SZT'> = {}
  for (const section of form.sections) {
    for (const category of section.categories) {
      for (const product of category.products) units[product.id] = product.orderUnit
    }
  }
  return units
}

/**
 * Pozycje spoza formularza: sklep wyszukuje je w kartotece dostawcy i dopisuje na dole działu, bo kartka
 * z wydruku trafia do działu (w zamówieniu i na wydruku to kategoria "Spoza formularza" w tym dziale).
 * `extras` to pozycje ze wszystkich działów - produkt można dopisać tylko raz.
 */
function ExtrasSection({
  storeId,
  supplier,
  sectionId,
  extras,
  showErrors,
  onChange,
}: {
  storeId: string
  supplier: DeliSupplier
  sectionId: string
  extras: DraftExtra[]
  showErrors: boolean
  onChange: (extras: DraftExtra[]) => void
}) {
  const update = (productId: string, change: Partial<DraftExtra>) =>
    onChange(extras.map((extra) => (extra.product.id === productId ? { ...extra, ...change } : extra)))

  return (
    <section aria-labelledby={`extras-${sectionId}`} className="mt-3">
      <h2 id={`extras-${sectionId}`} className="text-base font-semibold">
        Spoza formularza
      </h2>
      <p className="mt-0.5 mb-3 text-sm text-muted-foreground">
        Rzeczy, których nie ma w formularzu, trafią do tego działu. Szukasz w kartotece sklepu{' '}
        {storeLabel(supplier.store)}.
      </p>
      <Card className="gap-0 divide-y py-0">
        {extras.filter((extra) => extra.sectionId === sectionId).map((extra) => {
          const error =
            showErrors && extra.quantity.trim() !== '' ? parseQuantity(extra.quantity, extra.unit).error : undefined
          const label = extra.product.name ?? extra.product.ean
          return (
            <div key={extra.product.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5">
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{extra.product.name ?? 'Bez nazwy'}</div>
                <div className="truncate text-xs text-muted-foreground tabular-nums">
                  {extra.product.brand && `${extra.product.brand} · `}EAN {extra.product.ean}
                </div>
                {error && <div className="text-xs text-destructive">{error}</div>}
              </div>
              <UnitToggle value={extra.unit} label={label} onChange={(unit) => update(extra.product.id, { unit })} />
              <QuantityInput
                unit={extra.unit}
                value={extra.quantity}
                invalid={Boolean(error)}
                label={label}
                onChange={(quantity) => update(extra.product.id, { quantity })}
              />
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Usuń ${label} z zamówienia`}
                title="Usuń"
                onClick={() => onChange(extras.filter((item) => item.product.id !== extra.product.id))}
              >
                <XIcon />
              </Button>
            </div>
          )
        })}
        <div className="p-3">
          <ProductSearchField
            source={{ storeId, supplierId: supplier.store.id }}
            addedProductIds={new Set(extras.map((extra) => extra.product.id))}
            placeholder="Dopisz produkt spoza formularza: nazwa, marka, EAN lub ITM8"
            onAdd={(product) =>
              onChange([
                ...extras,
                { product, sectionId, unit: product.unit?.toLowerCase() === 'kg' ? 'KG' : 'SZT', quantity: '' },
              ])
            }
          />
        </div>
      </Card>
    </section>
  )
}
