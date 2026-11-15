import { useMemo, useState } from 'react'
import { Link2Icon, Unlink2Icon } from 'lucide-react'

import { SegmentedField } from '@/components/form'
import { SearchInput } from '@/components/SearchInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { useArticleLinks } from '@/features/article-links/api'
import { shortItm8 } from '@/features/article-links/format'
import { cn } from '@/lib/utils'

export type LinkStore = { id: string; number: string; name: string }
export type LinkItem = { itm8: string; name: string | null; brand?: string | null; hint?: string }

/** Sklep, w którym wiersz nie ma artykułu, z artykułami tego sklepu, które nie mają odpowiednika w sklepie źródłowym. */
export type LinkTarget = { store: LinkStore; candidates: LinkItem[] }

export type ExistingLink = { store: LinkStore; item: LinkItem; linkId: string }

const normalize = (text: string) =>
  text
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/ł/gi, 'l')
    .toUpperCase()
    .split(/[^A-Z0-9]+/)
    .filter((word) => word.length >= 2)

/**
 * Jak bardzo nazwa kandydata przypomina nazwę artykułu: udział słów artykułu, które mają w kandydacie słowo o tych
 * samych trzech pierwszych znakach (sklepy skracają nazwy różnie: „KARKOWKA B/K” i „KARK. BEZ KOSCI”).
 */
function similarity(source: string[], candidate: string | null) {
  if (source.length === 0 || !candidate) return 0
  const words = normalize(candidate)
  let score = 0
  for (const word of source) {
    if (words.includes(word)) score += 1
    else if (word.length >= 3 && words.some((other) => other.slice(0, 3) === word.slice(0, 3))) score += 0.6
  }
  return score / source.length
}

/**
 * Łączy artykuł jednego sklepu z tym samym towarem w innym sklepie, który ma inne kody i nazwę. Połączenie zapisuje
 * się w bazie i od razu działa w raporcie lady i w porównaniu cen mięsa, w obie strony.
 */
export function ArticleLinkDialog({
  storeId,
  open,
  onOpenChange,
  source,
  linked,
  targets,
  canEdit,
}: {
  storeId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  source: { store: LinkStore; item: LinkItem }
  linked: ExistingLink[]
  targets: LinkTarget[]
  canEdit: boolean
}) {
  const links = useArticleLinks(storeId)
  const [targetId, setTargetId] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const target = targets.find((item) => item.store.id === targetId) ?? targets[0]

  const sourceWords = useMemo(() => normalize(source.item.name ?? ''), [source.item.name])
  const candidates = useMemo(() => {
    if (!target) return []
    const words = normalize(search)
    return target.candidates
      .map((item) => ({ item, score: similarity(sourceWords, item.name) }))
      .filter(({ item }) => {
        if (words.length === 0) return true
        const haystack = normalize(`${item.name ?? ''} ${item.brand ?? ''} ${shortItm8(item.itm8)}`)
        return words.every((word) => haystack.some((other) => other.startsWith(word)))
      })
      .sort((a, b) => b.score - a.score || (a.item.name ?? '').localeCompare(b.item.name ?? '', 'pl'))
  }, [target, search, sourceWords])

  const close = () => onOpenChange(false)

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Połącz artykuły sklepów</DialogTitle>
          <DialogDescription>
            Każdy sklep ma własne kody i nazwy. Połączony artykuł raporty porównują w jednym wierszu, także później i w
            obu sklepach.
          </DialogDescription>
        </DialogHeader>

        <ArticleLine store={source.store} item={source.item} emphasis />

        {linked.length > 0 && (
          <section className="space-y-2">
            <h3 className="text-sm font-medium">Połączony z</h3>
            <ul className="divide-y rounded-lg border">
              {linked.map((entry) => (
                <li key={entry.linkId} className="flex items-center gap-3 px-3 py-2">
                  <ArticleLine store={entry.store} item={entry.item} />
                  {canEdit && (
                    <Button
                      variant="outline"
                      size="sm"
                      className="ml-auto shrink-0"
                      disabled={links.pending}
                      onClick={() => links.unlink(entry.linkId, entry.item.name ?? shortItm8(entry.item.itm8), close)}
                    >
                      <Unlink2Icon />
                      Odłącz
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          </section>
        )}

        {canEdit && target && (
          <section className="space-y-3">
            <div className="flex flex-wrap items-end justify-between gap-3">
              <h3 className="text-sm font-medium">Ten sam towar w sklepie {target.store.number}</h3>
              {targets.length > 1 && (
                <SegmentedField
                  label="Sklep"
                  options={targets.map((item) => ({ value: item.store.id, label: item.store.number }))}
                  value={target.store.id}
                  onChange={(value) => setTargetId(value)}
                />
              )}
            </div>
            <SearchInput
              value={search}
              onChange={setSearch}
              placeholder="Szukaj po nazwie, marce albo ITM8"
              autoFocus
            />
            {candidates.length === 0 ? (
              <p className="rounded-lg bg-muted px-3 py-6 text-center text-sm text-muted-foreground">
                {target.candidates.length === 0
                  ? `Sklep ${target.store.number} nie ma w tym okresie artykułu bez odpowiednika.`
                  : 'Nic nie pasuje do wyszukiwania.'}
              </p>
            ) : (
              <ul className="max-h-[50vh] divide-y overflow-y-auto rounded-lg border">
                {candidates.map(({ item, score }, index) => (
                  <li key={item.itm8} className="flex items-center gap-3 px-3 py-2 hover:bg-muted">
                    <ArticleLine item={item} />
                    {index === 0 && score >= 0.5 && search === '' && (
                      <span className="shrink-0 rounded-md bg-sky-200 px-1.5 py-0.5 text-xs font-medium text-sky-950 dark:bg-sky-900 dark:text-sky-100">
                        najbardziej podobny
                      </span>
                    )}
                    <Button
                      size="sm"
                      className="ml-auto shrink-0"
                      disabled={links.pending}
                      onClick={() =>
                        links.link(
                          {
                            storeId: source.store.id,
                            itm8: source.item.itm8,
                            linkedStoreId: target.store.id,
                            linkedItm8: item.itm8,
                          },
                          `${source.item.name ?? shortItm8(source.item.itm8)} = ${item.name ?? shortItm8(item.itm8)} (${target.store.number})`,
                          close,
                        )
                      }
                    >
                      <Link2Icon />
                      Połącz
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}
      </DialogContent>
    </Dialog>
  )
}

function ArticleLine({ store, item, emphasis }: { store?: LinkStore; item: LinkItem; emphasis?: boolean }) {
  return (
    <div className={cn('min-w-0', emphasis && 'rounded-lg bg-muted px-3 py-2')}>
      <p className={cn('truncate text-sm', emphasis && 'font-semibold')} title={item.name ?? undefined}>
        {store && <span className="mr-1.5 font-semibold tabular-nums">{store.number}</span>}
        {item.name ?? 'Brak nazwy w kartotece'}
      </p>
      <p className="truncate text-xs text-muted-foreground tabular-nums">
        {/* Marka mięsa bywa nazwą podrodziny (WIEPRZOWE), więc bez powtórzeń. */}
        {[...new Set([`ITM8 ${shortItm8(item.itm8)}`, item.brand, item.hint].filter(Boolean))].join(' · ')}
      </p>
    </div>
  )
}
