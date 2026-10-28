import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Tabs } from '@base-ui/react/tabs'
import {
  type Announcements,
  type CollisionDetection,
  closestCenter,
  DndContext,
  type DragEndEvent,
  type DragOverEvent,
  DragOverlay,
  type DragStartEvent,
  KeyboardSensor,
  MeasuringStrategy,
  PointerSensor,
  pointerWithin,
  type UniqueIdentifier,
  useDroppable,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import { arrayMove, SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { CheckIcon, ClipboardPasteIcon, GripVerticalIcon, PencilIcon, PlusIcon, Trash2Icon, XIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
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
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { type MoveChange, useChangeOrderForm, useMoveInOrderForm, useOrderForm, usePasteOrderProducts } from '@/features/deli-order/api'
import { CATEGORY_NAV_GRID, CategoryNav, categoryAnchorId } from '@/features/deli-order/CategoryNav'
import { InvitationsPanel } from '@/features/deli-order/InvitationsPanel'
import { NameDialog, type NameDialogState } from '@/features/deli-order/NameDialog'
import { type PasteDialogState, PasteProductsDialog } from '@/features/deli-order/PasteProductsDialog'
import { ProductSearchField } from '@/features/deli-order/ProductSearchField'
import { UNIT_LABELS, UnitToggle } from '@/features/deli-order/QuantityInput'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { OrderCategory, OrderProduct, OrderSection, OrderUnit, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

type Confirmation = { title: string; description: string; confirmLabel: string; onConfirm: () => void }

/** Formularz własnego sklepu: podgląd, a dla uprawnienia Edycja układanie działów, kategorii, produktów i zaproszeń. */
export function FormEditor({ store }: { store: StoreSummary }) {
  const canEdit = useCan('DELI_ORDERS', 'EDIT')
  const { data: form, isPending, isError, refetch } = useOrderForm(store.id)
  const change = useChangeOrderForm(store.id)
  const paste = usePasteOrderProducts(store.id)
  const move = useMoveInOrderForm(store.id)
  const [pasteDialog, setPasteDialog] = useState<PasteDialogState | null>(null)
  const [editing, setEditing] = useState(false)
  const [activeSectionId, setActiveSectionId] = useState<string | null>(null)
  const [nameDialog, setNameDialog] = useState<NameDialogState | null>(null)
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null)

  const sections = form?.sections ?? []
  const activeSection = sections.find((section) => section.id === activeSectionId) ?? sections[0]
  const isEditing = canEdit && editing

  const onChangeError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const addSection = () =>
    setNameDialog({
      title: 'Nowy dział',
      description: 'Dział to osobna zakładka formularza, np. Rzeźnia, Garmaż albo Inne.',
      label: 'Nazwa działu',
      submitLabel: 'Dodaj dział',
      onSubmit: async (name) => {
        const updated = await change.mutateAsync({ type: 'createSection', name })
        setActiveSectionId(updated.sections.at(-1)?.id ?? null)
      },
    })

  return (
    <>
      <div className="mb-5 flex items-start justify-between gap-4">
        <p className="max-w-xl pt-1 text-sm text-muted-foreground">
          Z tego formularza zamawia sklep {storeLabel(store)} i sklepy, które przyjęły zaproszenie.
        </p>
        {canEdit &&
          (isEditing ? (
            <Button size="lg" onClick={() => setEditing(false)}>
              <CheckIcon />
              Zakończ układanie
            </Button>
          ) : (
            <Button variant="outline" size="lg" className="shrink-0" onClick={() => setEditing(true)}>
              <PencilIcon />
              Ułóż formularz
            </Button>
          ))}
      </div>

      {isEditing && (
        <HowTo
          id="deli-form"
          title="Jak ułożyć formularz"
          className="mb-6"
          steps={[
            <>
              Dodaj <strong className="font-semibold text-foreground">dział</strong> (np. Rzeźnia). Każdy dział to osobna
              zakładka i osobna kartka zamówienia.
            </>,
            <>
              W dziale dodaj <strong className="font-semibold text-foreground">kategorię</strong> (np. Wędliny) i wyszukaj
              produkty z kartoteki po nazwie, EAN albo ITM8.
            </>,
            <>
              Przy produkcie wybierz, czy zamawia się go w <strong className="font-semibold text-foreground">kg czy
              sztukach</strong>. Kolejność zmienisz, <strong className="font-semibold text-foreground">przeciągając
              za uchwyt</strong> kategorię albo produkt, także do innej kategorii działu.
            </>,
            <>
              <strong className="font-semibold text-foreground">Zaproś sklepy</strong> poniżej, żeby mogły zamawiać z tego
              formularza.
            </>,
          ]}
        />
      )}
      {isEditing && <InvitationsPanel storeId={store.id} />}

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać formularza"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : isPending ? (
        <div aria-hidden className="h-40 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : sections.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Formularz jest pusty</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            {canEdit
              ? 'Dodaj pierwszy dział, np. Rzeźnia, a w nim kategorie z produktami.'
              : 'Osoba z uprawnieniem do edycji jeszcze go nie przygotowała.'}
          </p>
          {canEdit && (
            <Button
              size="lg"
              className="mt-2"
              onClick={() => {
                setEditing(true)
                addSection()
              }}
            >
              <PlusIcon />
              Dodaj dział
            </Button>
          )}
        </Card>
      ) : (
        <Tabs.Root value={activeSection?.id ?? null} onValueChange={(value) => setActiveSectionId(value as string)}>
          <div className="mb-5 flex items-end gap-2 border-b">
            <Tabs.List className="relative -mb-px flex min-w-0 gap-1 overflow-x-auto">
              {sections.map((section) => (
                <Tabs.Tab
                  key={section.id}
                  value={section.id}
                  className="shrink-0 border-b-2 border-transparent px-4 pt-2 pb-2.5 text-sm font-medium whitespace-nowrap text-muted-foreground transition-colors duration-150 outline-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 data-active:border-foreground data-active:text-foreground"
                >
                  {section.name}
                </Tabs.Tab>
              ))}
            </Tabs.List>
            {isEditing && (
              <Button variant="ghost" size="sm" className="mb-1.5 shrink-0" onClick={addSection}>
                <PlusIcon />
                Dział
              </Button>
            )}
          </div>

          <div className={CATEGORY_NAV_GRID}>
            <div className="min-w-0">
              {sections.map((section) => (
                <Tabs.Panel key={section.id} value={section.id} className="outline-none">
                  <SectionPanel
                    storeId={store.id}
                    section={section}
                    editing={isEditing}
                    busy={change.isPending}
                    onRename={() =>
                      setNameDialog({
                        title: 'Zmień nazwę działu',
                        label: 'Nazwa działu',
                        initialName: section.name,
                        submitLabel: 'Zapisz',
                        onSubmit: (name) => change.mutateAsync({ type: 'renameSection', sectionId: section.id, name }),
                      })
                    }
                    onDelete={() =>
                      setConfirmation({
                        title: `Usunąć dział „${section.name}”?`,
                        description: `Razem z nim znikną jego kategorie (${section.categories.length}) i listy produktów. Produkty zostają w kartotece.`,
                        confirmLabel: 'Usuń dział',
                        onConfirm: () =>
                          change.mutate({ type: 'deleteSection', sectionId: section.id }, { onError: onChangeError }),
                      })
                    }
                    onAddCategory={() =>
                      setNameDialog({
                        title: `Nowa kategoria w dziale ${section.name}`,
                        label: 'Nazwa kategorii',
                        submitLabel: 'Dodaj kategorię',
                        onSubmit: (name) => change.mutateAsync({ type: 'createCategory', sectionId: section.id, name }),
                      })
                    }
                    onRenameCategory={(category) =>
                      setNameDialog({
                        title: 'Zmień nazwę kategorii',
                        label: 'Nazwa kategorii',
                        initialName: category.name,
                        submitLabel: 'Zapisz',
                        onSubmit: (name) => change.mutateAsync({ type: 'renameCategory', categoryId: category.id, name }),
                      })
                    }
                    onDeleteCategory={(category) =>
                      setConfirmation({
                        title: `Usunąć kategorię „${category.name}”?`,
                        description: `Z formularza zniknie lista ${category.products.length} produktów tej kategorii. Produkty zostają w kartotece.`,
                        confirmLabel: 'Usuń kategorię',
                        onConfirm: () =>
                          change.mutate({ type: 'deleteCategory', categoryId: category.id }, { onError: onChangeError }),
                      })
                    }
                    onAddProduct={(category, productId) =>
                      change.mutate({ type: 'addProduct', categoryId: category.id, productId }, { onError: onChangeError })
                    }
                    onPasteProducts={(category, initialText) =>
                      setPasteDialog({
                        categoryName: category.name,
                        initialText,
                        onSubmit: (rows) => paste.mutateAsync({ categoryId: category.id, rows }),
                      })
                    }
                    onRemoveProduct={(category, itemId) =>
                      change.mutate({ type: 'removeProduct', categoryId: category.id, itemId }, { onError: onChangeError })
                    }
                    onSetUnit={(category, itemId, orderUnit) =>
                      change.mutate(
                        { type: 'setOrderUnit', categoryId: category.id, itemId, orderUnit },
                        { onError: onChangeError },
                      )
                    }
                    onMove={(moveChange, categories) =>
                      form &&
                      move(
                        moveChange,
                        { sections: sections.map((other) => (other.id === section.id ? { ...section, categories } : other)) },
                        onChangeError,
                      )
                    }
                  />
                </Tabs.Panel>
              ))}
            </div>
            {activeSection && (
              <CategoryNav
                items={activeSection.categories.map((category) => ({
                  id: category.id,
                  name: category.name,
                  count: category.products.length,
                }))}
              />
            )}
          </div>
        </Tabs.Root>
      )}

      <NameDialog state={nameDialog} onClose={() => setNameDialog(null)} />
      <PasteProductsDialog state={pasteDialog} onClose={() => setPasteDialog(null)} />

      <AlertDialog open={confirmation !== null} onOpenChange={(open) => !open && setConfirmation(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{confirmation?.title}</AlertDialogTitle>
            <AlertDialogDescription>{confirmation?.description}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Anuluj</AlertDialogCancel>
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                confirmation?.onConfirm()
                setConfirmation(null)
              }}
            >
              {confirmation?.confirmLabel}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}

type SectionPanelProps = {
  storeId: string
  section: OrderSection
  editing: boolean
  busy: boolean
  onRename: () => void
  onDelete: () => void
  onAddCategory: () => void
  onRenameCategory: (category: OrderCategory) => void
  onDeleteCategory: (category: OrderCategory) => void
  onAddProduct: (category: OrderCategory, productId: string) => void
  onPasteProducts: (category: OrderCategory, initialText: string) => void
  onRemoveProduct: (category: OrderCategory, itemId: string) => void
  onSetUnit: (category: OrderCategory, itemId: string, orderUnit: OrderUnit) => void
  /** Przeciągnięcie: zmiana do zapisania i nowy układ kategorii działu, pokazany od razu. */
  onMove: (change: MoveChange, categories: OrderCategory[]) => void
}

/** Produkty kategorii w trakcie przeciągania: id kategorii -> pozycje w bieżącej kolejności. */
type Columns = Record<string, OrderProduct[]>

type DragData = { type: 'category' } | { type: 'product' } | { type: 'drop' }

/** Pusta kategoria też przyjmuje produkt: cała jej karta jest celem o tym id. */
const DROP_PREFIX = 'drop:'

const columnsOf = (section: OrderSection): Columns =>
  Object.fromEntries(section.categories.map((category) => [category.id, category.products]))

/** Kategoria, w której jest produkt (albo której kartę wskazuje cel upuszczenia). */
function containerOf(id: UniqueIdentifier, columns: Columns) {
  const key = String(id)
  if (key.startsWith(DROP_PREFIX)) return key.slice(DROP_PREFIX.length)
  return Object.keys(columns).find((categoryId) => columns[categoryId].some((product) => product.id === key))
}

const dragType = (data: { current?: unknown }) => (data.current as DragData | undefined)?.type

/**
 * Kategorię porównujemy tylko z kategoriami. Produkt trafia na wiersz pod wskaźnikiem, potem na kartę kategorii
 * pod wskaźnikiem (np. pustej), a z klawiatury (bez wskaźnika) na najbliższy wiersz.
 */
const collisionDetection: CollisionDetection = (args) => {
  if (dragType(args.active.data) === 'category') {
    return closestCenter({
      ...args,
      droppableContainers: args.droppableContainers.filter((container) => dragType(container.data) === 'category'),
    })
  }
  const targets = args.droppableContainers.filter((container) => dragType(container.data) !== 'category')
  const hits = pointerWithin({ ...args, droppableContainers: targets })
  const rows = hits.filter((hit) => !String(hit.id).startsWith(DROP_PREFIX))
  if (rows.length > 0) return rows
  if (hits.length > 0) return hits
  return closestCenter({ ...args, droppableContainers: targets })
}

const screenReaderInstructions = {
  draggable:
    'Żeby przenieść, naciśnij spację albo Enter. Strzałkami zmieniasz miejsce, spacją albo Enterem upuszczasz, Escape anuluje.',
}

function SectionPanel({
  storeId,
  section,
  editing,
  busy,
  onRename,
  onDelete,
  onAddCategory,
  onRenameCategory,
  onDeleteCategory,
  onAddProduct,
  onPasteProducts,
  onRemoveProduct,
  onSetUnit,
  onMove,
}: SectionPanelProps) {
  const [active, setActive] = useState<{ type: 'category' | 'product'; id: string } | null>(null)
  const [columns, setColumns] = useState<Columns | null>(null)
  // Przeciągana kategoria: gdzie była na ekranie przed zwinięciem kategorii (patrz niżej).
  const anchorTop = useRef<number | null>(null)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const shown = columns ?? columnsOf(section)
  const draggingCategory = active?.type === 'category'
  const products = section.categories.flatMap((category) => category.products)
  const nameOf = (id: UniqueIdentifier) => {
    const key = String(id).replace(DROP_PREFIX, '')
    const category = section.categories.find((other) => other.id === key)
    if (category) return `kategorię ${category.name}`
    const product = products.find((other) => other.id === key)
    return product ? (product.name ?? product.ean) : ''
  }
  const announcements: Announcements = {
    onDragStart: ({ active }) => `Podniesiono ${nameOf(active.id)}.`,
    onDragOver: ({ active, over }) => (over ? `${nameOf(active.id)} nad ${nameOf(over.id)}.` : undefined),
    onDragEnd: ({ active }) => `Upuszczono ${nameOf(active.id)}.`,
    onDragCancel: ({ active }) => `Anulowano przenoszenie ${nameOf(active.id)}.`,
  }

  // Przy przeciąganiu kategorii zwijamy wszystkie do samych nagłówków (długie listy produktów nie przeszkadzają),
  // a stronę przewijamy tak, żeby przeciągana została pod wskaźnikiem.
  useLayoutEffect(() => {
    if (!draggingCategory || !active || anchorTop.current === null) return
    const node = document.getElementById(categoryAnchorId(active.id))
    if (node) window.scrollBy(0, node.getBoundingClientRect().top - anchorTop.current)
    anchorTop.current = null
  }, [draggingCategory, active])

  const reset = () => {
    setActive(null)
    setColumns(null)
  }

  const onDragStart = ({ active }: DragStartEvent) => {
    const type = dragType(active.data) === 'category' ? 'category' : 'product'
    if (type === 'category') {
      anchorTop.current = document.getElementById(categoryAnchorId(String(active.id)))?.getBoundingClientRect().top ?? null
    } else {
      setColumns(columnsOf(section))
    }
    setActive({ type, id: String(active.id) })
  }

  // Produkt nad inną kategorią przechodzi do niej od razu, żeby było widać, gdzie wyląduje.
  const onDragOver = ({ active, over }: DragOverEvent) => {
    if (!over || dragType(active.data) !== 'product') return
    setColumns((current) => {
      if (!current) return current
      const from = containerOf(active.id, current)
      const to = containerOf(over.id, current)
      if (!from || !to || from === to) return current
      const moving = current[from].find((product) => product.id === active.id)
      // Kategoria może mieć produkt tylko raz.
      if (!moving || current[to].some((product) => product.productId === moving.productId)) return current
      const target = current[to]
      const overIndex = target.findIndex((product) => product.id === over.id)
      const translated = active.rect.current.translated
      const below = overIndex >= 0 && translated !== null && translated.top > over.rect.top + over.rect.height / 2
      const index = overIndex < 0 ? target.length : overIndex + (below ? 1 : 0)
      return {
        ...current,
        [from]: current[from].filter((product) => product.id !== active.id),
        [to]: [...target.slice(0, index), moving, ...target.slice(index)],
      }
    })
  }

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    const itemId = String(active.id)
    if (dragType(active.data) === 'category') {
      reset()
      if (!over || over.id === active.id) return
      const from = section.categories.findIndex((category) => category.id === itemId)
      const to = section.categories.findIndex((category) => category.id === over.id)
      if (from < 0 || to < 0) return
      onMove({ type: 'moveCategory', categoryId: itemId, index: to }, arrayMove(section.categories, from, to))
      return
    }

    const current = columns
    reset()
    if (!current) return
    const container = containerOf(itemId, current)
    if (!container) return
    let list = current[container]
    if (over && over.id !== active.id && containerOf(over.id, current) === container && !String(over.id).startsWith(DROP_PREFIX)) {
      list = arrayMove(
        list,
        list.findIndex((product) => product.id === itemId),
        list.findIndex((product) => product.id === over.id),
      )
    }
    const index = list.findIndex((product) => product.id === itemId)
    const origin = section.categories.find((category) => category.products.some((product) => product.id === itemId))
    if (!origin) return
    if (origin.id === container && origin.products.findIndex((product) => product.id === itemId) === index) return
    const arranged = { ...current, [container]: list }
    onMove(
      { type: 'moveProduct', categoryId: origin.id, itemId, targetCategoryId: container, index },
      section.categories.map((category) => ({ ...category, products: arranged[category.id] ?? category.products })),
    )
  }

  const activeCategory = draggingCategory ? section.categories.find((category) => category.id === active.id) : undefined
  const activeProduct = active?.type === 'product' ? products.find((product) => product.id === active.id) : undefined

  return (
    <div className="grid grid-cols-1 gap-5">
      {editing && (
        <div className="flex flex-wrap items-center gap-2">
          <Button size="lg" onClick={onAddCategory}>
            <PlusIcon />
            Kategoria
          </Button>
          <div className="ml-auto flex gap-1">
            <Button variant="ghost" size="sm" onClick={onRename}>
              <PencilIcon />
              Nazwa działu
            </Button>
            <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" onClick={onDelete}>
              <Trash2Icon />
              Usuń dział
            </Button>
          </div>
        </div>
      )}

      {section.categories.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Dział {section.name} nie ma jeszcze kategorii</p>
          <p className="text-sm text-muted-foreground">
            {editing ? 'Dodaj kategorię, np. Wędliny, i przypnij do niej produkty.' : 'Formularz tego działu jest w przygotowaniu.'}
          </p>
        </Card>
      ) : (
        <DndContext
          sensors={sensors}
          collisionDetection={collisionDetection}
          // Kategorie zwijają się po starcie przeciągania, więc cele trzeba mierzyć na bieżąco.
          measuring={{ droppable: { strategy: MeasuringStrategy.Always } }}
          accessibility={{ announcements, screenReaderInstructions }}
          onDragStart={onDragStart}
          onDragOver={onDragOver}
          onDragEnd={onDragEnd}
          onDragCancel={reset}
        >
          <SortableContext items={section.categories.map((category) => category.id)} strategy={verticalListSortingStrategy}>
            {section.categories.map((category) => (
              <CategoryCard
                key={category.id}
                storeId={storeId}
                category={category}
                products={shown[category.id] ?? category.products}
                editing={editing}
                collapsed={draggingCategory}
                busy={busy}
                onRename={() => onRenameCategory(category)}
                onDelete={() => onDeleteCategory(category)}
                onAddProduct={(productId) => onAddProduct(category, productId)}
                onPasteProducts={(initialText) => onPasteProducts(category, initialText)}
                onRemoveProduct={(itemId) => onRemoveProduct(category, itemId)}
                onSetUnit={(itemId, orderUnit) => onSetUnit(category, itemId, orderUnit)}
              />
            ))}
          </SortableContext>
          <DragOverlay>
            {activeCategory && (
              <div className="flex cursor-grabbing items-center gap-2 rounded-xl border bg-card px-3 py-2 shadow-lg">
                <GripVerticalIcon aria-hidden className="size-4 text-muted-foreground" />
                <span className="text-base font-semibold">{activeCategory.name}</span>
                <span className="text-sm text-muted-foreground tabular-nums">{activeCategory.products.length}</span>
              </div>
            )}
            {activeProduct && (
              <div className="flex cursor-grabbing items-center gap-2 rounded-lg border bg-card px-3 py-2.5 shadow-lg">
                <GripVerticalIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                <div className="min-w-0">
                  <div className="truncate text-sm font-medium">{activeProduct.name ?? 'Bez nazwy'}</div>
                  <div className="truncate text-xs text-muted-foreground tabular-nums">
                    {activeProduct.brand && `${activeProduct.brand} · `}EAN {activeProduct.ean}
                  </div>
                </div>
              </div>
            )}
          </DragOverlay>
        </DndContext>
      )}
    </div>
  )
}

