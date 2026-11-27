import { useState } from 'react'
import { TagsIcon } from 'lucide-react'
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
import { ApiError, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import { cn } from '@/lib/utils'

/** Artykuł do etykiet: EAN, nazwa i ilość z tabeli (może być pusta, ułamkowa albo ujemna). */
export type LabelLine = { ean: string; name: string; quantity: number | null }

type Props = {
  storeId: string
  lines: LabelLine[]
  /** Etykiety tylko dla zaznaczonych wierszy (inaczej dla wszystkich artykułów tabeli). */
  selection: boolean
  /** Artykuły pominięte, bo nie mają EAN-u. */
  skipped: number
  open: boolean
  onOpenChange: (open: boolean) => void
}

const MAX_LABELS = 9999
const GROUP_CHOICES = [1, 2, 3, 4, 5, 6]
const NAME_KEY = 'intermanage.dataEntryLabelName'
/** Backend przyjmuje w miejscu formatu najwyżej 30 znaków. */
const MAX_NAME = 30

/**
 * Liczba etykiet według ilości z tabeli: cała ilość bez znaku, a ilość ułamkowa (towar na wagę), pusta albo 0
 * daje jedną etykietę.
 */
function countFromQuantity(quantity: number | null) {
  const value = Math.abs(quantity ?? 0)
  if (value < 1 || !Number.isInteger(value)) return 1
  return Math.min(value, MAX_LABELS)
}

const parseCount = (text: string) => {
  const value = Number(text)
  return text.trim() !== '' && Number.isInteger(value) && value >= 1 && value <= MAX_LABELS ? value : null
}

/** Program sklepu dzieli nazwę pliku po `_`, więc w nazwie zostają same litery i cyfry. */
const namePart = (text: string) =>
  text
    .replace(/[^A-Za-z0-9]/g, '')
    .toUpperCase()
    .slice(0, MAX_NAME)

function readName() {
  try {
    return localStorage.getItem(NAME_KEY) ?? ''
  } catch {
    return ''
  }
}

function writeName(name: string) {
  try {
    localStorage.setItem(NAME_KEY, name)
  } catch {
    // Bez localStorage nazwa obowiązuje do zamknięcia karty.
  }
}

/** Grupy po kolei blokami: przy 3 grupach pierwsza trzecia listy to grupa 1 itd. */
const splitIntoGroups = (length: number, groups: number) => {
  const size = Math.ceil(length / groups) || 1
  return Array.from({ length }, (_, index) => Math.min(groups, Math.floor(index / size) + 1))
}

const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * Plik etykiet cenowych (ETIQUETTE) z artykułów tabeli, jak okno Cenówek: przy każdym artykule liczba etykiet
 * (na start 1, skróty „po 1” i „tyle, ile w ilości”), podział na kilka plików i własna nazwa w nazwie pliku. Plik
 * zapisuje się w folderze plików PROTO wybranym raz w przeglądarce, skąd program sklepu sam go wczytuje; bez folderu
 * się pobiera.
 */
export function LabelDialog(props: Props) {
  return (
    <Dialog open={props.open} onOpenChange={props.onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-3xl">
        {/* Treść montuje się przy otwarciu, więc liczby i grupy startują od nowa dla bieżącej tabeli. */}
        {props.open && <LabelDialogBody {...props} />}
      </DialogContent>
    </Dialog>
  )
}

function LabelDialogBody({ storeId, lines, selection, skipped, onOpenChange }: Props) {
  const folder = useProtoFolder(storeId)
  const [saving, setSaving] = useState(false)
  const [unchecked, setUnchecked] = useState<ReadonlySet<number>>(new Set())
  const [counts, setCounts] = useState<string[]>(() => lines.map(() => '1'))
  const [bulkCount, setBulkCount] = useState('')
  const [groupCount, setGroupCount] = useState(1)
  const [groups, setGroups] = useState<number[]>(() => lines.map(() => 1))
  const [name, setName] = useState(readName)

  const indexes = lines.map((_, index) => index)
  const picked = indexes.filter((index) => !unchecked.has(index))
  const countOf = (index: number) => parseCount(counts[index] ?? '1')
  const groupOf = (index: number) => (groupCount > 1 ? Math.min(groups[index] ?? 1, groupCount) : 1)
  const labelsOf = (chosen: number[]) => chosen.reduce((sum, index) => sum + (countOf(index) ?? 0), 0)
  const byQuantity = lines.reduce((sum, line) => sum + countFromQuantity(line.quantity), 0)
  const hasQuantities = lines.some((line) => countFromQuantity(line.quantity) > 1)

  const changeName = (text: string) => {
    const next = namePart(text)
    setName(next)
    writeName(next)
  }

  const setCountsOf = (chosen: number[], value: (index: number) => number, message: string) => {
    if (chosen.length === 0) return
    const previous = counts
    const changed = new Set(chosen)
    setCounts((current) => current.map((text, index) => (changed.has(index) ? String(value(index)) : text)))
    toast(message, { action: { label: 'Przywróć', onClick: () => setCounts(previous) } })
  }

  const setAllCounts = () => {
    const value = parseCount(bulkCount)
    if (value === null) return
    setCountsOf(
      picked,
      () => value,
      `Ustawiono po ${value} ${value === 1 ? 'etykiecie' : 'etykiety'}: ${picked.length} art.`,
    )
  }

  const chooseGroups = (count: number) => {
    setGroupCount(count)
    setGroups(splitIntoGroups(lines.length, count))
  }

  const toggle = (index: number) =>
    setUnchecked((current) => {
      const next = new Set(current)
      if (!next.delete(index)) next.add(index)
      return next
    })

  const generate = async () => {
    if (picked.some((index) => countOf(index) === null)) {
      toast.error(`Liczba etykiet musi być od 1 do ${MAX_LABELS}`)
      return
    }
    setSaving(true)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(folder.key)
      const path = `/stores/${storeId}/data-entry/export/labels`
      const saved: string[] = []
      for (let group = 1; group <= groupCount; group++) {
        const ofFile = picked.filter((index) => groupOf(index) === group)
        if (ofFile.length === 0) continue
        // Nazwa pliku ma czas co do sekundy: kolejna grupa czeka, żeby nie nadpisać poprzedniej.
        if (saved.length > 0) await pause(1100)
        const body = {
          lines: ofFile.map((index) => ({ ean: lines[index].ean, count: countOf(index) ?? 1 })),
          format: name || null,
        }
        const { fileName, blob } = await fetchPostedFile(path, body, 'ETIQUETTE.traite0')
        if (folderReady) await saveToFolder(folder.key, fileName, blob)
        else saveBlob(blob, fileName)
        saved.push(`${groupCount > 1 ? `grupa ${group}: ` : ''}${ofFile.length} art., ${labelsOf(ofFile)} etykiet`)
      }
      toast.success(saved.length > 1 ? `Utworzono pliki etykiet: ${saved.length}` : 'Utworzono etykiety', {
        description: `${saved.join(' · ')}${
          folderReady ? '' : ' · pobrane: folder protokołów nie jest wybrany w tej przeglądarce'
        }`,
        duration: 10_000,
      })
      onOpenChange(false)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się utworzyć etykiet')
    } finally {
      setSaving(false)
    }
  }

  const rowGrid = cn(
    'grid items-center gap-3',
    groupCount > 1 ? 'grid-cols-[auto_minmax(0,1fr)_auto_auto_auto]' : 'grid-cols-[auto_minmax(0,1fr)_auto_auto]',
  )
  const articles = `${lines.length} ${lines.length === 1 ? 'artykuł' : 'artykułów'}`

  return (
    <>
      <DialogHeader>
        <DialogTitle>Etykiety cenowe</DialogTitle>
        <DialogDescription>
          {selection ? `Zaznaczone: ${articles}.` : `Wszystkie artykuły tabeli: ${articles}.`}
          {skipped > 0 && ` Bez EAN-u: ${skipped}, nie trafią do pliku.`} Przy każdym artykule wpiszesz, ile etykiet
          wydrukować; każda grupa tworzy się w osobnym pliku.
        </DialogDescription>
      </DialogHeader>

      {lines.length > 0 && (
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3 rounded-lg bg-muted/60 p-3">
          <form
            className="flex items-end gap-2"
            onSubmit={(event) => {
              event.preventDefault()
              setAllCounts()
            }}
          >
            <label className="flex flex-col gap-1 text-xs text-muted-foreground">
              Etykiet na każdy zaznaczony
              <Input
                type="number"
                inputMode="numeric"
                min={1}
                max={MAX_LABELS}
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
          {hasQuantities && (
            <div className="flex flex-col gap-1 text-xs text-muted-foreground">
              Według tabeli
              <div className="flex gap-1">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={picked.length === 0}
                  onClick={() => setCountsOf(picked, () => 1, `Po 1 etykiecie: ${picked.length} art.`)}
                >
                  Po 1
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={picked.length === 0}
                  title="Cała ilość z tabeli bez znaku; towar na wagę (ilość z ułamkiem) dostaje 1 etykietę"
                  onClick={() =>
                    setCountsOf(
                      picked,
                      (index) => countFromQuantity(lines[index].quantity),
                      `Etykiety według ilości: ${picked.length} art.`,
                    )
                  }
                >
                  Tyle, ile w ilości ({byQuantity})
                </Button>
              </div>
            </div>
          )}
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
          <label className="flex flex-col gap-1 text-xs text-muted-foreground">
            Nazwa pliku
            <Input
              value={name}
              placeholder="twój login"
              maxLength={MAX_NAME}
              title="Same litery i cyfry; puste = twój login"
              onChange={(event) => changeName(event.target.value)}
              className="h-8 w-36 font-mono text-xs"
            />
          </label>
        </div>
      )}

      <div className="flex items-baseline justify-between gap-3">
        <h3 className="text-sm font-medium">Artykuły ({lines.length})</h3>
        {picked.length > 0 && (
          <span className="text-xs text-muted-foreground tabular-nums">zaznaczone etykiety: {labelsOf(picked)}</span>
        )}
      </div>
      <div className="max-h-[45vh] overflow-y-auto rounded-lg border">
        <ul aria-label="Etykiety do wydruku">
          {lines.map((line, index) => (
            <li key={index} className="border-b last:border-b-0">
              <label
                className={cn(
                  rowGrid,
                  'cursor-pointer px-3 py-1.5 text-sm hover:bg-accent has-checked:bg-blue-100 dark:has-checked:bg-blue-950/60',
                )}
              >
                <input
                  type="checkbox"
                  checked={!unchecked.has(index)}
                  onChange={() => toggle(index)}
                  className="size-4 accent-blue-600"
                />
                <span className="min-w-0">
                  <span className="block truncate font-medium" title={line.name || undefined}>
                    {line.name || line.ean}
                  </span>
                  <span className="block text-xs text-muted-foreground tabular-nums">
                    {line.ean}
                    {line.quantity != null && ` · ilość ${line.quantity}`}
                  </span>
                </span>
                <Input
                  type="number"
                  inputMode="numeric"
                  min={1}
                  max={MAX_LABELS}
                  aria-label={`Liczba etykiet: ${line.name || line.ean}`}
                  title="Liczba etykiet"
                  value={counts[index] ?? '1'}
                  aria-invalid={countOf(index) === null}
                  onChange={(event) =>
                    setCounts((current) => current.map((text, i) => (i === index ? event.target.value : text)))
                  }
                  onFocus={(event) => event.target.select()}
                  className="h-7 w-16 px-2 text-right tabular-nums"
                />
                {groupCount > 1 && (
                  <span className="flex gap-0.5" role="group" aria-label={`Grupa: ${line.name || line.ean}`}>
                    {Array.from({ length: groupCount }, (_, i) => i + 1).map((group) => (
                      <button
                        key={group}
                        type="button"
                        aria-pressed={groupOf(index) === group}
                        title={`Grupa ${group}`}
                        onClick={(event) => {
                          event.preventDefault()
                          setGroups((current) => current.map((value, i) => (i === index ? group : value)))
                        }}
                        className={cn(
                          'size-6 rounded text-xs font-medium tabular-nums',
                          groupOf(index) === group
                            ? 'bg-foreground text-background'
                            : 'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
                        )}
                      >
                        {group}
                      </button>
                    ))}
                  </span>
                )}
              </label>
            </li>
          ))}
        </ul>
        {lines.length === 0 && (
          <p className="px-3 py-6 text-center text-sm text-muted-foreground">Brak artykułów z EAN-em w tabeli.</p>
        )}
      </div>

      <SavedFolder folder={folder} />

      <DialogFooter className="sm:justify-between">
        <Button variant="ghost" onClick={() => onOpenChange(false)}>
          Zamknij
        </Button>
        <Button size="xl" loading={saving} disabled={picked.length === 0} onClick={() => void generate()}>
          <TagsIcon />
          Utwórz etykiety ({picked.length})
        </Button>
      </DialogFooter>
    </>
  )
}
