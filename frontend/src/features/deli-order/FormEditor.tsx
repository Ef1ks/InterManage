import { useEffect, useRef, useState } from 'react'
import { Tabs } from '@base-ui/react/tabs'
import { CheckIcon, ClipboardPasteIcon, PencilIcon, PlusIcon, Trash2Icon, XIcon } from 'lucide-react'
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
import { useChangeOrderForm, useOrderForm, usePasteOrderProducts } from '@/features/deli-order/api'
import { CategoryNav, categoryAnchorId } from '@/features/deli-order/CategoryNav'
import { InvitationsPanel } from '@/features/deli-order/InvitationsPanel'
import { NameDialog, type NameDialogState } from '@/features/deli-order/NameDialog'
import { type PasteDialogState, PasteProductsDialog } from '@/features/deli-order/PasteProductsDialog'
import { ProductSearchField } from '@/features/deli-order/ProductSearchField'
import { UNIT_LABELS, UnitToggle } from '@/features/deli-order/QuantityInput'
import { storeLabel } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { OrderCategory, OrderSection, OrderUnit, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

type Confirmation = { title: string; description: string; confirmLabel: string; onConfirm: () => void }

/** Formularz własnego sklepu: podgląd, a dla uprawnienia Edycja układanie działów, kategorii, produktów i zaproszeń. */
export function FormEditor({ store }: { store: StoreSummary }) {
  const canEdit = useCan('DELI_ORDERS', 'EDIT')
  const { data: form, isPending, isError, refetch } = useOrderForm(store.id)
  const change = useChangeOrderForm(store.id)
  const paste = usePasteOrderProducts(store.id)
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
              sztukach</strong>.
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
              />
            </Tabs.Panel>
          ))}
        </Tabs.Root>
      )}
      {activeSection && (
        <CategoryNav
          items={activeSection.categories.map((category) => ({
            id: category.id,
            name: category.name,
            count: category.products.length,
          }))}
        />
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
}: SectionPanelProps) {
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
        section.categories.map((category) => (
          <CategoryCard
            key={category.id}
            storeId={storeId}
            category={category}
            editing={editing}
            busy={busy}
            onRename={() => onRenameCategory(category)}
            onDelete={() => onDeleteCategory(category)}
            onAddProduct={(productId) => onAddProduct(category, productId)}
            onPasteProducts={(initialText) => onPasteProducts(category, initialText)}
            onRemoveProduct={(itemId) => onRemoveProduct(category, itemId)}
            onSetUnit={(itemId, orderUnit) => onSetUnit(category, itemId, orderUnit)}
          />
        ))
      )}
    </div>
  )
}

function CategoryCard({
  storeId,
  category,
  editing,
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
  editing: boolean
  busy: boolean
  onRename: () => void
  onDelete: () => void
  onAddProduct: (productId: string) => void
  onPasteProducts: (initialText: string) => void
  onRemoveProduct: (itemId: string) => void
  onSetUnit: (itemId: string, orderUnit: OrderUnit) => void
}) {
  const addedProductIds = new Set(category.products.map((product) => product.productId))
  const [justAddedId, setJustAddedId] = useState<string | null>(null)
  const addBarRef = useRef<HTMLDivElement>(null)
  const justAddedVisible = justAddedId !== null && addedProductIds.has(justAddedId)

  // Nowy produkt ląduje na końcu tabeli, tuż nad wyszukiwarką: przewijamy tak, żeby było widać oba.
  useEffect(() => {
    if (!justAddedVisible) return
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    addBarRef.current?.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'nearest' })
  }, [justAddedVisible, justAddedId])

  return (
    <section id={categoryAnchorId(category.id)} aria-label={category.name} className="scroll-mt-20">
      <div className="mb-2 flex items-center gap-2">
        <h2 className="text-base font-semibold">{category.name}</h2>
        <span className="text-sm text-muted-foreground tabular-nums">{category.products.length}</span>
        {editing && (
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

      <Card className="gap-0 py-0">
        {category.products.length > 0 && (
          <Table className="min-w-[36rem] table-fixed">
            {/* Stały układ kolumn: każda kategoria to osobna tabela, a kolumny mają się pokrywać między nimi. */}
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="pl-4">Produkt</TableHead>
                <TableHead className="w-36">EAN</TableHead>
                <TableHead className="w-32">Zamawiane w</TableHead>
                <TableHead className={cn('pr-4', editing ? 'w-14' : 'w-0 p-0')}>
                  <span className="sr-only">Akcje</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {category.products.map((product) => (
                <TableRow
                  key={product.id}
                  className={cn('tabular-nums', product.productId === justAddedId && 'bg-muted/60')}
                >
                  <TableCell className="py-2.5 pl-4 whitespace-normal">
                    <div className="font-medium">{product.name ?? 'Bez nazwy'}</div>
                    <div className="text-xs text-muted-foreground">
                      {product.brand && `${product.brand} · `}ITM8 {product.itm8}
                      {product.active === false && ' · nieaktywny w systemie sklepu'}
                    </div>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{product.ean}</TableCell>
                  <TableCell>
                    {editing ? (
                      <UnitToggle
                        value={product.orderUnit}
                        label={product.name ?? product.ean}
                        onChange={(orderUnit) => onSetUnit(product.id, orderUnit)}
                      />
                    ) : (
                      <span className="text-muted-foreground">{UNIT_LABELS[product.orderUnit]}</span>
                    )}
                  </TableCell>
                  <TableCell className={cn('pr-4 text-right', !editing && 'w-0 p-0')}>
                    {editing && (
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={`Usuń ${product.name ?? product.ean} z kategorii`}
                        title="Usuń z kategorii"
                        disabled={busy}
                        onClick={() => onRemoveProduct(product.id)}
                      >
                        <XIcon />
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {category.products.length === 0 && !editing && (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground">Brak produktów w tej kategorii.</p>
        )}
        {editing && (
          <div ref={addBarRef} className={cn('flex gap-2 p-3', category.products.length > 0 && 'border-t')}>
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
    </section>
  )
}
