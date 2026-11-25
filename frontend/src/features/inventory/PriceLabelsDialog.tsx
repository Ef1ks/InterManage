import { FileOutputIcon, TagsIcon, Trash2Icon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { SavedFolder } from '@/components/SavedFolder'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { useCan } from '@/features/auth/api'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import { priceLabelsFilePath, usePriceMarkdownChange, usePriceMarkdowns } from '@/features/inventory/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ApiError, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import type { PriceLabelKind, PriceMarkdown } from '@/lib/types'
import { cn } from '@/lib/utils'

const KINDS: PriceLabelKind[] = ['PRICE_CHANGE', 'MARKDOWN']
const KIND_LABELS: Record<PriceLabelKind, string> = { PRICE_CHANGE: 'zmiany cen', MARKDOWN: 'przeceny' }
/** Format cenówki w nazwie pliku, gdy nie ma własnej nazwy (jak `PriceLabelKind` w backendzie). */
const DEFAULT_NAMES: Record<PriceLabelKind, string> = { PRICE_CHANGE: 'BIALFOR67', MARKDOWN: 'PROPROFOR67' }
const MAX_COUNT = 9999
const GROUP_CHOICES = [1, 2, 3, 4, 5, 6]
const NAMES_KEY = 'intermanage.priceLabelNames'

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/** Program sklepu dzieli nazwę pliku po `_`, więc w nazwie zostają same litery i cyfry. */
const namePart = (text: string) => text.replace(/[^A-Za-z0-9]/g, '')

function readNames(): Partial<Record<PriceLabelKind, string>> {
  try {
    const saved = JSON.parse(localStorage.getItem(NAMES_KEY) ?? '{}') as Record<string, unknown>
    return Object.fromEntries(
      KINDS.filter((kind) => typeof saved[kind] === 'string').map((kind) => [kind, saved[kind] as string]),
    )
  } catch {
    return {}
  }
}

function writeNames(names: Partial<Record<PriceLabelKind, string>>) {
  try {
    localStorage.setItem(NAMES_KEY, JSON.stringify(names))
  } catch {
    // Bez localStorage nazwa obowiązuje do zamknięcia karty.
  }
}

const parseCount = (text: string) => {
  const value = Number(text)
  return text.trim() !== '' && Number.isInteger(value) && value >= 1 && value <= MAX_COUNT ? value : null
}

/** Grupy po kolei blokami: przy 3 grupach pierwsza trzecia listy to grupa 1 itd. */
function splitIntoGroups(items: PriceMarkdown[], groups: number): Record<string, number> {
  const size = Math.ceil(items.length / groups) || 1
  return Object.fromEntries(items.map((item, index) => [item.id, Math.min(groups, Math.floor(index / size) + 1)]))
}

const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/** Przycisk w nagłówku Magazynu i Ustaw promocje z liczbą przecen bez cenówki. */
export function PriceLabelsButton({ storeId }: { storeId: string }) {
  const markdowns = usePriceMarkdowns(storeId)
  const [open, setOpen] = useState(false)
  const waiting = (markdowns.data ?? []).filter((item) => item.labelsGeneratedAt === null).length
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <TagsIcon />
        Cenówki
        {waiting > 0 && (
          <span className="rounded-full bg-foreground px-1.5 text-xs leading-5 font-semibold text-background tabular-nums">
            {waiting}
          </span>
        )}
      </Button>
      <PriceLabelsDialog storeId={storeId} open={open} onOpenChange={setOpen} />
    </>
  )
}

/**
 * Przeceny z kart artykułów czekające na cenówkę. Plik ETIQUETTE idzie do folderu plików PROTO i dopiero po zapisie
 * artykuły przechodzą do „W pliku”; stamtąd można utworzyć plik ponownie, a usunięcie ma „Przywróć”. Przy każdym
 * artykule liczba cenówek, a podział na grupy daje osobny plik na każdą grupę.
 */
