import { useState } from 'react'
import {
  closestCenter,
  DndContext,
  type DragEndEvent,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { CrownIcon, GripVerticalIcon, PencilIcon, PlusIcon, SendIcon, Trash2Icon, UsersIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { LoadingPanel } from '@/components/LoadingPanel'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { useStoreRoleChange, useStoreRoles } from '@/features/store-roles/api'
import { RankFormDialog } from '@/features/store-roles/RankFormDialog'
import { useStorePartners } from '@/features/store-settings/api'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { usePermissionCategories } from '@/features/tools/categories'
import { ApiError } from '@/lib/api'
import type { StoreRole, StoreRoleOffer, StoreRoles } from '@/lib/types'
import { cn } from '@/lib/utils'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Hierarchia wybranego sklepu: rangi od najwyższej (Aderon zawsze na górze), kolejność przeciągnięciem, rangi poza
 * hierarchią osobno, przesłanie hierarchii sklepom zaprzyjaźnionym i przyjęcie hierarchii od nich.
 */
export function HierarchyPage() {
  const store = useCurrentStore()
  const roles = useStoreRoles(store?.id)

  if (!store) {
    return <FullPageMessage title="Brak sklepu" description="Wybierz sklep, żeby zobaczyć jego hierarchię." inline />
  }
  return (
    <>
      <PageHeader
        title="Hierarchia i rangi"
        description={`Sklep ${storeLabel(store)}. Kto stoi wyżej, zarządza kontami i rangami pod sobą i zleca im zadania.`}
      />
      {roles.isError ? (
        <FullPageMessage
          title="Nie udało się wczytać hierarchii"
          action={{ label: 'Spróbuj ponownie', onClick: () => roles.refetch() }}
          inline
        />
      ) : !roles.data ? (
        <LoadingPanel className="h-96 rounded-xl" />
      ) : (
        <Hierarchy key={store.id} storeId={store.id} data={roles.data} />
      )}
    </>
  )
}

function Hierarchy({ storeId, data }: { storeId: string; data: StoreRoles }) {
  const change = useStoreRoleChange(storeId)
  const [editor, setEditor] = useState<{ open: boolean; rank?: StoreRole }>({ open: false })
  const [offering, setOffering] = useState(false)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const aderon = data.roles.find((rank) => rank.admin)
  const hierarchy = data.roles.filter((rank) => !rank.admin && !rank.outsideHierarchy)
  const outside = data.roles.filter((rank) => rank.outsideHierarchy)
  const mine = data.roles.find((rank) => rank.id === data.myRoleId)
  const myIndex = mine && !mine.admin && !mine.outsideHierarchy ? hierarchy.indexOf(mine) : -1
  /** Przestawia Aderon i właściciel każdą rangę, pozostali z edycją Pracowników tylko te pod sobą. */
  const movable = (rank: StoreRole) => data.admin || (data.canEdit && myIndex >= 0 && hierarchy.indexOf(rank) > myIndex)

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return
    const ids = hierarchy.map((rank) => rank.id)
    const next = arrayMove(ids, ids.indexOf(String(active.id)), ids.indexOf(String(over.id)))
    if (!data.admin && next.slice(0, myIndex + 1).some((id, index) => id !== ids[index])) {
      toast.error('Przestawiasz tylko rangi poniżej swojej')
      return
    }
    change.mutate(
      { type: 'order', roleIds: next },
      {
        onSuccess: () =>
          toast.success('Zmieniono kolejność rang', {
            action: { label: 'Cofnij', onClick: () => change.mutate({ type: 'order', roleIds: ids }) },
          }),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się zmienić kolejności')),
      },
    )
  }

  const remove = (rank: StoreRole) =>
    change.mutate(
      { type: 'delete', id: rank.id },
      {
        onSuccess: () =>
          toast.success(`Usunięto rangę „${rank.name}”`, {
            action: { label: 'Przywróć', onClick: () => change.mutate({ type: 'restore', id: rank.id }) },
          }),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć rangi')),
      },
    )

  const rowProps = {
    mineId: data.myRoleId,
    onEdit: (rank: StoreRole) => setEditor({ open: true, rank }),
    onDelete: remove,
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap gap-2">
        {data.canEdit && (
          <Button size="lg" onClick={() => setEditor({ open: true })}>
            <PlusIcon />
            Nowa ranga
          </Button>
        )}
        {data.admin && (
          <Button size="lg" variant="outline" onClick={() => setOffering(true)}>
            <SendIcon />
            Prześlij sklepom zaprzyjaźnionym
          </Button>
        )}
      </div>

      {data.incoming.map((offer) => (
        <IncomingOffer key={offer.id} storeId={storeId} offer={offer} admin={data.admin} />
      ))}

      <section aria-labelledby="hierarchy-heading" className="grid gap-3">
        <h2 id="hierarchy-heading" className="text-lg font-semibold tracking-tight">
          Hierarchia
        </h2>
        <Card className="gap-0 py-0">
          <ul className="divide-y">
            {aderon && <RankRow rank={aderon} index={0} fixed {...rowProps} />}
            <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
              <SortableContext items={hierarchy.map((rank) => rank.id)} strategy={verticalListSortingStrategy}>
                {hierarchy.map((rank, index) => (
                  <SortableRank key={rank.id} rank={rank} index={index + 1} movable={movable(rank)} {...rowProps} />
                ))}
              </SortableContext>
            </DndContext>
          </ul>
        </Card>
        <p className="px-1 text-sm text-muted-foreground">
          Przeciągnij rangę, żeby zmienić, kto jest pod kim. Właściciel aplikacji stoi ponad całą hierarchią.
        </p>
      </section>

      {outside.length > 0 && (
        <section aria-labelledby="outside-heading" className="grid gap-3">
          <div>
            <h2 id="outside-heading" className="text-lg font-semibold tracking-tight">
              Poza hierarchią
            </h2>
            <p className="text-sm text-muted-foreground">
              Same uprawnienia: prowadzą konta w hierarchii (poza Aderonem), ale nie zlecają zadań. Zmienia je Aderon i
              właściciel.
            </p>
          </div>
          <Card className="gap-0 py-0">
            <ul className="divide-y">
              {outside.map((rank) => (
                <RankRow key={rank.id} rank={rank} fixed {...rowProps} />
              ))}
            </ul>
          </Card>
        </section>
      )}

      {data.outgoing.length > 0 && (
        <section aria-labelledby="outgoing-heading" className="grid gap-3">
          <h2 id="outgoing-heading" className="text-lg font-semibold tracking-tight">
            Przesłane, czekają na przyjęcie
          </h2>
          <Card className="gap-0 py-0">
            <ul className="divide-y">
              {data.outgoing.map((offer) => (
                <li key={offer.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                  <span>
                    Sklep{' '}
                    <span className="font-medium">
                      {storeLabel({ number: offer.storeNumber, name: offer.storeName })}
                    </span>
                  </span>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={!data.admin}
                    onClick={() =>
                      change.mutate(
                        { type: 'cancel', offerId: offer.id },
                        { onError: (error) => toast.error(errorMessage(error, 'Nie udało się wycofać')) },
                      )
                    }
                  >
                    Wycofaj
                  </Button>
                </li>
              ))}
            </ul>
          </Card>
        </section>
      )}

      <RankFormDialog
        storeId={storeId}
        open={editor.open}
        rank={editor.rank}
        admin={data.admin}
        onOpenChange={(open) => setEditor((current) => ({ ...current, open }))}
      />
      <OfferDialog storeId={storeId} open={offering} onOpenChange={setOffering} />
    </div>
  )
}

type RowProps = {
  rank: StoreRole
  /** Miejsce w hierarchii (Aderon 0), brak = poza hierarchią. */
  index?: number
  mineId: string | null
  onEdit: (rank: StoreRole) => void
  onDelete: (rank: StoreRole) => void
}

function SortableRank({ movable, ...props }: RowProps & { movable: boolean }) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({
    id: props.rank.id,
    disabled: !movable,
  })
  return (
    <RankRow
      {...props}
      rowRef={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      dragging={isDragging}
      handle={
        movable ? (
          <button
            type="button"
            ref={setActivatorNodeRef}
            aria-label={`Przenieś: ${props.rank.name}`}
            title="Przeciągnij, żeby zmienić miejsce w hierarchii"
            className="inline-flex size-8 shrink-0 cursor-grab touch-none items-center justify-center rounded-md text-muted-foreground transition-colors outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 active:cursor-grabbing"
            {...attributes}
            {...listeners}
          >
            <GripVerticalIcon className="size-4" />
          </button>
        ) : undefined
      }
    />
  )
}

function RankRow({
  rank,
  index,
  mineId,
  onEdit,
  onDelete,
  fixed,
  handle,
  rowRef,
  style,
  dragging,
}: RowProps & {
  fixed?: boolean
  handle?: React.ReactNode
  rowRef?: (node: HTMLElement | null) => void
  style?: React.CSSProperties
  dragging?: boolean
}) {
  const { data: categories = [] } = usePermissionCategories()
  const granted = categories.filter((category) => rank.permissions[category.code])
  return (
    <li
      ref={rowRef}
      style={style}
      className={cn(
        'flex flex-wrap items-center gap-3 bg-card px-3 py-3 sm:flex-nowrap',
        dragging && 'relative z-10 rounded-lg shadow-md',
      )}
    >
      {handle ?? <span aria-hidden className="size-8 shrink-0" />}
      <span className="w-6 shrink-0 text-right text-sm text-muted-foreground tabular-nums">
        {index === undefined ? (
          ''
        ) : index === 0 ? (
          <CrownIcon aria-label="Najwyżej" className="ml-auto size-4" />
        ) : (
          index
        )}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2 font-medium">
          {rank.name}
          {rank.id === mineId && <Badge variant="secondary">Twoja ranga</Badge>}
          {fixed && rank.admin && <span className="text-xs font-normal text-muted-foreground">zawsze na górze</span>}
        </div>
        <div className="truncate text-xs text-muted-foreground">
          {rank.admin
            ? 'Wszystkie uprawnienia w tym sklepie, bez ustawień firmy'
            : granted.length === 0
              ? 'Bez dostępu do narzędzi'
              : granted
                  .map(
                    (category) => `${category.label}${rank.permissions[category.code] === 'EDIT' ? ' (edycja)' : ''}`,
                  )
                  .join(', ')}
        </div>
      </div>
      <span
        className="flex shrink-0 items-center gap-1 text-sm text-muted-foreground tabular-nums"
        title="Konta z tą rangą"
      >
        <UsersIcon aria-hidden className="size-4" />
        {rank.userCount}
      </span>
      <div className="flex shrink-0 gap-1">
        {rank.editable && (
          <Button variant="ghost" size="icon-sm" aria-label={`Edytuj rangę ${rank.name}`} onClick={() => onEdit(rank)}>
            <PencilIcon />
          </Button>
        )}
        {rank.deletable && (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Usuń rangę ${rank.name}`}
            disabled={rank.userCount > 0}
            title={rank.userCount > 0 ? 'Najpierw zmień rangę kontom, które ją mają' : undefined}
            onClick={() => onDelete(rank)}
          >
            <Trash2Icon />
          </Button>
        )}
      </div>
    </li>
  )
}

function IncomingOffer({ storeId, offer, admin }: { storeId: string; offer: StoreRoleOffer; admin: boolean }) {
  const change = useStoreRoleChange(storeId)
  const from = storeLabel({ number: offer.storeNumber, name: offer.storeName })
  const accept = () =>
    change.mutate(
      { type: 'accept', offerId: offer.id },
      {
        onSuccess: () =>
          toast.success(`Przyjęto hierarchię sklepu ${from}`, {
            action: { label: 'Cofnij', onClick: () => change.mutate({ type: 'undo', offerId: offer.id }) },
          }),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się przyjąć hierarchii')),
      },
    )
  return (
    <Card className="gap-3 border-sky-300 bg-sky-50 px-5 py-4 dark:border-sky-800 dark:bg-sky-950/50">
      <div>
        <div className="font-medium">Sklep {from} przesłał swoją hierarchię</div>
        <div className="text-sm text-muted-foreground">
          {offer.roleNames.join(' › ')}. Przyjęcie zastąpi nazwy i uprawnienia rang o tych samych miejscach, dopisze
          brakujące, a Twoje pozostałe rangi zostaną pod nimi.
        </div>
      </div>
      {admin ? (
        <div className="flex flex-wrap gap-2">
          <Button onClick={accept} loading={change.isPending}>
            Przyjmij
          </Button>
          <Button
            variant="outline"
            onClick={() =>
              change.mutate(
                { type: 'reject', offerId: offer.id },
                { onError: (error) => toast.error(errorMessage(error, 'Nie udało się odrzucić')) },
              )
            }
          >
            Odrzuć
          </Button>
        </div>
      ) : (
        <div className="text-sm text-muted-foreground">Przyjmuje Aderon albo właściciel.</div>
      )}
    </Card>
  )
}

function OfferDialog({
  storeId,
  open,
  onOpenChange,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const partners = useStorePartners(storeId, open)
  const change = useStoreRoleChange(storeId)
  const [chosen, setChosen] = useState<string[]>([])
  const accepted = (partners.data?.partners ?? []).filter((partner) => partner.acceptedAt)

  const send = () =>
    change.mutate(
      { type: 'offer', storeIds: chosen },
      {
        onSuccess: () => {
          toast.success(
            chosen.length === 1 ? 'Przesłano hierarchię' : `Przesłano hierarchię do ${chosen.length} sklepów`,
          )
          setChosen([])
          onOpenChange(false)
        },
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się przesłać hierarchii')),
      },
    )

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-md">
        <DialogHeader>
          <DialogTitle className="text-lg">Prześlij hierarchię</DialogTitle>
          <DialogDescription>
            Sklep zaprzyjaźniony dostanie propozycję z rangami, ich kolejnością i uprawnieniami. Zmieni się u niego
            dopiero po przyjęciu, a przyjęcie da się cofnąć.
          </DialogDescription>
        </DialogHeader>
        {partners.isPending ? (
          <LoadingPanel className="h-24 rounded-lg" />
        ) : accepted.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Ten sklep nie ma jeszcze sklepów zaprzyjaźnionych. Zaprosisz je w Ustawieniach sklepu.
          </p>
        ) : (
          <ul className="grid gap-1" data-drag-select-scope>
            {accepted.map((partner) => (
              <li key={partner.id}>
                <label className="flex cursor-pointer items-center gap-3 rounded-lg px-2 py-2 hover:bg-muted">
                  <input
                    type="checkbox"
                    className="size-4 shrink-0 accent-blue-600"
                    checked={chosen.includes(partner.store.id)}
                    onChange={(event) => {
                      const checked = event.target.checked
                      setChosen((current) =>
                        checked ? [...current, partner.store.id] : current.filter((id) => id !== partner.store.id),
                      )
                    }}
                  />
                  {storeLabel(partner.store)}
                </label>
              </li>
            ))}
          </ul>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Anuluj
          </Button>
          <Button disabled={chosen.length === 0} loading={change.isPending} onClick={send}>
            Prześlij
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
