import { useState } from 'react'
import { ListChecksIcon } from 'lucide-react'
import { toast } from 'sonner'

import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useBakerySupplierArticles, useSaveHiddenArticles } from '@/features/bakery-check/api'
import { ApiError } from '@/lib/api'
import { matchesSearch } from '@/lib/search'
import type { BakeryArticle } from '@/lib/types'

/** Przycisk z oknem ustawień listy WZ, osobnej dla każdego dostawcy. */
export function WzArticlesButton({
  storeId,
  supplierId,
  supplierName,
}: {
  storeId: string
  supplierId: string
  supplierName: string
}) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        <ListChecksIcon />
        Artykuły na WZ
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="gap-4 p-6 sm:max-w-7xl">
          {open && (
            <ArticlesContent
              storeId={storeId}
              supplierId={supplierId}
              supplierName={supplierName}
              onDone={() => setOpen(false)}
            />
          )}
        </DialogContent>
      </Dialog>
    </>
  )
}

function ArticlesContent({
  storeId,
  supplierId,
  supplierName,
  onDone,
}: {
  storeId: string
  supplierId: string
  supplierName: string
  onDone: () => void
}) {
  const { data, isPending, isError } = useBakerySupplierArticles(storeId, supplierId, true)
  return (
    <>
      <DialogHeader>
        <DialogTitle className="text-lg">Artykuły na WZ: {supplierName}</DialogTitle>
        <DialogDescription>
          Odznaczonych artykułów tego dostawcy nie będzie na listach WZ. Wrócą same tylko wtedy, gdy są na przypiętym
          bonie, a pozostałe dopiszesz przyciskiem „Dodaj artykuł”.
        </DialogDescription>
      </DialogHeader>
      {isPending ? (
        <div aria-hidden className="h-64 animate-pulse rounded-xl bg-muted motion-reduce:animate-none" />
      ) : isError ? (
        <p className="py-6 text-center text-sm text-destructive">Nie udało się wczytać artykułów.</p>
      ) : data.articles.length === 0 ? (
        <p className="rounded-xl border px-4 py-6 text-center text-sm text-muted-foreground">
          Kartoteka nie ma artykułów tego dostawcy.
        </p>
      ) : (
        <ArticlesGrid
          key={supplierId}
          storeId={storeId}
          supplierId={supplierId}
          articles={data.articles}
          initialHidden={data.hiddenProductIds}
          onDone={onDone}
        />
      )}
    </>
  )
}

function ArticlesGrid({
  storeId,
  supplierId,
  articles,
  initialHidden,
  onDone,
}: {
  storeId: string
  supplierId: string
  articles: BakeryArticle[]
  initialHidden: string[]
  onDone: () => void
}) {
  const [hidden, setHidden] = useState(() => new Set(initialHidden))
  const [filter, setFilter] = useState('')
  const save = useSaveHiddenArticles(storeId, supplierId)

  const visible = articles.filter((article) => matchesSearch([article.name, article.brand, article.ean], filter))

  const toggle = (productId: string) =>
    setHidden((current) => {
      const next = new Set(current)
      if (!next.delete(productId)) next.add(productId)
      return next
    })

  const setVisible = (hide: boolean) =>
    setHidden((current) => {
      const next = new Set(current)
      visible.forEach((article) => (hide ? next.add(article.productId) : next.delete(article.productId)))
      return next
    })

  const submit = () => {
    save.mutate([...hidden], {
      onSuccess: () => {
        toast.success('Zapisano artykuły na WZ')
        onDone()
      },
      onError: (failure) =>
        toast.error(failure instanceof ApiError ? failure.message : 'Nie udało się zapisać artykułów'),
    })
  }

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <SearchInput
          value={filter}
          onChange={setFilter}
          placeholder="Szukaj: nazwa, marka, EAN"
          className="min-w-48 flex-1"
        />
        <Button variant="outline" size="sm" onClick={() => setVisible(false)}>
          Zaznacz wszystkie
        </Button>
        <Button variant="outline" size="sm" onClick={() => setVisible(true)}>
          Odznacz wszystkie
        </Button>
      </div>

      {visible.length === 0 ? (
        <p className="px-3 py-6 text-center text-sm text-muted-foreground">Nic nie pasuje do wyszukiwania.</p>
      ) : (
        <ul className="grid max-h-[60svh] overflow-x-hidden overflow-y-auto rounded-xl border sm:grid-cols-2 lg:grid-cols-4">
          {visible.map((article) => (
            <li key={article.productId} className="-mr-px -mb-px border-r border-b">
              <label className="flex h-full cursor-pointer items-center gap-2 px-3 py-1.5 text-sm hover:bg-accent has-checked:bg-blue-200 has-checked:hover:bg-blue-300 dark:has-checked:bg-blue-950 dark:has-checked:hover:bg-blue-900">
                <input
                  type="checkbox"
                  checked={!hidden.has(article.productId)}
                  onChange={() => toggle(article.productId)}
                  className="size-4 shrink-0 accent-blue-600"
                />
                <span className="min-w-0">
                  <span className="block truncate" title={article.name ?? undefined}>
                    {article.name ?? article.ean}
                  </span>
                  <span className="block truncate text-xs text-muted-foreground tabular-nums">{article.ean}</span>
                </span>
              </label>
            </li>
          ))}
        </ul>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <span className="text-sm text-muted-foreground tabular-nums" aria-live="polite">
          Na WZ: {articles.length - hidden.size} z {articles.length}
        </span>
        <Button onClick={submit} loading={save.isPending}>
          Zapisz
        </Button>
      </div>
    </>
  )
}
