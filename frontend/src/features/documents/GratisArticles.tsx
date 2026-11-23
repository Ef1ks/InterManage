import { useState } from 'react'
import { GiftIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { LoadingPanel } from '@/components/LoadingPanel'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useCan } from '@/features/auth/api'
import { type GratisArticle, useGratisArticleChanges, useGratisArticles } from '@/features/documents/api'
import { useProductList } from '@/features/inventory/api'
import { ApiError } from '@/lib/api'
import { useDebouncedValue } from '@/lib/use-debounced-value'

/** Ile wyników wyszukiwania kartoteki pokazujemy naraz. */
const SEARCH_RESULTS = 20

const errorText = (error: unknown) => (error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

/** ITM8 bez zer wiodących, jak w systemie sklepu. */
const shortItm8 = (itm8: string) => itm8.replace(/^0+(?=\d)/, '')

/**
 * Przycisk nad listą przeniesień stanów: lista artykułów z kartoteki sklepu, które oznaczają gratis. Taki artykuł na
 * minusie robi z przeniesienia kandydata na gratis, a na plusie podświetla dokument jako błędny.
 */
export function GratisArticlesButton({ storeId }: { storeId: string }) {
  const [open, setOpen] = useState(false)
  const { data } = useGratisArticles(storeId)

  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <GiftIcon />
        Artykuły gratisowe{data ? ` (${data.length})` : ''}
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-5 p-6 sm:max-w-3xl">
          {open && <GratisArticlesBody storeId={storeId} />}
        </DialogContent>
      </Dialog>
    </>
  )
}

function GratisArticlesBody({ storeId }: { storeId: string }) {
  const canEdit = useCan('DOCUMENTS', 'EDIT')
  const { data: articles, isPending, isError } = useGratisArticles(storeId)
  const { add, remove } = useGratisArticleChanges(storeId)
  const listed = new Set((articles ?? []).map((article) => article.itm8))

  const addArticle = (itm8: string, restored = false) =>
    add.mutate(itm8, {
      onSuccess: (article) =>
        toast.success(
          restored ? `Przywrócono ${article.name ?? shortItm8(article.itm8)}` : `Dodano ${article.name ?? 'artykuł'}`,
        ),
      onError: (error) => toast.error(errorText(error)),
    })

  const removeArticle = (article: GratisArticle) =>
    remove.mutate(article.id, {
      onSuccess: () =>
        toast.success(`Usunięto ${article.name ?? shortItm8(article.itm8)} z listy`, {
          action: { label: 'Przywróć', onClick: () => addArticle(article.itm8, true) },
        }),
      onError: (error) => toast.error(errorText(error)),
    })

  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Artykuły gratisowe</DialogTitle>
        <DialogDescription>
          Artykuły z kartoteki sklepu, którymi oznacza się gratis w przeniesieniach stanów. Artykuł na minusie: dokument
          może być gratisem. Na plusie: coś jest nie tak, dokument jest wyróżniony na różowo.
        </DialogDescription>
      </DialogHeader>

      {isPending ? (
        <LoadingPanel className="h-32 rounded-xl" />
      ) : isError ? (
        <p className="text-sm text-muted-foreground">Nie udało się wczytać listy.</p>
      ) : articles.length === 0 ? (
        <p className="rounded-xl border border-dashed px-4 py-6 text-center text-sm text-muted-foreground">
          Lista jest pusta, więc żadne przeniesienie nie jest oznaczane jako możliwy gratis.
        </p>
      ) : (
        <ul className="divide-y rounded-xl border">
          {articles.map((article) => (
            <li key={article.id} className="flex items-center gap-3 px-4 py-2.5">
              <GiftIcon aria-hidden className="size-4 shrink-0 text-amber-600 dark:text-amber-400" />
              <div className="min-w-0 flex-1 text-sm">
                <p className="truncate font-medium">
                  {article.name ?? 'Artykuł bez nazwy'}
                  {article.brand && <span className="font-normal text-muted-foreground"> · {article.brand}</span>}
                </p>
                <p className="truncate text-xs text-muted-foreground tabular-nums">
                  ITM8 {shortItm8(article.itm8)}
                  {article.ean && ` · EAN ${article.ean}`}
                  {article.addedBy && ` · dodał(a) ${article.addedBy}`}
                </p>
              </div>
              {canEdit && (
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={`Usuń ${article.name ?? shortItm8(article.itm8)} z listy`}
                  title="Usuń z listy"
                  loading={remove.isPending && remove.variables === article.id}
                  onClick={() => removeArticle(article)}
                >
                  <Trash2Icon />
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {canEdit && (
        <CatalogSearch
          storeId={storeId}
          listed={listed}
          adding={add.isPending ? add.variables : null}
          onAdd={addArticle}
        />
      )}
    </>
  )
}

/** Wyszukiwarka kartoteki sklepu (nazwa, marka, ITM8, EAN) z dodawaniem na listę. */
function CatalogSearch({
  storeId,
  listed,
  adding,
  onAdd,
}: {
  storeId: string
  listed: Set<string>
  adding: string | null
  onAdd: (itm8: string) => void
}) {
  const [query, setQuery] = useState('')
  const debounced = useDebouncedValue(query.trim(), 250)
  const { data, isFetching } = useProductList(storeId, debounced, '')
  const items = debounced ? (data?.pages[0]?.items.slice(0, SEARCH_RESULTS) ?? []) : []

  return (
    <section aria-labelledby="gratis-article-search" className="grid gap-2">
      <h3 id="gratis-article-search" className="text-sm font-medium">
        Dodaj artykuł z kartoteki
      </h3>
      <SearchInput value={query} onChange={setQuery} placeholder="Nazwa, marka, ITM8 albo EAN, np. gratis" />
      {debounced &&
        (items.length === 0 ? (
          <p className="text-sm text-muted-foreground">{isFetching ? 'Szukam…' : 'Nic nie znaleziono w kartotece.'}</p>
        ) : (
          <ul className="max-h-72 divide-y overflow-y-auto rounded-xl border">
            {items.map((item) => {
              const already = listed.has(item.itm8)
              return (
                <li key={item.itm8} className="flex items-center gap-3 px-4 py-2">
                  <div className="min-w-0 flex-1 text-sm">
                    <p className="truncate">
                      {item.name ?? 'Artykuł bez nazwy'}
                      {item.brand && <span className="text-muted-foreground"> · {item.brand}</span>}
                    </p>
                    <p className="truncate text-xs text-muted-foreground tabular-nums">
                      ITM8 {shortItm8(item.itm8)} · EAN {item.ean}
                    </p>
                  </div>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={already}
                    loading={adding === item.itm8}
                    onClick={() => onAdd(item.itm8)}
                  >
                    {!already && <PlusIcon />}
                    {already ? 'Na liście' : 'Dodaj'}
                  </Button>
                </li>
              )
            })}
          </ul>
        ))}
    </section>
  )
}
