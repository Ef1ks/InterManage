import { useEffect, useRef, useState } from 'react'
import { CheckCheckIcon, MergeIcon, PencilIcon, RotateCcwIcon, SaveIcon, SplitIcon, Trash2Icon } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCan, useMe } from '@/features/auth/api'
import { useCorrectOrder, useDeleteOrder, useMarkOrderRead, useOrder, useOrders } from '@/features/deli-order/api'
import { DeliveryDateDialog } from '@/features/deli-order/DeliveryDateDialog'
import { FulfillOrderDialog } from '@/features/deli-order/FulfillOrderDialog'
import { groupItems } from '@/features/deli-order/group-items'
import { mergeableGroups, MergeWithSiblings, ordersCount, useMergeWithUndo } from '@/features/deli-order/MergeOrders'
import { OrderDownloads } from '@/features/deli-order/OrderDownloads'
import { OrderMovementDocuments } from '@/features/deli-order/OrderMovementDocuments'
import { OrderStatusBadge } from '@/features/deli-order/OrderStatusBadge'
import { deliPaths } from '@/features/deli-order/paths'
import { QuantityInput, formatQuantity, parseQuantity } from '@/features/deli-order/QuantityInput'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { formatLongDate, formatShortDate } from '@/lib/dates'
import type { DeliOrder, DeliOrderItem } from '@/lib/types'

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'medium', timeStyle: 'short' })

export function OrderDetail({ storeId }: { storeId: string }) {
  const orderId = useParams().orderId ?? ''
  const { data: order, isPending, isError } = useOrder(storeId, orderId)

  if (isError) {
    return <FullPageMessage title="Nie znaleziono zamówienia" description="Mogło zostać złożone w innym sklepie." inline />
  }
  if (isPending) {
    return <div aria-hidden className="h-40 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
  }
  // Klucz: po zapisie poprawek pola startują od nowych ilości.
  return <OrderView key={order.correctedAt ?? 'original'} storeId={storeId} order={order} />
}

