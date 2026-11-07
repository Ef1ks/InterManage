import { Link, Route, Routes } from 'react-router'
import { ChevronRightIcon } from 'lucide-react'

import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
import { PageHeader } from '@/components/PageHeader'
import { Card } from '@/components/ui/card'
import { useCan } from '@/features/auth/api'
import { formatMoney } from '@/features/documents/format'
import { useStocktakes } from '@/features/stocktake/api'
import { stocktakePath } from '@/features/stocktake/paths'
import { StocktakeDetail } from '@/features/stocktake/StocktakeDetail'
import { StocktakeUpload } from '@/features/stocktake/StocktakeUpload'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { formatLongDate } from '@/lib/dates'
import type { StoreSummary } from '@/lib/types'

const numberFormat = new Intl.NumberFormat('pl-PL')

/** Weryfikacja inwentury: co przeliczyć przed zamknięciem, na podstawie raportu porównania stanów. */
export function StocktakePage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Weryfikacja inwentury" />
        <FullPageMessage title="Brak sklepu" description="Inwentury są osobne dla każdego sklepu." inline />
      </>
    )
  }
  return (
    <Routes key={store.id}>
      <Route index element={<StocktakeList store={store} />} />
      <Route path=":id" element={<StocktakeDetail store={store} />} />
    </Routes>
  )
}

function StocktakeList({ store }: { store: StoreSummary }) {
  const stocktakes = useStocktakes(store.id)
  const canEdit = useCan('INVENTORY', 'EDIT')

  return (
    <div className="grid gap-5">
      <PageHeader title="Weryfikacja inwentury" description={storeLabel(store)} />
      <HowTo
        id="stocktake"
        title="Jak to działa?"
        steps={[
          'Po policzeniu działu albo sklepu wydrukuj do PDF „Porównanie stanów rzeczywistych i teoretycznych”.',
          'Wgraj raport. Kolejne wydruki tej samej inwentury dopisują się do niej.',
          'Przelicz artykuły z listy, zaczynając od góry, i popraw ilości w systemie sklepu.',
          'Wgraj nowy wydruk: zobaczysz, ile różnic zostało. Każdy kolejny dzień sprzedaży dokłada dowody.',
        ]}
      />
      {canEdit && <StocktakeUpload storeId={store.id} title="Wgraj raport porównania stanów" />}
      {stocktakes.isPending ? (
        <p className="text-sm text-muted-foreground">Wczytywanie…</p>
      ) : stocktakes.isError ? (
        <p className="text-sm text-destructive">Nie udało się wczytać inwentur. Spróbuj ponownie.</p>
      ) : stocktakes.data.length === 0 ? (
        <p className="text-sm text-muted-foreground">Nie ma jeszcze żadnej inwentury.</p>
      ) : (
        <div className="grid gap-2">
          {stocktakes.data.map((stocktake) => (
            <Link key={stocktake.id} to={`${stocktakePath}/${stocktake.id}`}>
              <Card className="flex-row items-center gap-4 p-4 transition-colors duration-150 hover:bg-muted/50">
                <div className="min-w-0 flex-1">
                  <p className="font-medium">{stocktake.name}</p>
                  <p className="text-sm text-muted-foreground">
                    Nr {stocktake.number} · {formatLongDate(stocktake.stocktakeDate)}
                  </p>
                </div>
                <div className="text-right text-sm tabular-nums">
                  <p>{numberFormat.format(stocktake.lineCount)} różnic</p>
                  <p className="text-muted-foreground">{formatMoney(stocktake.netValue)} zł netto</p>
                </div>
                <ChevronRightIcon className="size-4 text-muted-foreground" aria-hidden />
              </Card>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
