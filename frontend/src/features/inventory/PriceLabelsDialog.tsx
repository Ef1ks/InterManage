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
import { useCan } from '@/features/auth/api'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import { priceLabelsFilePath, usePriceMarkdownChange, usePriceMarkdowns } from '@/features/inventory/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { ApiError, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import type { PriceLabelKind, PriceMarkdown } from '@/lib/types'
import { cn } from '@/lib/utils'

const ROW_GRID =
  'grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-3 sm:grid-cols-[auto_minmax(0,1fr)_auto_auto]'

const KINDS: PriceLabelKind[] = ['PRICE_CHANGE', 'MARKDOWN']
const KIND_LABELS: Record<PriceLabelKind, string> = { PRICE_CHANGE: 'zmiany cen', MARKDOWN: 'przeceny' }

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

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
 * artykuły przechodzą do „W pliku”; stamtąd można utworzyć plik ponownie, a usunięcie ma „Przywróć”.
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

  const items = markdowns.data ?? []
  const pending = items.filter((item) => item.labelsGeneratedAt === null)
  const generated = items.filter((item) => item.labelsGeneratedAt !== null)
  const pickedPending = pending.filter((item) => !unchecked.has(item.id))
  const pickedGenerated = generated.filter((item) => !unchecked.has(item.id))
  const picked = [...pickedPending, ...pickedGenerated]

  const toggle = (id: string) =>
    setUnchecked((current) => {
      const next = new Set(current)
      if (!next.delete(id)) next.add(id)
      return next
    })

  // Przecena i zmiana ceny mają różne cenówki, więc każdy rodzaj idzie do osobnego pliku.
  const saveFile = async (chosen: PriceMarkdown[]) => {
    setSaving(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu.
      const folderReady = await ensureFolderAccess(folder.key)
      const saved: string[] = []
      for (const kind of KINDS) {
        const ofKind = chosen.filter((item) => item.kind === kind)
        if (ofKind.length === 0) continue
        const ids = ofKind.map((item) => item.id)
        const { fileName, blob } = await fetchPostedFile(priceLabelsFilePath(storeId), { ids }, 'ETIQUETTE.traite0')
        if (folderReady) await saveToFolder(folder.key, fileName, blob)
        else saveBlob(blob, fileName)
        await change.mutateAsync({ action: 'labels-generated', ids })
        saved.push(`${fileName} (${KIND_LABELS[kind]}: ${new Set(ofKind.map((item) => item.ean)).size})`)
      }
      toast.success(saved.length === 1 ? 'Zapisano plik cenówek' : `Zapisano pliki cenówek: ${saved.length}`, {
        description: `${saved.join(' · ')}${
          folderReady ? '' : ' · pobrane: folder protokołów nie jest wybrany w tej przeglądarce'
        }`,
        duration: 10_000,
      })
    } catch (error) {
      toast.error(errorMessage(error, 'Nie udało się zapisać pliku cenówek'))
    } finally {
      setSaving(false)
    }
  }

  const remove = () => {
    const ids = picked.map((item) => item.id)
    change.mutate(
      { action: 'delete', ids },
      {
        onSuccess: () =>
          toast(`Usunięto z listy: ${ids.length}`, {
            action: {
              label: 'Przywróć',
              onClick: () =>
                change.mutate(
                  { action: 'restore', ids },
                  { onError: () => toast.error('Nie udało się przywrócić cenówek') },
                ),
            },
          }),
        onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć cenówek')),
      },
    )
  }

  const row = (item: PriceMarkdown) => (
    <li key={item.id} className="border-b last:border-b-0">
      <label
        className={cn(
          ROW_GRID,
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
      </label>
    </li>
  )

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>Cenówki</DialogTitle>
          <DialogDescription>
            Czekają tu przeceny i zmiany cen z plików CTRLPRIX (wprowadzanie danych). Plik ETIQUETTE wczyta program
            sklepu, po jednej cenówce na artykuł; przeceny i zmiany cen idą do osobnych plików, bo mają inne cenówki.
          </DialogDescription>
        </DialogHeader>

        <section aria-label="Do wydruku" className="flex flex-col gap-2">
          <h3 className="text-sm font-medium">Do wydruku ({pending.length})</h3>
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
            <div className="flex justify-end">
              <Button
                loading={saving}
                disabled={pickedPending.length === 0 || change.isPending}
                onClick={() => void saveFile(pickedPending)}
              >
                <FileOutputIcon />
                Utwórz plik ETIQUETTE ({pickedPending.length})
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
              <Button variant="outline" disabled={picked.length === 0 || change.isPending} onClick={remove}>
                <Trash2Icon />
                Usuń z listy ({picked.length})
              </Button>
            </div>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