/** Uchwyt do przeciągania; sam wiersz albo nagłówek nie reaguje, żeby nie przeszkadzać w klikaniu i zaznaczaniu. */
function DragHandle({ label, ...props }: { label: string } & React.ComponentProps<'button'>) {
  return (
    <button
      type="button"
      aria-label={label}
      title="Przeciągnij, żeby zmienić miejsce"
      className="inline-flex size-8 shrink-0 cursor-grab touch-none items-center justify-center rounded-md text-muted-foreground transition-colors outline-none hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 active:cursor-grabbing"
      {...props}
    >
      <GripVerticalIcon className="size-4" />
    </button>
  )
}

function CategoryCard({
  storeId,
  category,
  products,
  editing,
  collapsed,
  busy,
  onRename,
  onDelete,
  onAddProduct,
  onPasteProducts,
  onRemoveProduct,
  onSetUnit,
}: {
  storeId: string
  category: OrderCategory
  /** Produkty w bieżącej kolejności (w trakcie przeciągania różna od zapisanej). */
  products: OrderProduct[]
  editing: boolean
  /** Przeciągana jest kategoria: pokazujemy same nagłówki. */
  collapsed: boolean
  busy: boolean
  onRename: () => void
  onDelete: () => void
  onAddProduct: (productId: string) => void
  onPasteProducts: (initialText: string) => void
  onRemoveProduct: (itemId: string) => void
  onSetUnit: (itemId: string, orderUnit: OrderUnit) => void
}) {
  const addedProductIds = new Set(products.map((product) => product.productId))
  const [justAddedId, setJustAddedId] = useState<string | null>(null)
  const addBarRef = useRef<HTMLDivElement>(null)
  const justAddedVisible = justAddedId !== null && addedProductIds.has(justAddedId)
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({
    id: category.id,
    data: { type: 'category' } satisfies DragData,
    disabled: !editing,
  })
  const { setNodeRef: setDropRef, isOver } = useDroppable({
    id: DROP_PREFIX + category.id,
    data: { type: 'drop' } satisfies DragData,
    disabled: !editing,
  })

  // Nowy produkt ląduje na końcu tabeli, tuż nad wyszukiwarką: przewijamy tak, żeby było widać oba.
  useEffect(() => {
    if (!justAddedVisible) return
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    addBarRef.current?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'nearest' })
  }, [justAddedVisible, justAddedId])

  return (
    <section
      ref={setNodeRef}
      id={categoryAnchorId(category.id)}
      aria-label={category.name}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cn('scroll-mt-20', isDragging && 'opacity-40')}
    >
      <div className={cn('flex items-center gap-2', !collapsed && 'mb-2', collapsed && 'rounded-xl border bg-card px-2 py-1.5')}>
        {editing && (
          <DragHandle
            ref={setActivatorNodeRef}
            label={`Przeciągnij kategorię ${category.name}`}
            className="-ml-1"
            {...attributes}
            {...listeners}
          />
        )}
        <h2 className="text-base font-semibold">{category.name}</h2>
        <span className="text-sm text-muted-foreground tabular-nums">{products.length}</span>
        {editing && !collapsed && (
          <div className="ml-auto flex gap-1">
            <Button variant="ghost" size="icon-sm" aria-label={`Zmień nazwę kategorii ${category.name}`} title="Zmień nazwę" onClick={onRename}>
              <PencilIcon />
            </Button>
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`Usuń kategorię ${category.name}`}
              title="Usuń kategorię"
              className="text-destructive hover:text-destructive"
              onClick={onDelete}
            >
              <Trash2Icon />
            </Button>
          </div>
        )}
      </div>

      {!collapsed && (
        <Card
          ref={setDropRef}
          className={cn('gap-0 py-0 transition-shadow', isOver && 'ring-2 ring-ring/40')}
        >
          {products.length > 0 && (
            <Table className="min-w-[36rem] table-fixed">
              {/* Stały układ kolumn: każda kategoria to osobna tabela, a kolumny mają się pokrywać między nimi. */}
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  {editing && (
                    <TableHead className="w-10 pl-2">
                      <span className="sr-only">Przeciągnij</span>
                    </TableHead>
                  )}
                  <TableHead className={editing ? 'pl-1' : 'pl-4'}>Produkt</TableHead>
                  <TableHead className="w-36">EAN</TableHead>
                  <TableHead className="w-32">Zamawiane w</TableHead>
                  <TableHead className={cn('pr-4', editing ? 'w-14' : 'w-0 p-0')}>
                    <span className="sr-only">Akcje</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                <SortableContext items={products.map((product) => product.id)} strategy={verticalListSortingStrategy}>
                  {products.map((product) => (
                    <ProductRow
                      key={product.id}
                      product={product}
                      editing={editing}
                      highlighted={product.productId === justAddedId}
                      busy={busy}
                      onRemove={() => onRemoveProduct(product.id)}
                      onSetUnit={(orderUnit) => onSetUnit(product.id, orderUnit)}
                    />
                  ))}
                </SortableContext>
              </TableBody>
            </Table>
          )}
          {products.length === 0 && !editing && (
            <p className="px-4 py-6 text-center text-sm text-muted-foreground">Brak produktów w tej kategorii.</p>
          )}
          {editing && (
            <div ref={addBarRef} className={cn('flex gap-2 p-3', products.length > 0 && 'border-t')}>
              {/* Pole nie blokuje się na czas zapisu (zmiany idą po kolei), więc fokus zostaje do wpisania kolejnego. */}
              <div className="min-w-0 flex-1">
                <ProductSearchField
                  source={{ storeId }}
                  addedProductIds={addedProductIds}
                  onAdd={(product) => {
                    setJustAddedId(product.id)
                    onAddProduct(product.id)
                  }}
                  onPasteList={onPasteProducts}
                />
              </div>
              <Button
                variant="outline"
                className="h-10 shrink-0 rounded-xl"
                aria-label="Wklej listę EAN-ów z Excela"
                title="Wklej listę EAN-ów z Excela"
                onClick={() => onPasteProducts('')}
              >
                <ClipboardPasteIcon />
                <span className="max-sm:hidden">Wklej z Excela</span>
              </Button>
            </div>
          )}
        </Card>
      )}
    </section>
  )
}

