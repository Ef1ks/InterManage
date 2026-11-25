import { BadgePercentIcon, EraserIcon, FileOutputIcon, PackageSearchIcon, PlusIcon } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { SegmentedField } from '@/components/form'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCan } from '@/features/auth/api'
import { CatalogListDialog } from '@/features/data-entry/CatalogListDialog'
import { formatDocumentDate } from '@/features/documents/format'
import { paddedScaleCode } from '@/features/data-entry/rows'
import { useAddPriceMarkdowns } from '@/features/inventory/api'
import { ArticlePriceHistory } from '@/features/inventory/ArticlePriceHistory'
import { PriceLabelsButton } from '@/features/inventory/PriceLabelsDialog'
import { buildPromoEntryMacro, buildPromoResumeMacro, mercalysVatCode } from '@/features/data-entry/mercalys-macro'
import { type ProFile, proFileName } from '@/features/leaflet/leaflet-rows'
import { ProFilesDialog } from '@/features/leaflet/ProFilesDialog'
import { useSalesReportsOverview } from '@/features/sales/api'
import { ArticleSalesPanel } from '@/features/sales/SalesChartDialog'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { addDays, todayIso } from '@/lib/dates'
import { useProtoFolder } from '@/lib/proto-files'
import type { StoreSummary } from '@/lib/types'
import { store as reduxStore, useAppDispatch, useAppSelector } from '@/store'
import {
  addPromoRows,
  addPromoTile,
  DEFAULT_LEVELS,
  newPromoDraft,
  promoLabelKey,
  randomOperation,
  type PromoMode,
  type PromoRow,
  type PromoSetupDraft,
  type PromoTile,
  setPromoDraft,
  setPromoLevels,
  updatePromoRow,
  updatePromoTile,
} from '@/store/promoSetupDraftSlice'

import { markPromotionInserted } from '@/features/collectors/promotions-api'

import { fetchPromoArticles, savePromoLabels } from './api'
import { DiscountLevelsDialog } from './DiscountLevelsDialog'
import type { PromoSetupHandoff } from './paths'
import {
  codeKey,
  discountedPrice,
  hasPeriod,
  macroBlocked,
  macroRows,
  macroStartOf,
  promoFiles,
  tileTariff,
} from './promo-rows'

const macroItems = (tile: PromoTile) =>
  macroRows(tile).map((row) => ({
    ean: row.article!.ean!.replace(/^0+(?=\d{13})/, ''),
    price: row.price!,
    dateTo: row.dateTo!,
    vatCode: mercalysVatCode(row.article!.vatPercent),
  }))
import { PromoTileCard } from './PromoTileCard'

const PRO_COPY = {
  description:
    'Jeden plik na każdą promocję: EAN-y artykułów z kartoteki i ceny promocji. Przy wczytaniu pliku w programie sklepu wpisz jego kod taryfy. Razem z nimi zapisze się plik etykiet PRO_PRO_FOR_67: po jednej na każdy artykuł z plików.',
  empty: 'Żadna promocja nie ma artykułów i okresu.',
  withoutPrice: 'bez ceny promocji',
}

const MODE_OPTIONS: { value: PromoMode; label: string }[] = [
  { value: 'macro', label: 'Makro' },
  { value: 'pro', label: 'Ceny promocji z pliku' },
]

/** Tryb w adresie: bez parametru makro (główny), {@code ?tryb=pro} pliki .PRO. */
const modeOf = (params: URLSearchParams): PromoMode => (params.get('tryb') === 'pro' ? 'pro' : 'macro')

const draftsOf = (mode: PromoMode) => {
  const state = reduxStore.getState().promoSetupDraft
  return mode === 'macro' ? state.macroDrafts : state.drafts
}

/**
 * Ustawianie promocji w dwóch trybach. Makro (główny): jeden kafelek, każdy artykuł z własnym końcem promocji, a
 * „Stwórz automatycznie” ustawia je w programie sklepu. Pliki .PRO: kafelki, każdy z własnymi artykułami i okresem,
 * każdy daje plik .PRO z kodem taryfy: cztery losowe cyfry pracy i numer kafelka (543101, 543102). Po prawej karta
 * klikniętego artykułu jak w Magazynie i produktach. Cenę promocji wpisuje się ręcznie albo wybiera przecenę od ceny
 * z kartoteki.
 */
export function PromoSetupPage() {
  const store = useCurrentStore()
  const [params] = useSearchParams()
  const mode = modeOf(params)
  if (!store) {
    return (
      <>
        <PageHeader title="Ustaw promocje" />
        <FullPageMessage title="Brak sklepu" description="Promocje są ustawiane osobno w każdym sklepie." inline />
      </>
    )
  }
  return <StorePromoSetup key={`${store.id}:${mode}`} store={store} mode={mode} />
}