function OrderView({ storeId, order }: { storeId: string; order: DeliOrder }) {
  const incoming = order.formStore.id === storeId
  const sender = order.store.id === storeId
  const canEdit = useCan('DELI_ORDERS', 'EDIT')
  const owner = useMe().data?.owner ?? false
  // Usunięcie działa po jednej stronie: "nasze" ukrywa zamówienie u nas, "ich" widzimy jako status.
  const deletedHere = (sender && order.storeDeletedAt) || (incoming && order.formStoreDeletedAt)
  const deletedThere = !deletedHere && (order.storeDeletedAt || order.formStoreDeletedAt)
  const deleted = Boolean(deletedHere || deletedThere)
  // Połączone w inne albo rozdzielone zamówienie tylko się ogląda; obowiązuje to, z którym je połączono.
  const replaced = Boolean(order.mergedIntoId || order.splitAt)
  const open = order.status !== 'FULFILLED'
  const active = open && !deleted && !replaced
  const editable = incoming && canEdit && active
  // Swoją stronę zamówienia sklep, który je złożył, zmienia z Podglądem, dostawca z Edycją.
  const managesOwnSide = sender || canEdit
  // Zrealizowane usuwa tylko właściciel.
  const deletable = (open || owner) && managesOwnSide && !deletedHere && !replaced
  const correct = useCorrectOrder(storeId, order.id)
  const [fulfilling, setFulfilling] = useState(false)
  const [changingDate, setChangingDate] = useState(false)
  const { undo: split, undoPending: splitting } = useMergeWithUndo(storeId)
  // Inne zamówienia tego sklepu na ten sam dzień, z którymi dostawca może połączyć to.
  const incomingOrders = useOrders(storeId, 'incoming').data
  const siblings = editable
    ? (mergeableGroups(incomingOrders ?? []).find((group) => group.some((other) => other.id === order.id)) ?? [])
        .filter((other) => other.id !== order.id)
    : []

  // Pierwsze otwarcie u dostawcy zmienia status z "nowe" na "odczytane" (raz na wejście).
  const markRead = useMarkOrderRead(storeId, order.id)
  const readRequested = useRef(false)
  useEffect(() => {
    if (!incoming || order.status !== 'NEW' || deleted || replaced || readRequested.current) return
    readRequested.current = true
    markRead.mutate()
  }, [incoming, order.status, deleted, replaced, markRead])
  const { remove, restore } = useDeleteOrder(storeId, order.id)
  const navigate = useNavigate()
  const [draft, setDraft] = useState<Record<string, string>>(() =>
    Object.fromEntries(order.items.map((item) => [item.id, String(item.quantity).replace('.', ',')])),
  )

  const parsed = order.items.map((item) => ({ item, ...parseQuantity(draft[item.id] ?? '', item.unit) }))
  const changed = parsed.filter(({ item, value }) => value !== undefined && value !== item.quantity)
  const hasErrors = parsed.some(({ error }) => error)

  const saveCorrections = () =>
    correct.mutate(
      changed.map(({ item, value }) => ({ id: item.id, quantity: value! })),
      {
        onSuccess: () => toast.success('Zapisano poprawione ilości'),
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać'),
      },
    )

  const restoreOrder = () =>
    toast.promise(restore.mutateAsync(), {
      loading: 'Przywracanie zamówienia…',
      success: 'Zamówienie przywrócone',
      error: (error) => (error instanceof ApiError ? error.message : 'Nie udało się przywrócić zamówienia'),
    })

  // Bez okna z pytaniem: nic nie jest kasowane, a toast pozwala od razu cofnąć.
  const deleteOrder = () =>
    remove.mutate(undefined, {
      onSuccess: () => {
        toast.success('Zamówienie usunięte', {
          description: incoming && !sender
            ? `Zniknęło z Twojej listy. Zamawiający (${storeLabel(order.store)}) zobaczy je jako usunięte.`
            : `Zniknęło z Twojej listy. Dostawca (${storeLabel(order.formStore)}) zobaczy je jako usunięte.`,
          action: { label: 'Przywróć', onClick: restoreOrder },
          duration: 10_000,
        })
        navigate(incoming ? deliPaths.incoming : deliPaths.sent)
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się usunąć zamówienia'),
    })

  return (
    <>
      <BackLink to={incoming ? deliPaths.incoming : deliPaths.sent}>
        {incoming ? 'Przychodzące zamówienia' : 'Wysłane zamówienia'}
      </BackLink>

      <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-3">
            <h2 className="text-xl font-semibold tracking-tight">
              {incoming ? `Od sklepu ${storeLabel(order.store)}` : `Do sklepu ${storeLabel(order.formStore)}`}
            </h2>
            <OrderStatusBadge order={order} incoming={incoming} />
          </div>
          <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-muted-foreground">Dostawa</dt>
            <dd>
              <span className="font-medium">{formatLongDate(order.deliveryDate)}</span>
              {active && managesOwnSide && (
                <Button
                  variant="ghost"
                  size="icon-sm"
                  className="ml-1 -my-1 align-middle"
                  aria-label="Zmień datę dostawy"
                  title="Zmień datę dostawy"
                  onClick={() => setChangingDate(true)}
                >
                  <PencilIcon />
                </Button>
              )}
              {order.deliveryDate !== order.orderedDeliveryDate && (
                <div className="text-xs text-muted-foreground tabular-nums">
                  złożone na {formatShortDate(order.orderedDeliveryDate)}
                  {order.deliveryDateChangedAt && `, zmienione ${dateTime.format(new Date(order.deliveryDateChangedAt))}`}
                  {order.deliveryDateChangedBy && ` · ${order.deliveryDateChangedBy}`}
                </div>
              )}
            </dd>
            <dt className="text-muted-foreground">Złożone</dt>
            <dd className="tabular-nums">
              {dateTime.format(new Date(order.submittedAt))}
              {order.submittedBy && ` · ${order.submittedBy}`}
            </dd>
            {order.readAt && (
              <>
                <dt className="text-muted-foreground">Odczytane</dt>
                <dd className="tabular-nums">
                  {dateTime.format(new Date(order.readAt))}
                  {order.readBy && ` · ${order.readBy}`}
                </dd>
              </>
            )}
            {order.correctedAt && (
              <>
                <dt className="text-muted-foreground">Poprawione</dt>
                <dd className="tabular-nums">
                  {dateTime.format(new Date(order.correctedAt))}
                  {order.correctedBy && ` · ${order.correctedBy}`}
                </dd>
              </>
            )}
            {order.fulfilledAt && (
              <>
                <dt className="text-muted-foreground">Zrealizowane</dt>
                <dd className="tabular-nums">
                  {dateTime.format(new Date(order.fulfilledAt))}
                  {order.fulfilledBy && ` · ${order.fulfilledBy}`}
                </dd>
              </>
            )}
          </dl>
        </div>
        <div className="flex flex-wrap gap-2">
          <OrderDownloads storeId={storeId} orderId={order.id} />
          {editable && (
            <>
            {changed.length > 0 && (
              <Button variant="outline" size="xl" loading={correct.isPending} disabled={hasErrors} onClick={saveCorrections}>
                <SaveIcon />
                Zapisz poprawki ({changed.length})
              </Button>
            )}
            <Button size="xl" onClick={() => setFulfilling(true)}>
              <CheckCheckIcon />
              Potwierdź zrealizowanie
            </Button>
            <FulfillOrderDialog
              storeId={storeId}
              order={order}
              initial={draft}
              open={fulfilling}
              onOpenChange={setFulfilling}
            />
            </>
          )}
          {deletable && (
            <Button
              variant="ghost"
              size="xl"
              className="text-destructive hover:bg-destructive/10 hover:text-destructive"
              loading={remove.isPending}
              onClick={deleteOrder}
            >
              <Trash2Icon />
              Usuń zamówienie
            </Button>
          )}
        </div>
      </div>

      <DeliveryDateDialog storeId={storeId} order={order} open={changingDate} onOpenChange={setChangingDate} />

      {order.mergedIntoId && (
        <div className="mb-6 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border bg-card px-4 py-3">
          <MergeIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
          <p className="min-w-0 flex-1 text-sm font-medium">
            To zamówienie połączono z innym zamówieniem tego sklepu na ten sam dzień - obowiązuje połączone.
          </p>
          <Button variant="outline" render={<Link to={deliPaths.orderDetail(order.mergedIntoId)} />}>
            Otwórz połączone
          </Button>
        </div>
      )}
      {order.splitAt && (
        <div className="mb-6 rounded-xl border bg-card px-4 py-3">
          <p className="text-sm font-medium">Połączenie cofnięto - zamówienia znów są osobno</p>
          <p className="mt-1 text-xs text-muted-foreground">
            Obowiązują zamówienia, z których powstało:{' '}
            {order.mergedFrom.map((source, index) => (
              <span key={source.id}>
                {index > 0 && ', '}
                <Link to={deliPaths.orderDetail(source.id)} className="underline underline-offset-2 hover:text-foreground">
                  złożone {dateTime.format(new Date(source.submittedAt))}
                </Link>
              </span>
            ))}
          </p>
        </div>
      )}
      {order.mergedAt && !order.splitAt && (
        <div className="mb-6 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border bg-card px-4 py-3">
          <MergeIcon aria-hidden className="size-5 shrink-0 text-muted-foreground" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium">Połączone z {ordersCount(order.mergedFrom.length)}</p>
            <p className="text-xs text-muted-foreground tabular-nums">
              {order.mergedFrom
                .map((source) => `${dateTime.format(new Date(source.submittedAt))}${source.submittedBy ? ` · ${source.submittedBy}` : ''} (${source.itemCount} poz.)`)
                .join(', ')}
              {order.mergedAt && `. Połączono ${dateTime.format(new Date(order.mergedAt))}`}
              {order.mergedBy && ` · ${order.mergedBy}`}.
            </p>
          </div>
          {editable && (
            <Button variant="outline" loading={splitting} onClick={() => split(order.id)}>
              <SplitIcon />
              Rozdziel
            </Button>
          )}
        </div>
      )}
      {siblings.length > 0 && <MergeWithSiblings storeId={storeId} orderId={order.id} siblings={siblings} />}

      {deletedHere && (
        <div className="mb-6 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border border-destructive/25 bg-card px-4 py-3">
          <Trash2Icon aria-hidden className="size-5 shrink-0 text-destructive" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium">Usunięto to zamówienie u Ciebie</p>
            <p className="text-xs text-muted-foreground tabular-nums">
              {deletionNote(sender ? order.storeDeletedAt : order.formStoreDeletedAt,
                sender ? order.storeDeletedBy : order.formStoreDeletedBy)}{' '}
              Nie widać go na liście, a druga strona widzi je jako usunięte.
            </p>
          </div>
          {managesOwnSide && (
            <Button variant="outline" loading={restore.isPending} onClick={restoreOrder}>
              <RotateCcwIcon />
              Przywróć
            </Button>
          )}
        </div>
      )}
      {deletedThere && (
        <div className="mb-6 flex items-center gap-4 rounded-xl border border-destructive/25 bg-card px-4 py-3">
          <Trash2Icon aria-hidden className="size-5 shrink-0 text-destructive" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-medium">
              {order.storeDeletedAt
                ? `Zamawiający (${storeLabel(order.store)}) usunął to zamówienie - nie realizuj go`
                : `Dostawca (${storeLabel(order.formStore)}) usunął to zamówienie - nie zostanie zrealizowane`}
            </p>
            <p className="text-xs text-muted-foreground tabular-nums">
              {order.storeDeletedAt
                ? deletionNote(order.storeDeletedAt, order.storeDeletedBy)
                : deletionNote(order.formStoreDeletedAt, order.formStoreDeletedBy)}
            </p>
          </div>
        </div>
      )}

      {editable && (
        <HowTo
          id="deli-incoming"
          title="Jak zrealizować zamówienie"
          className="mb-6"
          steps={[
            <>
              Kliknij <strong className="font-semibold text-foreground">Pobierz → PDF do wydruku</strong>. Każdy dział
              dostaje osobną kartkę z kodami kreskowymi.
            </>,
            <>
              Wydajesz inną ilość albo czegoś brakuje? Zmień liczbę przy produkcie (0 = nie wydajesz) i kliknij{' '}
              <strong className="font-semibold text-foreground">Zapisz poprawki</strong>.
            </>,
            <>
              Gdy towar jest wydany, <strong className="font-semibold text-foreground">przypnij dokument ruchu
              międzymagazynowego</strong>, na którym jedzie, kliknij{' '}
              <strong className="font-semibold text-foreground">Potwierdź zrealizowanie</strong> i wpisz wydane ilości
              (Enter przechodzi do następnego produktu). Plik PROTO6 zapisze się w wybranym folderze i program sklepu sam go wczyta.
            </>,
          ]}
        />
      )}
      {!incoming && active && (
        <p className="mb-6 text-sm text-muted-foreground">
          Zamówienie czeka na realizację w sklepie {storeLabel(order.formStore)}. Jeśli zmieni ilości, zobaczysz je tutaj
          jako przekreślone.
        </p>
      )}
      {!deleted && !replaced && <OrderMovementDocuments storeId={storeId} order={order} />}

      <div className="grid grid-cols-1 gap-6">
        {/* Pozycja poprawiona do zera nie zostanie dostarczona - poza edycją jej nie pokazujemy. */}
        {groupItems(editable ? order.items : order.items.filter((item) => item.quantity > 0)).map(({ section, categories }) => (
          <section key={section} aria-label={section}>
            <h3 className="mb-3 text-sm font-semibold tracking-wide text-muted-foreground uppercase">{section}</h3>
            <div className="grid grid-cols-1 gap-4">
              {categories.map(({ category, items }) => (
                <div key={category}>
                  <h4 className="mb-2 text-base font-semibold">{category}</h4>
                  <Card className="gap-0 divide-y py-0">
                    {items.map((item) => {
                      const error = parsed.find((p) => p.item.id === item.id)?.error
                      return (
                        <div key={item.id} className="flex items-center gap-4 px-4 py-2.5">
                          <div className="min-w-0 flex-1">
                            <div className="truncate text-sm font-medium">{item.name ?? 'Bez nazwy'}</div>
                            <div className="truncate text-xs text-muted-foreground tabular-nums">
                              {item.brand && `${item.brand} · `}EAN {item.ean}
                            </div>
                            {/* Dopisek pod nazwą, a nie obok pola: prawa strona ma stałą szerokość w każdym wierszu. */}
                            {editable && item.quantity !== item.orderedQuantity && (
                              <div className="text-xs text-muted-foreground tabular-nums">
                                zamówiono {formatQuantity(item.orderedQuantity, item.unit)}
                              </div>
                            )}
                            {error && <div className="text-xs text-destructive">{error}</div>}
                          </div>
                          {editable ? (
                            <QuantityInput
                              unit={item.unit}
                              value={draft[item.id] ?? ''}
                              invalid={Boolean(error)}
                              label={item.name ?? item.ean}
                              onChange={(next) => setDraft((current) => ({ ...current, [item.id]: next }))}
                            />
                          ) : (
                            <QuantityView item={item} />
                          )}
                        </div>
                      )
                    })}
                  </Card>
                </div>
              ))}
            </div>
          </section>
        ))}
      </div>

    </>
  )
}

/** "Usunięto 25 paź 2026, 10:12 · Jan Kowalski." */
function deletionNote(at: string | null, by: string | null) {
  if (!at) return ''
  return `Usunięto ${dateTime.format(new Date(at))}${by ? ` · ${by}` : ''}.`
}

function QuantityView({ item }: { item: DeliOrderItem }) {
  const corrected = item.quantity !== item.orderedQuantity
  return (
    <div className="w-28 shrink-0 text-right text-sm tabular-nums sm:w-36">
      <div className="font-medium">{formatQuantity(item.quantity, item.unit)}</div>
      {corrected && (
        <div className="text-xs text-muted-foreground">
          zamówiono <span className="line-through">{formatQuantity(item.orderedQuantity, item.unit)}</span>
        </div>
      )}
    </div>
  )
}