function PriceLabelsDialog({
  storeId,
  open,
  onOpenChange,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const markdowns = usePriceMarkdowns(storeId)
  const change = usePriceMarkdownChange(storeId)
  const folder = useProtoFolder(storeId)
  // Ustawianie promocji (DATA_ENTRY:VIEW) dopisuje swoje artykuły i drukuje ich cenówki.
  const canEditInventory = useCan('INVENTORY', 'EDIT')
  const canSetPromotions = useCan('DATA_ENTRY')
  const canEdit = canEditInventory || canSetPromotions
  const [saving, setSaving] = useState(false)
  // Zaznaczone jest wszystko, więc pamiętamy tylko odznaczone.
  const [unchecked, setUnchecked] = useState<ReadonlySet<string>>(new Set())
  // Liczba cenówek wpisu (bez wpisu 1); tekst, żeby pole dało się wyczyścić w trakcie pisania.
  const [counts, setCounts] = useState<Record<string, string>>({})
  const [bulkCount, setBulkCount] = useState('')
  // Podział na grupy: każda grupa to osobny plik cenówek.
  const [groupCount, setGroupCount] = useState(1)
  const [groups, setGroups] = useState<Record<string, number>>({})
  const [names, setNames] = useState(readNames)

  const items = markdowns.data ?? []
  const pending = items.filter((item) => item.labelsGeneratedAt === null)
  const generated = items.filter((item) => item.labelsGeneratedAt !== null)
  const pickedPending = pending.filter((item) => !unchecked.has(item.id))
  const pickedGenerated = generated.filter((item) => !unchecked.has(item.id))
  const picked = [...pickedPending, ...pickedGenerated]

  const countOf = (item: PriceMarkdown) => parseCount(counts[item.id] ?? '1')
  const groupOf = (item: PriceMarkdown) => (groupCount > 1 ? Math.min(groups[item.id] ?? 1, groupCount) : 1)
  const fileName = (kind: PriceLabelKind) => namePart(names[kind] ?? '') || DEFAULT_NAMES[kind]
  const labelsOf = (chosen: PriceMarkdown[]) => chosen.reduce((sum, item) => sum + (countOf(item) ?? 0), 0)

  const changeName = (kind: PriceLabelKind, text: string) => {
    const next = { ...names, [kind]: namePart(text).toUpperCase().slice(0, 40) }
    setNames(next)
    writeNames(next)
  }

  const setAllCounts = () => {
    const value = parseCount(bulkCount)
    if (value === null || picked.length === 0) return
    const previous = counts
    setCounts((current) => ({ ...current, ...Object.fromEntries(picked.map((item) => [item.id, String(value)])) }))
    toast(`Ustawiono po ${value} ${value === 1 ? 'cenówce' : 'cenówki'}: ${picked.length} art.`, {
      action: { label: 'Przywróć', onClick: () => setCounts(previous) },
    })
  }

  const chooseGroups = (count: number) => {
    setGroupCount(count)
    setGroups({ ...splitIntoGroups(generated, count), ...splitIntoGroups(pending, count) })
  }

  const toggle = (id: string) =>
    setUnchecked((current) => {
      const next = new Set(current)
      if (!next.delete(id)) next.add(id)
      return next
    })

  // Przecena i zmiana ceny mają różne cenówki, więc każdy rodzaj idzie do osobnego pliku, a każda grupa też.
  const saveFile = async (chosen: PriceMarkdown[]) => {
    if (chosen.some((item) => countOf(item) === null)) {
      toast.error(`Liczba cenówek musi być od 1 do ${MAX_COUNT}`)
      return
    }
    setSaving(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu.
      const folderReady = await ensureFolderAccess(folder.key)
      const saved: string[] = []
      let lastName: string | null = null
      for (const kind of KINDS) {
        for (let group = 1; group <= groupCount; group++) {
          const ofFile = chosen.filter((item) => item.kind === kind && groupOf(item) === group)
          if (ofFile.length === 0) continue
          const name = fileName(kind)
          // Nazwa pliku ma czas co do sekundy: kolejna grupa z tą samą nazwą czeka, żeby nie nadpisać poprzedniej.
          if (lastName === name) await pause(1100)
          lastName = name
          const lines = ofFile.map((item) => ({ id: item.id, count: countOf(item) ?? 1 }))
          const { fileName: file, blob } = await fetchPostedFile(
            priceLabelsFilePath(storeId),
            { items: lines, name: name === DEFAULT_NAMES[kind] ? null : name },
            'ETIQUETTE.traite0',
          )
          if (folderReady) await saveToFolder(folder.key, file, blob)
          else saveBlob(blob, file)
          await change.mutateAsync({ action: 'labels-generated', ids: ofFile.map((item) => item.id) })
          saved.push(
            `${KIND_LABELS[kind]}${groupCount > 1 ? `, grupa ${group}` : ''}: ${
              new Set(ofFile.map((item) => item.ean)).size
            } art., ${labelsOf(ofFile)} cenówek`,
          )
        }
      }
      toast.success(saved.length > 1 ? `Utworzono pliki cenówek: ${saved.length}` : 'Utworzono cenówki', {
        description: `${saved.join(' · ')}${
          folderReady ? '' : ' · pobrane: folder protokołów nie jest wybrany w tej przeglądarce'
        }`,
        duration: 10_000,
      })
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się utworzyć cenówek'))
    } finally {
      setSaving(false)
    }
  }

  const remove = (chosen: PriceMarkdown[]) => {
    const ids = chosen.map((item) => item.id)
    if (ids.length === 0) return
    change.mutate(
      { action: 'delete', ids },
      {
        onSuccess: () =>
          toast(
            ids.length === 1
              ? `Usunięto z listy: ${chosen[0].name ?? chosen[0].ean}`
              : `Usunięto z listy: ${ids.length}`,
            {
              action: {
                label: 'Przywróć',
                onClick: () =>
                  change.mutate(
                    { action: 'restore', ids },
                    { onError: () => toast.error('Nie udało się przywrócić cenówek') },
                  ),
              },
            },
          ),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć cenówek')),
      },
    )
  }

  const rowGrid = cn(
    'grid items-center gap-3',
    groupCount > 1
      ? 'grid-cols-[auto_minmax(0,1fr)_auto_auto_auto_auto] sm:grid-cols-[auto_minmax(0,1fr)_auto_auto_auto_auto_auto]'
      : 'grid-cols-[auto_minmax(0,1fr)_auto_auto_auto] sm:grid-cols-[auto_minmax(0,1fr)_auto_auto_auto_auto]',
  )

  const row = (item: PriceMarkdown) => (
    <li key={item.id} className="border-b last:border-b-0">
      <label
        className={cn(
          rowGrid,
          'px-3 py-1.5 text-sm',
          canEdit && 'cursor-pointer hover:bg-accent has-checked:bg-blue-100 dark:has-checked:bg-blue-950/60',
        )}
      >
        <input
          type="checkbox"
          checked={!unchecked.has(item.id)}
          disabled={!canEdit}
          onChange={() => toggle(item.id)}
          className="size-4 accent-blue-600"
        />
        <span className="min-w-0">
          <span className="flex items-center gap-1">
            <span className="min-w-0 truncate font-medium" title={item.name ?? undefined}>
              {item.name ?? `ITM8 ${item.itm8}`}
            </span>
            <ArticleStatsButton article={item} />
          </span>
          <span className="block text-xs text-muted-foreground tabular-nums">{item.ean}</span>
        </span>
        <span className="text-xs text-muted-foreground tabular-nums max-sm:hidden">
          {item.kind === 'PRICE_CHANGE' ? (
            <span className="rounded bg-sky-200 px-1.5 py-0.5 font-medium text-sky-950 dark:bg-sky-900 dark:text-sky-50">
              zmiana ceny {formatDocumentDate(item.validFrom)}
            </span>
          ) : (
            `${formatDocumentDate(item.validFrom)} - ${item.validTo ? formatDocumentDate(item.validTo) : ''}`
          )}
        </span>
        <span className="text-right whitespace-nowrap tabular-nums">
          {/* Zmiana ceny to po prostu nowa cena; przekreślona stara tylko przy przecenie. */}
          {item.kind === 'MARKDOWN' && item.regularPrice != null && (
            <span className="mr-2 text-xs text-muted-foreground line-through">{formatMoney(item.regularPrice)}</span>
          )}
          <span className="font-medium">{formatMoney(item.newPrice)} zł</span>
        </span>
        <Input
          type="number"
          inputMode="numeric"
          min={1}
          max={MAX_COUNT}
          aria-label={`Liczba cenówek: ${item.name ?? item.ean}`}
          title="Liczba cenówek"
          value={counts[item.id] ?? '1'}
          disabled={!canEdit}
          aria-invalid={countOf(item) === null}
          onChange={(event) => setCounts((current) => ({ ...current, [item.id]: event.target.value }))}
          onFocus={(event) => event.target.select()}
          className="h-7 w-16 px-2 text-right tabular-nums"
        />
        {groupCount > 1 && (
          <span className="flex gap-0.5" role="group" aria-label={`Grupa: ${item.name ?? item.ean}`}>
            {Array.from({ length: groupCount }, (_, index) => index + 1).map((group) => (
              <button
                key={group}
                type="button"
                disabled={!canEdit}
                aria-pressed={groupOf(item) === group}
                title={`Grupa ${group}`}
                onClick={(event) => {
                  event.preventDefault()
                  setGroups((current) => ({ ...current, [item.id]: group }))
                }}
                className={cn(
                  'size-6 rounded text-xs font-medium tabular-nums',
                  groupOf(item) === group
                    ? 'bg-foreground text-background'
                    : 'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
                )}
              >
                {group}
              </button>
            ))}
          </span>
        )}
        {canEdit ? (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Usuń z listy: ${item.name ?? item.ean}`}
            title="Usuń z listy"
            disabled={change.isPending}
            onClick={(event) => {
              event.preventDefault()
              remove([item])
            }}
          >
            <Trash2Icon />
          </Button>
        ) : (
          <span />
        )}
      </label>
    </li>
  )

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>Cenówki</DialogTitle>
          <DialogDescription>
            Czekają tu przeceny i zmiany cen z wprowadzania danych. Przy każdym artykule wpiszesz, ile cenówek
            wydrukować; przeceny, zmiany cen i każda grupa tworzą się w osobnym pliku.
          </DialogDescription>
        </DialogHeader>

        {canEdit && items.length > 0 && (
          <div className="flex flex-wrap items-end gap-x-6 gap-y-3 rounded-lg bg-muted/60 p-3">
            <form
              className="flex items-end gap-2"
              onSubmit={(event) => {
                event.preventDefault()
                setAllCounts()
              }}
            >
              <label className="flex flex-col gap-1 text-xs text-muted-foreground">
                Cenówek na każdy zaznaczony
                <Input
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={MAX_COUNT}
                  placeholder="np. 4"
                  value={bulkCount}
                  onChange={(event) => setBulkCount(event.target.value)}
                  className="h-8 w-20 text-right tabular-nums"
                />
              </label>
              <Button
                type="submit"
                variant="outline"
                size="sm"
                disabled={parseCount(bulkCount) === null || picked.length === 0}
              >
                Ustaw ({picked.length})
              </Button>
            </form>
            <div className="flex flex-col gap-1 text-xs text-muted-foreground">
              Podziel na pliki
              <div className="flex gap-1" role="group" aria-label="Liczba grup">
                {GROUP_CHOICES.map((count) => (
                  <Button
                    key={count}
                    size="sm"
                    variant={groupCount === count ? 'default' : 'outline'}
                    aria-pressed={groupCount === count}
                    title={count === 1 ? 'Jeden plik' : `${count} grupy po kolei, każda w osobnym pliku`}
                    onClick={() => chooseGroups(count)}
                    className="w-8 px-0 tabular-nums"
                  >
                    {count}
                  </Button>
                ))}
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              {KINDS.map((kind) => (
                <label key={kind} className="flex flex-col gap-1 text-xs text-muted-foreground">
                  Nazwa pliku: {KIND_LABELS[kind]}
                  <Input
                    value={names[kind] ?? ''}
                    placeholder={DEFAULT_NAMES[kind]}
                    maxLength={40}
                    title="Same litery i cyfry; puste = nazwa formatu cenówki"
                    onChange={(event) => changeName(kind, event.target.value)}
                    className="h-8 w-36 font-mono text-xs"
                  />
                </label>
              ))}
            </div>
          </div>
        )}

        <section aria-label="Do wydruku" className="flex flex-col gap-2">
          <div className="flex items-baseline justify-between gap-3">
            <h3 className="text-sm font-medium">Do wydruku ({pending.length})</h3>
            {pickedPending.length > 0 && (
              <span className="text-xs text-muted-foreground tabular-nums">
                zaznaczone cenówki: {labelsOf(pickedPending)}
              </span>
            )}
          </div>
          <div className="max-h-[32vh] overflow-y-auto rounded-lg border">
            <ul aria-label="Cenówki do wydruku">{pending.map(row)}</ul>
            {pending.length === 0 && (
              <p className="px-3 py-6 text-center text-sm text-muted-foreground">
                {markdowns.isLoading
                  ? 'Wczytywanie…'
                  : 'Nowe cenówki pojawią się po przecenie artykułu albo zmianie ceny we wprowadzaniu danych.'}
              </p>
            )}
          </div>
          {canEdit && (
            <div className="flex justify-end gap-2">
              <Button
                variant="outline"
                disabled={pickedPending.length === 0 || saving || change.isPending}
                onClick={() => remove(pickedPending)}
              >
                <Trash2Icon />
                Usuń zaznaczone ({pickedPending.length})
              </Button>
              <Button
                loading={saving}
                disabled={pickedPending.length === 0 || change.isPending}
                onClick={() => void saveFile(pickedPending)}
              >
                <FileOutputIcon />
                Utwórz cenówki ({pickedPending.length})
              </Button>
            </div>
          )}
        </section>

        {generated.length > 0 && (
          <section aria-label="W pliku" className="flex flex-col gap-2">
            <h3 className="text-sm font-medium">W pliku ({generated.length})</h3>
            <div className="max-h-[24vh] overflow-y-auto rounded-lg border">
              <ul aria-label="Cenówki w pliku">{generated.map(row)}</ul>
            </div>
          </section>
        )}

        {canEdit && <SavedFolder folder={folder} />}

        <DialogFooter className="sm:justify-between">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Zamknij
          </Button>
          {canEdit && (
            <div className="flex flex-wrap justify-end gap-2">
              {generated.length > 0 && (
                <Button
                  variant="outline"
                  disabled={pickedGenerated.length === 0 || saving || change.isPending}
                  onClick={() => void saveFile(pickedGenerated)}
                >
                  <FileOutputIcon />
                  Utwórz ponownie ({pickedGenerated.length})
                </Button>
              )}
              {generated.length > 0 && (
                <Button
                  variant="outline"
                  disabled={pickedGenerated.length === 0 || saving || change.isPending}
                  onClick={() => remove(pickedGenerated)}
                >
                  <Trash2Icon />
                  Usuń z pliku ({pickedGenerated.length})
                </Button>
              )}
            </div>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
