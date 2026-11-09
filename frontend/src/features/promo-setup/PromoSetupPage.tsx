import { BadgePercentIcon, EraserIcon, FileOutputIcon, PackageSearchIcon, PlusIcon } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCan } from '@/features/auth/api'
import { CatalogListDialog } from '@/features/data-entry/CatalogListDialog'
import { paddedScaleCode } from '@/features/data-entry/rows'
import { ArticlePriceHistory } from '@/features/inventory/ArticlePriceHistory'
import type { ProFile } from '@/features/leaflet/leaflet-rows'
import { ProFilesDialog } from '@/features/leaflet/ProFilesDialog'
import { useSalesReportsOverview } from '@/features/sales/api'
import { ArticleSalesPanel } from '@/features/sales/SalesChartDialog'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { addDays, todayIso } from '@/lib/dates'
import { useProtoFolder } from '@/lib/proto-files'
import type { StoreSummary } from '@/lib/types'
import { store as reduxStore, useAppDispatch, useAppSelector } from '@/store'
import {
  addPromoRows,
  addPromoTile,
  DEFAULT_LEVELS,
  newPromoDraft,
  type PromoRow,
  type PromoSetupDraft,
  type PromoTile,
  setPromoDraft,
  setPromoLevels,
  updatePromoRow,
  updatePromoTile,
} from '@/store/promoSetupDraftSlice'

import { fetchPromoArticles, savePromoLabels } from './api'
import { DiscountLevelsDialog } from './DiscountLevelsDialog'
import { codeKey, discountedPrice, hasPeriod, promoFiles, tileTariff } from './promo-rows'
import { PromoTileCard } from './PromoTileCard'

const PRO_COPY = {
  description:
    'Jeden plik na każdą promocję: EAN-y artykułów z kartoteki i ceny promocji. Przy wczytaniu pliku w programie sklepu wpisz jego kod taryfy. Razem z nimi zapisze się plik etykiet PRO_PRO_FOR_67: po jednej na każdy artykuł z plików.',
  empty: 'Żadna promocja nie ma artykułów i okresu.',
  withoutPrice: 'bez ceny promocji',
}

/**
 * Ustawianie promocji: po lewej kafelki, każdy z własnymi artykułami i okresem, po prawej karta klikniętego artykułu
 * jak w Magazynie i produktach. Cenę promocji wpisuje się ręcznie albo wybiera przecenę od ceny z kartoteki; każdy
 * kafelek daje plik .PRO z kodem taryfy: cztery losowe cyfry pracy i numer kafelka (543101, 543102).
 */
export function PromoSetupPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Ustaw promocje" />
        <FullPageMessage title="Brak sklepu" description="Promocje są ustawiane osobno w każdym sklepie." inline />
      </>
    )
  }
  return <StorePromoSetup key={store.id} store={store} />
}

