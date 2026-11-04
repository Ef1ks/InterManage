import { type ReactNode, useState } from 'react'
import { PackageCheckIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { formatMoney } from '@/features/documents/format'
import { cn } from '@/lib/utils'

import { articleKey } from './duplicates'
import { type DataEntryRow, needsProductFile, purchasePerPiece, rowProblems } from './rows'

type Props = {
  /** Artykuły tabeli; ten sam EAN w kilku wierszach to jedna pozycja. */
  rows: DataEntryRow[]
  /** Wiersze zaznaczone w tabeli: zaznaczone na start okna. */
  selectedIds: string[]
  /** Dostawa z bazy zostawia nazwy jak w bazie, więc długa nazwa nie wyklucza artykułu. */
  allowLongNames?: boolean
  /** Dla którego dostawcy powstanie plik. */
  supplierNote: ReactNode
  saving: boolean
  open: boolean
  onOpenChange: (open: boolean) => void
  onSave: (rows: DataEntryRow[]) => void
}

type ArticleKind = 'new' | 'changed' | 'same'

type Article = { key: string; row: DataEntryRow; kind: ArticleKind; problem: string | null }

const KIND_LABELS: Record<ArticleKind, string> = { new: 'Nowy', changed: 'Zmieniony', same: 'Bez zmian' }

const KIND_STYLES: Record<ArticleKind, string> = {
  new: 'bg-violet-200 text-violet-950 dark:bg-violet-900/70 dark:text-violet-100',
  changed: 'bg-orange-200 text-orange-950 dark:bg-orange-900/70 dark:text-orange-100',
  same: 'bg-muted text-muted-foreground',
}

const ROW_GRID = 'grid grid-cols-[1rem_minmax(0,1fr)_8.5rem_4.5rem_4.5rem_6rem] items-center gap-3'

/** Czego brakuje artykułowi, żeby trafił do pliku CADBEZP. */
function problemOf(row: DataEntryRow, allowLongNames: boolean) {
  const problems = rowProblems(row)
  if (problems.includes('invalid-ean')) return 'błędny EAN'
  if (row.subfamilyCode === null) return 'brak podrodziny'
  if (!allowLongNames && problems.includes('name-too-long')) return 'za długa nazwa'
  return null
}

function articlesOf(rows: DataEntryRow[], allowLongNames: boolean) {
  const articles = new Map<string, Article>()
  let withoutEan = 0
  for (const row of rows) {
    if (row.ean === '' && row.name === '') continue
    const key = articleKey(row)
    if (row.eanFallback || !key || !/^\d{8,14}$/.test(row.ean)) {
      withoutEan++
    } else if (!articles.has(key)) {
      const kind = !row.known ? 'new' : needsProductFile(row) ? 'changed' : 'same'
      articles.set(key, { key, row, kind, problem: problemOf(row, allowLongNames) })
    }
  }
  return { articles: [...articles.values()], withoutEan }
}

/**
 * Wybór artykułów do pliku CADBEZP. Plikiem zakłada się i zmienia artykuły, a wczytany bez zmian w danych przepisuje
 * artykuły na dostawcę dokumentu, więc do wyboru są wszystkie artykuły tabeli, także niezmienione.
 */
export function ArticleFileDialog({ open, onOpenChange, ...props }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-4xl">
        <ArticleChoice {...props} onCancel={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

function ArticleChoice({
  rows,
  selectedIds,
  allowLongNames = false,
  supplierNote,
  saving,
  onCancel,
  onSave,
}: Omit<Props, 'open' | 'onOpenChange'> & { onCancel: () => void }) {
  const { articles, withoutEan } = articlesOf(rows, allowLongNames)
  const selectable = articles.filter((article) => article.problem === null)
  const inTable = new Set(rows.filter((row) => selectedIds.includes(row.id)).map(articleKey))
  const fromTable = selectable.filter((article) => inTable.has(article.key))
  const [chosen, setChosen] = useState(() => new Set(fromTable.map((article) => article.key)))

  const ofKind = (kind: ArticleKind) => selectable.filter((article) => article.kind === kind)
  const choose = (next: Article[]) => setChosen(new Set(next.map((article) => article.key)))
  const toggle = (key: string) =>
    setChosen((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })

  const picked = selectable.filter((article) => chosen.has(article.key))
  const blocked = articles.length - selectable.length
  const quickActions = [
    { label: 'Wszystkie', items: selectable },
    { label: 'Nowe', items: ofKind('new') },
    { label: 'Zmienione', items: ofKind('changed') },
    { label: 'Bez zmian', items: ofKind('same') },
    { label: 'Zaznaczone w tabeli', items: fromTable },
  ]

  return (
    <>
      <DialogHeader>
        <DialogTitle>Zmień artykuły</DialogTitle>
        <DialogDescription>
          {supplierNote}
          {withoutEan > 0 && ` Bez EAN-u: ${withoutEan}, nie trafią do pliku.`}
          {blocked > 0 && ` Do poprawienia w tabeli: ${blocked}.`}
        </DialogDescription>
      </DialogHeader>

      <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Szybkie zaznaczanie">
        {quickActions.map(({ label, items }) => (
          <Button key={label} variant="outline" size="sm" disabled={items.length === 0} onClick={() => choose(items)}>
            {label} ({items.length})
          </Button>
        ))}
        <Button
          variant="ghost"
          size="sm"
          disabled={selectable.length === 0}
          onClick={() => choose(selectable.filter((article) => !chosen.has(article.key)))}
        >
          Odwróć
        </Button>
        <Button variant="ghost" size="sm" disabled={picked.length === 0} onClick={() => choose([])}>
          Wyczyść
        </Button>
      </div>

      <div className="max-h-[55vh] overflow-y-auto rounded-lg border">
        <div
          className={cn(
            ROW_GRID,
            'sticky top-0 z-10 border-b bg-popover px-3 py-2 text-xs font-medium text-muted-foreground',
          )}
        >
          <span />
          <span>Artykuł</span>
          <span>EAN</span>
          <span className="text-right">Zakup</span>
          <span className="text-right">Sprzedaż</span>
          <span>Stan</span>
        </div>
        <ul aria-label="Artykuły">
          {articles.map(({ key, row, kind, problem }) => (
            <li key={key} className="border-b last:border-b-0">
              <label
                className={cn(
                  ROW_GRID,
                  'px-3 py-1.5 text-sm',
                  problem ? 'cursor-not-allowed opacity-70' : 'cursor-pointer hover:bg-accent',
                  'has-checked:bg-blue-100 dark:has-checked:bg-blue-950/60',
                )}
              >
                <input
                  type="checkbox"
                  checked={problem === null && chosen.has(key)}
                  disabled={problem !== null}
                  onChange={() => toggle(key)}
                  className="size-4 accent-blue-600"
                />
                <span className="min-w-0 truncate" title={row.name}>
                  {row.name}
                  {row.brand && <span className="ml-2 text-xs text-muted-foreground">{row.brand}</span>}
                </span>
                <span className="text-xs text-muted-foreground tabular-nums">{row.ean}</span>
                <Price value={purchasePerPiece(row)} />
                <Price value={row.salePrice} />
                <span
                  className={cn(
                    'w-fit rounded-md px-1.5 py-0.5 text-xs font-medium whitespace-nowrap',
                    problem ? 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100' : KIND_STYLES[kind],
                  )}
                >
                  {problem ?? KIND_LABELS[kind]}
                </span>
              </label>
            </li>
          ))}
        </ul>
        {articles.length === 0 && (
          <p className="px-3 py-6 text-center text-sm text-muted-foreground">W tabeli nie ma artykułów z EAN-em.</p>
        )}
      </div>

      <DialogFooter>
        <Button variant="ghost" onClick={onCancel}>
          Anuluj
        </Button>
        <Button loading={saving} disabled={picked.length === 0} onClick={() => onSave(picked.map(({ row }) => row))}>
          <PackageCheckIcon />
          Zapisz plik CADBEZP ({picked.length})
        </Button>
      </DialogFooter>
    </>
  )
}

function Price({ value }: { value: number | null }) {
  return <span className="text-right tabular-nums">{value === null ? '–' : formatMoney(value)}</span>
}
