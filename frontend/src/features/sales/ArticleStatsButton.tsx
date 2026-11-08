import { useState, type MouseEvent } from 'react'

import { useCan } from '@/features/auth/api'
import { type SalesChartTarget, useSalesReportsOverview } from '@/features/sales/api'
import { ChartButton } from '@/features/sales/report-parts'
import { SalesChartDialog } from '@/features/sales/SalesChartDialog'
import { useCurrentStore } from '@/features/stores/current-store'
import { addDays, todayIso } from '@/lib/dates'

const NO_FILTER: string[] = []
const ITM8_LENGTH = 13

/** Lokalny ITM8 nadaje każdy sklep sam, więc ten sam numer w innym sklepie to inny towar. */
const LOCAL_ITM8 = /^0*99/

/** Statystyki artykułu pokazuje każdy z dostępem do raportów albo do Magazynu (ten sam warunek co w API). */
export function useCanSeeArticleStats() {
  const sales = useCan('SALES', 'VIEW')
  const inventory = useCan('INVENTORY', 'VIEW')
  return sales || inventory
}

export type ArticleStatsTarget = {
  itm8: string
  name?: string | null
  ean?: string | null
  brand?: string | null
}

/**
 * Ikonka przy artykule w dowolnym wierszu aplikacji: otwiera jego statystyki (dane z kartoteki, wykres sprzedaży,
 * dostawy, protokoły) w bieżącym sklepie. Okno żyje przy przycisku, a nie raz na aplikację: tylko tak zagnieżdża się
 * w oknie, z którego je otwarto, zamiast je zamykać. Bez uprawnień przycisku nie ma.
 *
 * {@code foreign}: artykuł pochodzi z innego sklepu (ruch międzymagazynowy, formularz dostawcy), więc lokalny ITM8
 * pokazałby u nas inny towar i przycisku wtedy nie ma. {@code skipTab}: wiersz z polem ilości, gdzie Tab ma przechodzić
 * od pola do pola.
 */
export function ArticleStatsButton({
  article,
  foreign = false,
  skipTab = false,
  className,
}: {
  article: ArticleStatsTarget
  foreign?: boolean
  skipTab?: boolean
  className?: string
}) {
  const canSee = useCanSeeArticleStats()
  const store = useCurrentStore()
  // Okno montuje się przy pierwszym kliknięciu, a później zostaje na czas animacji zamykania.
  const [mounted, setMounted] = useState(false)
  const [target, setTarget] = useState<SalesChartTarget | null>(null)

  if (!canSee || !store || (foreign && LOCAL_ITM8.test(article.itm8))) return null

  const open = () => {
    setMounted(true)
    setTarget({
      kind: 'article',
      // Plik z bazy i tymczasowy bon podają krótki kod, a kartoteka, sprzedaż i dokumenty zawsze 13 cyfr.
      itm8: article.itm8.padStart(ITM8_LENGTH, '0'),
      name: article.name ?? null,
      ean: article.ean ?? null,
      brand: article.brand ?? null,
    })
  }

  return (
    <>
      <ChartButton
        label="Statystyki artykułu"
        onClick={open}
        tabIndex={skipTab ? -1 : undefined}
        className={className}
      />
      {mounted && <ArticleStatsDialog storeId={store.id} target={target} onClose={() => setTarget(null)} />}
    </>
  )
}

function ArticleStatsDialog({
  storeId,
  target,
  onClose,
}: {
  storeId: string
  target: SalesChartTarget | null
  onClose: () => void
}) {
  const overview = useSalesReportsOverview(storeId, target != null)
  return (
    // Portal przekazuje zdarzenia w górę drzewa Reacta, a kliknięcie w oknie nie może rozwijać ani zaznaczać wiersza.
    <span className="contents" onClick={stopPropagation}>
      <SalesChartDialog
        storeId={storeId}
        kind="sales"
        // Dopiero z datą ostatniej sprzedaży, żeby wykres nie pobierał się dwa razy.
        target={overview.isPending ? null : target}
        filter={NO_FILTER}
        basis="gross"
        lastSaleDate={overview.data?.lastSaleDate ?? addDays(todayIso(), -1)}
        onClose={onClose}
      />
    </span>
  )
}

const stopPropagation = (event: MouseEvent) => event.stopPropagation()