function StorePromoSetup({ store }: { store: StoreSummary }) {
  const dispatch = useAppDispatch()
  const stored = useAppSelector((state) => state.promoSetupDraft.drafts[store.id])
  const levels = useAppSelector((state) => state.promoSetupDraft.levels[store.id]) ?? DEFAULT_LEVELS
  const [fallback] = useState(newPromoDraft)
  const draft = stored ?? fallback
  const [params, setParams] = useSearchParams()
  const selected = params.get('artykul')
  const [listFor, setListFor] = useState<{ tileId: string; query: string } | null>(null)
  const [levelsOpen, setLevelsOpen] = useState(false)
  const [generated, setGenerated] = useState<ProFile[] | null>(null)
  const labelsFolder = useProtoFolder(store.id)

  const canSeeSales = useCan('SALES')
  const canSeeInventory = useCan('INVENTORY')
  const canSeeStats = canSeeSales || canSeeInventory
  const overview = useSalesReportsOverview(store.id, canSeeStats)
  // Wykres kończy się na ostatnim dniu ze sprzedażą, najpóźniej wczoraj (dzisiejsza nie jest jeszcze wgrana).
  const yesterday = addDays(todayIso(), -1)
  const savedUntil = overview.data?.lastSaleDate ?? null
  const lastSaleDate = savedUntil && savedUntil < yesterday ? savedUntil : yesterday

  useEffect(() => {
    if (!stored) dispatch(setPromoDraft({ storeId: store.id, draft: fallback }))
  }, [dispatch, fallback, store.id, stored])

  // Szkic bywa sprzed kilku dni: ceny i ostatni zakup od nowa, a cena z przeceny idzie za ceną z kartoteki.
  useEffect(() => {
    const current = reduxStore.getState().promoSetupDraft.drafts[store.id]
    const codes = [...new Set(current?.tiles.flatMap((tile) => tile.rows.map((row) => codeKey(row.code))) ?? [])]
    if (codes.length === 0) return
    fetchPromoArticles(store.id, codes)
      .then((articles) => {
        const latest = reduxStore.getState().promoSetupDraft.drafts[store.id]
        if (!latest) return
        const refreshed = (row: PromoRow): PromoRow => {
          const article = articles.get(codeKey(row.code)) ?? null
          if (!article) return { ...row, article: null }
          const price = row.discount === null ? row.price : discountedPrice(article.regularPrice, row.discount)
          return { ...row, article, price }
        }
        const tiles = latest.tiles.map((tile) => ({ ...tile, rows: tile.rows.map(refreshed) }))
        dispatch(setPromoDraft({ storeId: store.id, draft: { ...latest, tiles } }))
      })
      .catch(() => toast.error('Nie udało się odświeżyć cen artykułów.'))
  }, [dispatch, store.id])

  const files = useMemo(() => promoFiles(draft), [draft])
  const incomplete = draft.tiles.filter((tile) => tile.rows.length > 0 && !hasPeriod(tile))
  const articleCount = draft.tiles.reduce((sum, tile) => sum + tile.rows.length, 0)
  const listTile = draft.tiles.find((tile) => tile.id === listFor?.tileId)
  const tableEans = useMemo(
    () => new Set(listTile?.rows.flatMap((row) => (row.article?.ean ? [codeKey(row.article.ean)] : [])) ?? []),
    [listTile],
  )

  const restore = (previous: PromoSetupDraft) => dispatch(setPromoDraft({ storeId: store.id, draft: previous }))

  const select = (row: PromoRow) => {
    if (!row.article?.itm8) return
    const itm8 = row.article.itm8
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        next.set('artykul', itm8)
        return next
      },
      { replace: true },
    )
  }

  /** Karta artykułu nie zostaje po usuniętym wierszu. */
  const closeArticle = (rows: PromoRow[]) => {
    if (!rows.some((row) => row.article?.itm8 === selected)) return
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        next.delete('artykul')
        return next
      },
      { replace: true },
    )
  }

  /** Dopisuje artykuły do kafelka; artykuł, który już w nim jest, pomija, a nieznany kod zostaje wierszem z błędem. */
  const addCodes = async (tileId: string, entered: string[]) => {
    // Krótki kod to ITM8 albo kod z etykiety wagi bez końca, więc szukamy obu.
    const keys = entered.flatMap((code) => [codeKey(code), codeKey(paddedScaleCode(code))])
    const articles = await fetchPromoArticles(store.id, [...new Set(keys)]).catch(() => null)
    if (!articles) {
      toast.error('Nie udało się wyszukać artykułów.')
      return
    }
    const tile = reduxStore.getState().promoSetupDraft.drafts[store.id]?.tiles.find((item) => item.id === tileId)
    const present = new Set(tile?.rows.map((row) => row.article?.ean ?? row.code))
    const rows: PromoRow[] = []
    let repeated = 0
    for (const entry of entered) {
      const article = articles.get(codeKey(entry)) ?? articles.get(codeKey(paddedScaleCode(entry))) ?? null
      const code = article?.code ?? entry
      const key = article?.ean ?? code
      if (present.has(key)) repeated += 1
      else {
        present.add(key)
        rows.push({ id: crypto.randomUUID(), code, article, price: null, discount: null })
      }
    }
    dispatch(addPromoRows({ storeId: store.id, tileId, rows }))
    if (repeated > 0) toast.info(`Już w tej promocji: ${repeated}`)
    const last = rows.at(-1)
    if (last) select(last)
  }

  const setPrice = (tile: PromoTile, row: PromoRow, price: number | null) =>
    dispatch(updatePromoRow({ storeId: store.id, tileId: tile.id, rowId: row.id, changes: { price, discount: null } }))

  const setDiscount = (tile: PromoTile, row: PromoRow, discount: number | null) => {
    const price = discount === null ? row.price : discountedPrice(row.article?.regularPrice ?? null, discount)
    dispatch(updatePromoRow({ storeId: store.id, tileId: tile.id, rowId: row.id, changes: { price, discount } }))
  }

  /** Ten sam stopień przeceny dla wszystkich artykułów kafelka; ręcznie wpisane ceny też są zastępowane. */
  const setTileDiscount = (tile: PromoTile, discount: number) => {
    const rows = tile.rows.map((row) =>
      row.article ? { ...row, discount, price: discountedPrice(row.article.regularPrice, discount) } : row,
    )
    const tiles = draft.tiles.map((item) => (item.id === tile.id ? { ...item, rows } : item))
    dispatch(setPromoDraft({ storeId: store.id, draft: { ...draft, tiles } }))
    const count = rows.filter((row) => row.article).length
    toast.success(`Promocja ${tile.number}: przecena -${String(discount).replace('.', ',')}% dla ${count} art.`, {
      action: { label: 'Przywróć', onClick: () => restore(draft) },
    })
  }

  const removeRow = (tile: PromoTile, row: PromoRow) => {
    const tiles = draft.tiles.map((item) =>
      item.id === tile.id ? { ...item, rows: item.rows.filter((candidate) => candidate.id !== row.id) } : item,
    )
    dispatch(setPromoDraft({ storeId: store.id, draft: { ...draft, tiles } }))
    closeArticle([row])
    toast.success(`Usunięto: ${row.article?.name ?? row.code}`, {
      action: { label: 'Przywróć', onClick: () => restore(draft) },
    })
  }

  const removeTile = (tile: PromoTile) => {
    const tiles = draft.tiles.filter((item) => item.id !== tile.id)
    dispatch(setPromoDraft({ storeId: store.id, draft: { ...draft, tiles } }))
    closeArticle(tile.rows)
    toast.success(`Usunięto promocję ${tile.number}`, {
      action: { label: 'Przywróć', onClick: () => restore(draft) },
    })
  }

  const clear = () => {
    dispatch(setPromoDraft({ storeId: store.id, draft: newPromoDraft() }))
    closeArticle(draft.tiles.flatMap((tile) => tile.rows))
    toast.success('Wyczyszczono promocje', { action: { label: 'Przywróć', onClick: () => restore(draft) } })
  }

  return (
    <>
      <span data-wide-page hidden />
      <PageHeader
        title="Ustaw promocje"
        description={`${storeLabel(store)}: kilka promocji naraz, każda z własnymi artykułami i okresem.`}
      />

      <div className="sticky top-14 z-15 mb-3 flex flex-wrap items-center gap-2 rounded-lg border bg-muted p-1.5 shadow-xs">
        <span className="px-2 text-sm text-muted-foreground tabular-nums">
          Promocje: {draft.tiles.length} · artykuły: {articleCount}
        </span>
        <Button variant="ghost" className="ml-auto" disabled={articleCount === 0} onClick={clear}>
          <EraserIcon />
          Wyczyść
        </Button>
        <Button
          disabled={files.size === 0}
          title={files.size === 0 ? 'Dodaj artykuły i ustaw okres promocji' : undefined}
          onClick={() => setGenerated([...files.values()])}
        >
          <FileOutputIcon />
          Wygeneruj wszystkie pliki promo
        </Button>
      </div>

      <div className="grid items-start gap-4 lg:grid-cols-2">
        <div className="grid min-w-0 gap-4">
          {draft.tiles.map((tile) => {
            const file = files.get(tile.id)
            return (
              <PromoTileCard
                key={tile.id}
                tile={tile}
                tariff={tileTariff(draft, tile)}
                levels={levels}
                selected={selected}
                canGenerate={file !== undefined}
                onSelect={select}
                onDates={(changes) => dispatch(updatePromoTile({ storeId: store.id, tileId: tile.id, changes }))}
                onCodes={(codes) => void addCodes(tile.id, codes)}
                onSearch={(query) => setListFor({ tileId: tile.id, query })}
                onList={() => setListFor({ tileId: tile.id, query: '' })}
                onPrice={(row, price) => setPrice(tile, row, price)}
                onDiscount={(row, discount) => setDiscount(tile, row, discount)}
                onTileDiscount={(discount) => setTileDiscount(tile, discount)}
                onRemoveRow={(row) => removeRow(tile, row)}
                onRemove={() => removeTile(tile)}
                onGenerate={() => file && setGenerated([file])}
              />
            )
          })}

          <div className="flex flex-wrap gap-2">
            <Button variant="outline" size="lg" onClick={() => dispatch(addPromoTile({ storeId: store.id }))}>
              <PlusIcon />
              Dodaj promocję
            </Button>
            <Button variant="outline" size="lg" onClick={() => setLevelsOpen(true)}>
              <BadgePercentIcon />
              Stwórz nowe automatyczne przeceny
            </Button>
          </div>
        </div>

        <section
          aria-label="Karta artykułu"
          className="min-w-0 lg:sticky lg:top-30 lg:max-h-[calc(100dvh-8.5rem)] lg:overflow-y-auto"
        >
          {!canSeeStats ? (
            <Placeholder text="Statystyki artykułu wymagają dostępu do raportów sprzedaży albo magazynu." />
          ) : selected ? (
            <Card className="gap-5 px-5 py-5 sm:px-6">
              <ArticleSalesPanel
                storeId={store.id}
                target={{ kind: 'article', itm8: selected, name: null, ean: null, brand: null }}
                lastSaleDate={lastSaleDate}
              />
              {canSeeInventory && <ArticlePriceHistory key={selected} storeId={store.id} itm8={selected} />}
            </Card>
          ) : (
            <Placeholder text="Kliknij artykuł w promocji, a zobaczysz jego stan, sprzedaż, dostawy i zmiany cen." />
          )}
        </section>
      </div>

      {listFor && (
        <CatalogListDialog
          key={`${listFor.tileId}:${listFor.query}`}
          storeId={store.id}
          open
          onOpenChange={(open) => !open && setListFor(null)}
          inTable={tableEans}
          quickAdd
          onAdd={(additions) =>
            addCodes(
              listFor.tileId,
              additions.map(({ item }) => item.ean),
            )
          }
          initialQuery={listFor.query}
          title={`Lista towarów · promocja ${listTile?.number ?? ''}`}
          lastSaleDate={canSeeSales ? lastSaleDate : null}
        />
      )}

      <DiscountLevelsDialog
        levels={levels}
        onChange={(next) => dispatch(setPromoLevels({ storeId: store.id, levels: next }))}
        open={levelsOpen}
        onOpenChange={setLevelsOpen}
      />

      <ProFilesDialog
        files={generated ?? []}
        open={generated !== null}
        onOpenChange={(open) => !open && setGenerated(null)}
        copy={PRO_COPY}
        labels={{
          folder: labelsFolder,
          save: (files, folderReady) => savePromoLabels(store.id, files, labelsFolder.key, folderReady),
        }}
        notice={
          generated !== null && generated.length > 1 && incomplete.length > 0
            ? `Bez okresu, nie trafią do plików: ${incomplete.map((tile) => `promocja ${tile.number}`).join(', ')}.`
            : undefined
        }
      />
    </>
  )
}

function Placeholder({ text }: { text: string }) {
  return (
    <Card className="min-h-80 items-center justify-center gap-2 px-6 py-12 text-center">
      <PackageSearchIcon aria-hidden className="size-8 text-muted-foreground" />
      <p className="max-w-sm text-sm text-muted-foreground">{text}</p>
    </Card>
  )
}
