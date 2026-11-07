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
import { GripVerticalIcon, LayoutDashboardIcon, RotateCcwIcon } from 'lucide-react'
import { toast } from 'sonner'

import { orderByKeys, SIDEBAR_GROUPS, type SidebarGroup } from '@/components/layout/navigation'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCurrentUser } from '@/features/auth/api'
import { useSaveSidebarOrder } from '@/features/store-settings/api'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/utils'

const DEFAULT_ORDER = SIDEBAR_GROUPS.map((group) => group.key)

const keysOf = (order: string[] | undefined) => orderByKeys(SIDEBAR_GROUPS, order).map((group) => group.key)

/** Czy sklep ma kolejność inną niż domyślna (nowe kategorie stoją wtedy na końcu). */
export const hasCustomSidebarOrder = (order: string[] | undefined) =>
  keysOf(order).some((key, index) => key !== DEFAULT_ORDER[index])

/**
 * Kolejność kategorii menu bocznego dla całego sklepu. Lista ma wszystkie kategorie, także te, których ta osoba nie
 * widzi, bo zapisana kolejność obowiązuje wszystkich. Zmiana zapisuje się od razu i od razu widać ją w menu.
 */
export function SidebarOrderEditor({ storeId }: { storeId: string }) {
  const me = useCurrentUser()
  const save = useSaveSidebarOrder(storeId)
  const [order, setOrder] = useState(() => keysOf(me.sidebarOrders[storeId]))
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )
  const custom = hasCustomSidebarOrder(order)

  const apply = (next: string[], onSuccess?: () => void) => {
    const previous = order
    setOrder(next)
    save.mutate(next, {
      onSuccess,
      onError: (error) => {
        setOrder(previous)
        toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać kolejności')
      },
    })
  }

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return
    apply(arrayMove(order, order.indexOf(String(active.id)), order.indexOf(String(over.id))))
  }

  const reset = () => {
    const previous = order
    apply(DEFAULT_ORDER, () =>
      toast.success('Przywrócono domyślną kolejność', { action: { label: 'Cofnij', onClick: () => apply(previous) } }),
    )
  }

  return (
    <div className="grid gap-3">
      <Card className="gap-0 py-0">
        <ul className="divide-y">
          <li className="flex items-center gap-3 px-3 py-2 text-muted-foreground">
            <span aria-hidden className="size-8 shrink-0" />
            <LayoutDashboardIcon aria-hidden className="size-4" />
            <span>Pulpit</span>
            <span className="ml-auto text-sm">zawsze na górze</span>
          </li>
          <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
            <SortableContext items={order} strategy={verticalListSortingStrategy}>
              {orderByKeys(SIDEBAR_GROUPS, order).map((group) => (
                <GroupRow key={group.key} group={group} />
              ))}
            </SortableContext>
          </DndContext>
        </ul>
      </Card>
      <div>
        <Button variant="outline" disabled={!custom} onClick={reset}>
          <RotateCcwIcon />
          Przywróć domyślną kolejność
        </Button>
      </div>
    </div>
  )
}

function GroupRow({ group }: { group: SidebarGroup }) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({
    id: group.key,
  })
  const Icon = group.icon
  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cn('flex items-center gap-3 bg-card px-3 py-2', isDragging && 'relative z-10 rounded-lg shadow-md')}
    >
      <button
        type="button"
        ref={setActivatorNodeRef}
        aria-label={`Przenieś: ${group.label}`}
        title="Przeciągnij, żeby zmienić miejsce"
        className="inline-flex size-8 shrink-0 cursor-grab touch-none items-center justify-center rounded-md text-muted-foreground transition-colors outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 active:cursor-grabbing"
        {...attributes}
        {...listeners}
      >
        <GripVerticalIcon className="size-4" />
      </button>
      <Icon aria-hidden className="size-4 text-muted-foreground" />
      <span className="font-medium">{group.label}</span>
    </li>
  )
}