function StorePromoSetup({ store, mode }: { store: StoreSummary; mode: PromoMode }) {
  const dispatch = useAppDispatch()
  const macro = mode === 'macro'
  const stored = useAppSelector((state) =>
    macro ? state.promoSetupDraft.macroDrafts[store.id] : state.promoSetupDraft.drafts[store.id],
  )
  const levels = useAppSelector((state) => state.promoSetupDraft.levels[store.id]) ?? DEFAULT_LEVELS
  const [fallback] = useState(newPromoDraft)
  const draft = stored ?? fallback
  const [params, setParams] = useSearchParams()
  const selected = params.get('artykul')
  const [listFor, setListFor] = useState<{ tileId: string; query: string } | null>(null)
  const [levelsOpen, setLevelsOpen] = useState(false)
  const [generated, setGenerated] = useState<ProFile[] | null>(null)
  const labelsFolder = useProtoFolder(store.id)
  const addMarkdowns = useAddPriceMarkdowns(store.id)

  const canSeeSales = useCan('SALES')
  const canSeeInventory = useCan('INVENTORY')
  const canSeeStats = canSeeSales || canSeeInventory
  const overview = useSalesReportsOverview(store.id, canSeeStats)
  // Wykres kończy się na ostatnim dniu ze sprzedażą, najpóźniej wczoraj (dzisiejsza nie jest jeszcze wgrana).
  const yesterday = addDays(todayIso(), -1)
  const savedUntil = overview.data?.lastSaleDate ?? null
  const lastSaleDate = savedUntil && savedUntil < yesterday ? savedUntil : yesterday

  useEffect(() => {
    if (!stored) dispatch(setPromoDraft({ storeId: store.id, mode, draft: fallback }))
  }, [dispatch, fallback, mode, store.id, stored])

  // Szkic bywa sprzed kilku dni: ceny i ostatni zakup od nowa, a cena z przeceny idzie za ceną z kartoteki.
  useEffect(() => {
    const current = draftsOf(mode)[store.id]
    const codes = [...new Set(current?.tiles.flatMap((tile) => tile.rows.map((row) => codeKey(row.code))) ?? [])]
    if (codes.length === 0) return
    fetchPromoArticles(store.id, codes)
      .then((articles) => {
        const latest = draftsOf(mode)[store.id]
        if (!latest) return
        const refreshed = (row: PromoRow): PromoRow => {
          // Wiersz dopisany w trakcie (np. z wprowadzania danych) nie był w tym zapytaniu.
          if (!codes.includes(codeKey(row.code))) return row
          const article = articles.get(codeKey(row.code)) ?? null
          if (!article) return { ...row, article: null }
          const price = row.discount === null ? row.price : discountedPrice(article.regularPrice, row.discount)
          return { ...row, article, price }
        }
        const tiles = latest.tiles.map((tile) => ({ ...tile, rows: tile.rows.map(refreshed) }))
        dispatch(setPromoDraft({ storeId: store.id, mode, draft: { ...latest, tiles } }))
      })
      .catch(() => toast.error('Nie udało się odświeżyć cen artykułów.'))
  }, [dispatch, mode, store.id])

  const files = useMemo(() => promoFiles(draft), [draft])
  const incomplete = draft.tiles.filter((tile) => tile.rows.length > 0 && !hasPeriod(tile))
  const articleCount = draft.tiles.reduce((sum, tile) => sum + tile.rows.length, 0)
  const listTile = draft.tiles.find((tile) => tile.id === listFor?.tileId)
  const tableEans = useMemo(
    () => new Set(listTile?.rows.flatMap((row) => (row.article?.ean ? [codeKey(row.article.ean)] : [])) ?? []),
    [listTile],
  )

  const setDraft = (next: PromoSetupDraft) => dispatch(setPromoDraft({ storeId: store.id, mode, draft: next }))
  const restore = setDraft

  const switchMode = (next: PromoMode) =>
    setParams(
      (current) => {
        const params = new URLSearchParams(current)
        if (next === 'pro') params.set('tryb', 'pro')
        else params.delete('tryb')
        params.delete('artykul')
        return params
      },
      { replace: true },
    )

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
  const addCodes = async (tileId: string, entered: string[], preset?: PromoSetupHandoff['promoItems']) => {
    // Krótki kod to ITM8 albo kod z etykiety wagi bez końca, więc szukamy obu.
    const keys = entered.flatMap((code) => [codeKey(code), codeKey(paddedScaleCode(code))])
    const articles = await fetchPromoArticles(store.id, [...new Set(keys)]).catch(() => null)
    if (!articles) {
      toast.error('Nie udało się wyszukać artykułów.')
      return
    }
    const tile = draftsOf(mode)[store.id]?.tiles.find((item) => item.id === tileId)
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
        const given = preset?.[entry]
        rows.push({
          id: crypto.randomUUID(),
          code,
          article,
          price: given?.price ?? null,
          // Cena z kolektora równa przecenie ze stopni sklepu (np. −30%) zostaje tą przeceną.
          discount: given
            ? (levels.find((level) => discountedPrice(article?.regularPrice ?? null, level) === given.price) ?? null)
            : null,
          ...(macro && { dateTo: given?.dateTo ?? tile?.dateTo ?? '' }),
        })
      }
    }
    dispatch(addPromoRows({ storeId: store.id, mode, tileId, rows }))
    if (repeated > 0) toast.info(`Już w tej promocji: ${repeated}`)
    const last = rows.at(-1)
    if (last) select(last)
    return rows.length
  }

  // Artykuły przeniesione z wprowadzania danych (ceny promocji uzupełnia się tutaj) albo z kolektora (z cenami
  // i końcami): do kafelka makra.
  const location = useLocation()
  const navigate = useNavigate()
  const handedOff = useRef(false)
  useEffect(() => {
    const handoff = location.state as PromoSetupHandoff | null
    const codes = handoff?.promoCodes
    if (!macro || handedOff.current || !handoff || !codes?.length) return
    handedOff.current = true
    navigate({ pathname: location.pathname, search: location.search }, { replace: true, state: null })
    const tileId = draftsOf(mode)[store.id]?.tiles[0]?.id
    if (!tileId) return
    const start = handoff.promoStart
    if (start && start > todayIso()) {
      dispatch(updatePromoTile({ storeId: store.id, mode, tileId, changes: { macroStart: start } }))
    }
    const source = handoff.promoSource
    void addCodes(tileId, codes, handoff.promoItems).then((added) => {
      if (added === undefined) return
      if (source?.collectorPromotionId) {
        void markPromotionInserted(store.id, source.collectorPromotionId).catch(() => undefined)
        toast.success(`Dodano ${source.label}: ${added}`, {
          description: added < codes.length ? `Już w promocji: ${codes.length - added}` : undefined,
        })
      } else if (added) {
        toast.success(`Dodano z wprowadzania danych: ${added}`, { description: 'Uzupełnij ceny promocji.' })
      }
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps -- raz po wejściu z wprowadzania danych
  }, [location.state])

  const setPrice = (tile: PromoTile, row: PromoRow, price: number | null) =>
    dispatch(
      updatePromoRow({ storeId: store.id, mode, tileId: tile.id, rowId: row.id, changes: { price, discount: null } }),
    )

  const setDiscount = (tile: PromoTile, row: PromoRow, discount: number | null) => {
    const price = discount === null ? row.price : discountedPrice(row.article?.regularPrice ?? null, discount)
    dispatch(updatePromoRow({ storeId: store.id, mode, tileId: tile.id, rowId: row.id, changes: { price, discount } }))
  }

  /**
   * Tryb makra: makro wprowadzania promocji do schowka (odtwarzacz, Alt+E). Kod operacji jak kod taryfy w .PRO, ale
   * przy każdym kopiowaniu z nowymi cyframi, żeby makro nie trafiło w operację z poprzedniego razu. Nazwa jak nazwa
   * pliku .PRO: dzień początku operacji (bez wybranego dziś) i najpóźniejszego końca. Początek po dziś makro wpisuje,
   * dzisiejszy zostawia z podpowiedzi Mercalys.
   */
  const copyMacro = async (tile: PromoTile) => {
    const items = macroItems(tile)
    const end = items.reduce((latest, item) => (item.dateTo > latest ? item.dateTo : latest), '')
    const operation = randomOperation()
    const code = tileTariff({ ...draft, operation }, tile)
    const today = todayIso()
    const start = macroStartOf(tile, today)
    const name = proFileName(start, end).replace(/\.PRO$/, '')
    try {
      await navigator.clipboard.writeText(
        buildPromoEntryMacro({ code, name, start: start > today ? start : undefined, items }),
      )
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    toast.success(`Skopiowano makro: ${items.length} art., operacja ${code}`, {
      description: 'Przejdź do Mercalys i naciśnij Alt+E.',
    })
    // Każdy artykuł z makra na listę cenówek, ale raz: wiersz pamięta, z czym już na nią trafił. Szkic artykułu (bez
    // ITM8) czeka na raport kartoteki.
    const labelKey = (row: PromoRow) =>
      row.article?.itm8 && row.article.ean && row.price !== null && row.dateTo
        ? promoLabelKey(row.article.itm8, row.price, row.dateTo)
        : null
    const toLabel = tile.rows.filter((row) => {
      const key = labelKey(row)
      return key !== null && row.labeled !== key
    })
    const markdowns = toLabel.map((row) => ({
      itm8: row.article!.itm8!,
      newPrice: row.price!,
      validFrom: start,
      validTo: row.dateTo!,
    }))
    const latest = draftsOf(mode)[store.id] ?? draft
    setDraft({ ...latest, operation })
    if (markdowns.length === 0) return
    const labeled = new Map(toLabel.map((row) => [row.id, labelKey(row) ?? undefined]))
    addMarkdowns.mutate(markdowns, {
      onSuccess: () => {
        const current = draftsOf(mode)[store.id]
        if (current) {
          const tiles = current.tiles.map((item) => ({
            ...item,
            rows: item.rows.map((row) => (labeled.has(row.id) ? { ...row, labeled: labeled.get(row.id) } : row)),
          }))
          setDraft({ ...current, tiles })
        }
        toast.success(`Dodano do cenówek: ${markdowns.length} art.`)
      },
      onError: (error) =>
        toast.error('Nie udało się dodać artykułów do cenówek', {
          description: error instanceof ApiError ? error.message : undefined,
        }),
    })
  }

  /** Po błędzie makra: od wskazanego artykułu, bez zakładania operacji od nowa. */
  const copyResumeMacro = async (tile: PromoTile, from: number) => {
    const items = macroItems(tile)
    const code = tileTariff(draft, tile)
    try {
      await navigator.clipboard.writeText(buildPromoResumeMacro({ code, items, from }))
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    toast.success(`Skopiowano makro od artykułu ${from + 1} z ${items.length}`, {
      description: 'Przejdź do Mercalys (ekran wpisywania artykułów promocji) i naciśnij Alt+E.',
    })
  }

  const setRowEnd = (tile: PromoTile, row: PromoRow, dateTo: string) =>
    dispatch(updatePromoRow({ storeId: store.id, mode, tileId: tile.id, rowId: row.id, changes: { dateTo } }))

  /** Tryb makra: koniec z kafelka dla wszystkich artykułów. */
  const setAllEnds = (tile: PromoTile) => {
    const rows = tile.rows.map((row) => ({ ...row, dateTo: tile.dateTo }))
    setDraft({ ...draft, tiles: draft.tiles.map((item) => (item.id === tile.id ? { ...item, rows } : item)) })
    toast.success(`Koniec promocji ${formatDocumentDate(tile.dateTo)} dla ${rows.length} art.`, {
      action: { label: 'Przywróć', onClick: () => restore(draft) },
    })
  }

  /** Ten sam stopień przeceny dla wszystkich artykułów kafelka; ręcznie wpisane ceny też są zastępowane. */
  const setTileDiscount = (tile: PromoTile, discount: number) => {
    const rows = tile.rows.map((row) =>
      row.article ? { ...row, discount, price: discountedPrice(row.article.regularPrice, discount) } : row,
    )
    const tiles = draft.tiles.map((item) => (item.id === tile.id ? { ...item, rows } : item))
    setDraft({ ...draft, tiles })
    const count = rows.filter((row) => row.article).length
    toast.success(
      `${macro ? 'Promocje' : `Promocja ${tile.number}`}: przecena -${String(discount).replace('.', ',')}% dla ${count} art.`,
      {
        action: { label: 'Przywróć', onClick: () => restore(draft) },
      },
    )
  }

  const removeRow = (tile: PromoTile, row: PromoRow) => {
    const tiles = draft.tiles.map((item) =>
      item.id === tile.id ? { ...item, rows: item.rows.filter((candidate) => candidate.id !== row.id) } : item,
    )
    setDraft({ ...draft, tiles })
    closeArticle([row])
    toast.success(`Usunięto: ${row.article?.name ?? row.code}`, {
      action: { label: 'Przywróć', onClick: () => restore(draft) },
    })
  }

  const removeTile = (tile: PromoTile) => {
    const tiles = draft.tiles.filter((item) => item.id !== tile.id)
    setDraft({ ...draft, tiles })
    closeArticle(tile.rows)
    toast.success(`Usunięto promocję ${tile.number}`, {
      action: { label: 'Przywróć', onClick: () => restore(draft) },
    })
  }

  const clear = () => {
    setDraft(newPromoDraft())
    closeArticle(draft.tiles.flatMap((tile) => tile.rows))
    toast.success('Wyczyszczono promocje', { action: { label: 'Przywróć', onClick: () => restore(draft) } })
  }

  return (
    <>
      <span data-wide-page hidden />
      <PageHeader
        title="Ustaw promocje"
        description={
          macro
            ? `${storeLabel(store)}: artykuły z cenami i własnym końcem promocji, ustawiane automatycznie makrem.`
            : `${storeLabel(store)}: kilka promocji naraz, każda z własnymi artykułami i okresem, w cenach promocji dla programu sklepu.`
        }
        actions={
          <>
            <PriceLabelsButton storeId={store.id} />
            <SegmentedField
              label="Tryb ustawiania promocji"
              options={MODE_OPTIONS}
              value={mode}
              onChange={switchMode}
            />
          </>
        }
      />

      <div className="sticky top-14 z-15 mb-3 flex flex-wrap items-center gap-2 rounded-lg border bg-muted p-1.5 shadow-xs">
        <span className="px-2 text-sm text-muted-foreground tabular-nums">
          {macro ? `Artykuły: ${articleCount}` : `Promocje: ${draft.tiles.length} · artykuły: ${articleCount}`}
        </span>
        <Button variant="ghost" className="ml-auto" disabled={articleCount === 0} onClick={clear}>
          <EraserIcon />
          Wyczyść
        </Button>
        {!macro && (
          <Button
            disabled={files.size === 0}
            title={files.size === 0 ? 'Dodaj artykuły i ustaw okres promocji' : undefined}
            onClick={() => setGenerated([...files.values()])}
          >
            <FileOutputIcon />
            Wygeneruj wszystkie pliki promo
          </Button>
        )}
      </div>

      <div className="grid items-start gap-4 lg:grid-cols-2">
        <div className="grid min-w-0 gap-4">
          {/* Tryb makra ma zawsze jeden kafelek. */}
          {(macro ? draft.tiles.slice(0, 1) : draft.tiles).map((tile) => {
            const file = files.get(tile.id)
            const shared = {
              tile,
              levels,
              selected,
              onSelect: select,
              onDates: (changes: { dateFrom?: string; dateTo?: string; macroStart?: string }) =>
                dispatch(updatePromoTile({ storeId: store.id, mode, tileId: tile.id, changes })),
              onCodes: (codes: string[]) => void addCodes(tile.id, codes),
              onSearch: (query: string) => setListFor({ tileId: tile.id, query }),
              onList: () => setListFor({ tileId: tile.id, query: '' }),
              onPrice: (row: PromoRow, price: number | null) => setPrice(tile, row, price),
              onDiscount: (row: PromoRow, discount: number | null) => setDiscount(tile, row, discount),
              onTileDiscount: (discount: number) => setTileDiscount(tile, discount),
              onRemoveRow: (row: PromoRow) => removeRow(tile, row),
            }
            return macro ? (
              <PromoTileCard
                key={tile.id}
                variant="macro"
                {...shared}
                onRowEnd={(row, dateTo) => setRowEnd(tile, row, dateTo)}
                onAllEnds={() => setAllEnds(tile)}
                tile={{ ...tile, dateFrom: macroStartOf(tile, todayIso()) }}
                createBlocked={macroBlocked({ ...tile, dateFrom: macroStartOf(tile, todayIso()) })}
                onCreate={() => void copyMacro(tile)}
                onResume={(from) => void copyResumeMacro(tile, from)}
              />
            ) : (
              <PromoTileCard
                key={tile.id}
                variant="pro"
                {...shared}
                tariff={tileTariff(draft, tile)}
                canGenerate={file !== undefined}
                onRemove={() => removeTile(tile)}
                onGenerate={() => file && setGenerated([file])}
              />
            )
          })}

          <div className="flex flex-wrap gap-2">
            {!macro && (
              <Button variant="outline" size="lg" onClick={() => dispatch(addPromoTile({ storeId: store.id }))}>
                <PlusIcon />
                Dodaj promocję
              </Button>
            )}
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
          onAdd={async (additions) => {
            await addCodes(
              listFor.tileId,
              additions.map(({ item }) => item.ean),
            )
          }}
          initialQuery={listFor.query}
          title={macro ? 'Lista towarów · promocje' : `Lista towarów · promocja ${listTile?.number ?? ''}`}
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
