import { useRef } from 'react'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { Card } from '@/components/ui/card'
import { formatDocumentDate } from '@/features/documents/format'
import { MIN_LABEL_QUERY, useLeafletLabelCheck } from '@/features/leaflet/api'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import type { LeafletLabelArticle, LeafletLabelLine, LeafletLabelStatus, StoreSummary } from '@/lib/types'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

const LINE_STATUS: Record<Exclude<LeafletLabelStatus, 'NONE'>, { title: string; tone: string }> = {
  CURRENT: {
    title: 'W obecnej gazetce',
    tone: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  },
  UPCOMING: { title: 'W kolejnej gazetce', tone: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100' },
  PAST: {
    title: 'Nie ma w obecnej gazetce, ostatnio',
    tone: 'bg-red-200 text-red-950 dark:bg-red-900/70 dark:text-red-100',
  },
}

/**
 * Sprawdzarka etykiet: po zeskanowaniu EAN-u albo wpisaniu nazwy, marki czy ITM8 pracownik widzi, jaką etykietę ma
 * wydrukować (OZNACZENIE PRZY PÓŁCE z pliku gazetki) i od kiedy do kiedy obowiązuje. Działa też na telefonie.
 */
export function LeafletLabelCheckPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Etykieta z gazetki" />
        <FullPageMessage title="Brak sklepu" description="Gazetki są wgrywane osobno w każdym sklepie." inline />
      </>
    )
  }
  return <StoreLabelCheck key={store.id} store={store} />
}

function StoreLabelCheck({ store }: { store: StoreSummary }) {
  const { text, setText, debounced } = useSearchParamText('szukaj')
  const check = useLeafletLabelCheck(store.id, debounced)
  const input = useRef<HTMLInputElement>(null)
  const tooShort = debounced.length < MIN_LABEL_QUERY

  return (
    <div className="max-w-2xl">
      <PageHeader title="Etykieta z gazetki" description={storeLabel(store)} />
      <SearchInput
        inputRef={input}
        autoFocus
        value={text}
        onChange={setText}
        // Czytnik kończy kod Enterem: zaznaczony tekst zastąpi kolejny skan.
        onEnter={() => input.current?.select()}
        placeholder="Zeskanuj EAN albo wpisz nazwę, markę, ITM8"
        className="mb-4"
      />
      {tooShort ? (
        <p className="text-sm text-muted-foreground">Zeskanuj artykuł albo wpisz co najmniej 3 znaki.</p>
      ) : check.isPending ? (
        <p className="text-sm text-muted-foreground">Szukam…</p>
      ) : check.isError ? (
        <p className="text-sm text-destructive">Nie udało się sprawdzić artykułu. Spróbuj ponownie.</p>
      ) : check.data.articles.length === 0 ? (
        <p className="text-sm text-muted-foreground">Nie znaleziono artykułu.</p>
      ) : (
        <div className="flex flex-col gap-3">
          {check.data.articles.map((article) => (
            <ArticleCard key={`${article.ean}:${article.name}`} article={article} />
          ))}
          {check.data.total > check.data.articles.length && (
            <p className="text-sm text-muted-foreground">
              Pokazano {check.data.articles.length} z {check.data.total}. Doprecyzuj wyszukiwanie.
            </p>
          )}
        </div>
      )}
    </div>
  )
}

function ArticleCard({ article }: { article: LeafletLabelArticle }) {
  return (
    <Card className="gap-3 p-4">
      <div className="min-w-0">
        <p className="font-medium break-words">
          {article.name}
          {article.itm8 && (
            <ArticleStatsButton
              article={{ itm8: article.itm8, name: article.name, ean: article.ean, brand: article.brand }}
              className="ml-1"
            />
          )}
        </p>
        <p className="text-sm text-muted-foreground tabular-nums">
          {[article.brand, article.ean].filter(Boolean).join(' · ')}
        </p>
      </div>
      {article.lines.length === 0 ? (
        <p className="rounded-lg bg-muted px-3 py-2 text-sm text-muted-foreground">
          Nie ma go w żadnej wgranej gazetce.
        </p>
      ) : (
        article.lines.map((line) => (
          <LabelLine key={`${line.year}/${line.week}:${line.dateFrom}:${line.dateTo}:${line.shelfLabel}`} line={line} />
        ))
      )}
    </Card>
  )
}

function LabelLine({ line }: { line: LeafletLabelLine }) {
  const { title, tone } = LINE_STATUS[line.status as keyof typeof LINE_STATUS]
  return (
    <div className={cn('rounded-lg px-3 py-2.5', tone)}>
      <p className="text-sm">
        {title} · gazetka {line.week}/{line.year}
      </p>
      <p className="text-lg leading-snug font-semibold">{line.shelfLabel ?? 'Brak oznaczenia w gazetce'}</p>
      <p className="text-sm font-medium tabular-nums">
        {formatDocumentDate(line.dateFrom)} – {formatDocumentDate(line.dateTo)}
      </p>
    </div>
  )
}