function ProductRow({
  product,
  editing,
  highlighted,
  busy,
  onRemove,
  onSetUnit,
}: {
  product: OrderProduct
  editing: boolean
  highlighted: boolean
  busy: boolean
  onRemove: () => void
  onSetUnit: (orderUnit: OrderUnit) => void
}) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({
    id: product.id,
    data: { type: 'product' } satisfies DragData,
    disabled: !editing,
  })
  const label = product.name ?? product.ean
  return (
    <TableRow
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className={cn('tabular-nums', highlighted && 'bg-muted/60', isDragging && 'relative z-10 bg-muted opacity-40')}
    >
      {editing && (
        <TableCell className="py-2.5 pl-2">
          <DragHandle
            ref={setActivatorNodeRef}
            label={`Przeciągnij ${label}`}
            {...attributes}
            {...listeners}
          />
        </TableCell>
      )}
      <TableCell className={cn('py-2.5 whitespace-normal', editing ? 'pl-1' : 'pl-4')}>
        <div className="font-medium">{product.name ?? 'Bez nazwy'}</div>
        <div className="text-xs text-muted-foreground">
          {product.brand && `${product.brand} · `}ITM8 {product.itm8}
          {product.active === false && ' · nieaktywny w systemie sklepu'}
        </div>
      </TableCell>
      <TableCell className="text-muted-foreground">{product.ean}</TableCell>
      <TableCell>
        {editing ? (
          <UnitToggle value={product.orderUnit} label={label} onChange={onSetUnit} />
        ) : (
          <span className="text-muted-foreground">{UNIT_LABELS[product.orderUnit]}</span>
        )}
      </TableCell>
      <TableCell className={cn('pr-4 text-right', !editing && 'w-0 p-0')}>
        {editing && (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Usuń ${label} z kategorii`}
            title="Usuń z kategorii"
            disabled={busy}
            onClick={onRemove}
          >
            <XIcon />
          </Button>
        )}
      </TableCell>
    </TableRow>
  )
}
