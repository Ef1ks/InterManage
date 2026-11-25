import { BadgePercentIcon, CheckIcon, MonitorPlayIcon, ScanBarcodeIcon, TagIcon, Trash2Icon } from 'lucide-react'
import { useSearchParams, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo, HowToButton } from '@/components/HowTo'
import { LoadingPanel } from '@/components/LoadingPanel'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useCan } from '@/features/auth/api'
import { formatDocumentDate } from '@/features/documents/format'
import { type PromoSetupHandoff, promoSetupPath } from '@/features/promo-setup/paths'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import type { CollectorPromotion, CollectorPromotionDetails } from '@/lib/types'
import { cn } from '@/lib/utils'

import {
  macroActive,
  useCollectorPromotion,
  useCollectorPromotions,
  useRemoveCollectorPromotion,
  useRestoreCollectorPromotion,
  useRunPromotionMacro,
} from './promotions-api'

const HINT = 'collector-promotions'

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })
const money = new Intl.NumberFormat('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)
const amount = (value: number | null) => (value == null ? '–' : money.format(value))
const articles = (count: number) =>
  `${count} ${count === 1 ? 'artykuł' : count % 10 >= 2 && count % 10 <= 4 && (count % 100 < 12 || count % 100 > 14) ? 'artykuły' : 'artykułów'}`

const MACRO_STATES: Record<string, { label: string; className: string }> = {
  QUEUED: {
    label: 'czeka na komputer sklepu',
    className: 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100',
  },
  OFFERED: {
    label: 'komputer sklepu zaraz zacznie',
    className: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  },
  RUNNING: {
    label: 'ustawia się w Mercalys',
    className: 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100',
  },
  DONE: {
    label: 'ustawione w Mercalys',
    className: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100',
  },
  FAILED: { label: 'makro przerwane', className: 'bg-rose-200 text-rose-950 dark:bg-rose-900/70 dark:text-rose-100' },
  NO_COMPUTER: {
    label: 'brak wolnego komputera',
    className: 'bg-rose-200 text-rose-950 dark:bg-rose-900/70 dark:text-rose-100',
  },
  CANCELLED: { label: 'makro anulowane', className: 'bg-muted text-foreground' },
}

/**
 * Promocje z kolektorów: artykuły z ceną promocji i końcem ustawione na kolektorze („3 Ustaw promocje”). Wstawia się je
 * do Ustaw promocje albo od razu ustawia makrem na komputerze sklepu; po udanym makrze cenówki idą do kolejki plików.
 */
export function CollectorPromotionsPage() {
  const store = useCurrentStore()
  return (
    <div data-wide-page>
      <PageHeader
        title="Promocje z kolektorów"
        description={store ? storeLabel(store) : undefined}
        actions={<HowToButton id={HINT} />}
      />
      {store ? (
        <Promotions key={store.id} storeId={store.id} />
      ) : (
        <FullPageMessage title="Brak sklepu" description="Promocje z kolektorów są w konkretnym sklepie." inline />
      )}
    </div>
  )
}

function Promotions({ storeId }: { storeId: string }) {
  const promotions = useCollectorPromotions(storeId)
  const [params, setParams] = useSearchParams()

  if (promotions.isPending) return <LoadingPanel className="h-72 rounded-xl" />
  if (promotions.isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać promocji z kolektorów"
        action={{ label: 'Spróbuj ponownie', onClick: () => promotions.refetch() }}
        inline
      />
    )
  }
  const picked = params.get('promocja')
  const waiting = promotions.data.find((item) => !item.insertedAt && !item.macro)
  const selected =
    promotions.data.find((item) => item.id === picked)?.id ?? waiting?.id ?? promotions.data[0]?.id ?? null
  const select = (id: string | null) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        if (id) next.set('promocja', id)
        else next.delete('promocja')
        return next
      },
      { replace: true },
    )

  return (
    <div className="grid gap-4">
      <HowTo
        id={HINT}
        title="Promocje z kolektora"
        reopenInHeader
        steps={[
          'Na kolektorze wybierz „3 Ustaw promocje”, skanuj artykuły, wpisz cenę albo przecenę i koniec promocji.',
          'Włóż kolektor na podstawkę i potwierdź wysyłkę. Możesz od razu zlecić ustawienie w Mercalys.',
          'Tutaj wstaw promocje do Ustaw promocje albo zleć je komputerowi sklepu. Cenówki powstaną po makrze.',
        ]}
      />
      {promotions.data.length === 0 ? (
        <FullPageMessage
          title="Brak promocji z kolektorów"
          description="Promocje pojawią się tu po wysłaniu ich z kolektora na podstawce."
          inline
        />
      ) : (
        <div className="grid items-start gap-4 lg:grid-cols-[15rem_minmax(0,1fr)]">
          <Picker storeId={storeId} promotions={promotions.data} selected={selected} onSelect={select} />
          {selected && <Details storeId={storeId} id={selected} />}
        </div>
      )}
    </div>
  )
}

function Status({ promotion }: { promotion: CollectorPromotion }) {
  const macro = promotion.macro ? MACRO_STATES[promotion.macro.status] : null
  return (
    <span className="flex flex-wrap gap-1">
      {macro && <Badge className={macro.className}>{macro.label}</Badge>}
      {promotion.macroError && !promotion.macro && (
        <Badge className="bg-rose-200 text-rose-950 dark:bg-rose-900/70 dark:text-rose-100">makro nie ruszyło</Badge>
      )}
      {promotion.labelsQueued && (
        <Badge className="bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100">
          <TagIcon aria-hidden />
          cenówki
        </Badge>
      )}
      {promotion.insertedAt && (
        <Badge className="bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100">
          <CheckIcon aria-hidden />
          wstawione
        </Badge>
      )}
      {!macro && !promotion.macroError && !promotion.insertedAt && (
        <Badge className="bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100">do ustawienia</Badge>
      )}
    </span>
  )
}

function Picker({
  storeId,
  promotions,
  selected,
  onSelect,
}: {
  storeId: string
  promotions: CollectorPromotion[]
  selected: string | null
  onSelect: (id: string | null) => void
}) {
  const remove = useRemoveCollectorPromotion(storeId)
  const restore = useRestoreCollectorPromotion(storeId)

  const removePromotion = (promotion: CollectorPromotion) =>
    remove.mutate(promotion.id, {
      onSuccess: () => {
        if (selected === promotion.id) onSelect(null)
        toast.success(`Usunięto promocje z ${promotion.collectorName}`, {
          action: {
            label: 'Przywróć',
            onClick: () =>
              restore.mutate(promotion.id, {
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się przywrócić promocji.')),
              }),
          },
        })
      },
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć promocji.')),
    })

  return (
    <Card className="gap-0 overflow-hidden p-0 lg:sticky lg:top-16 lg:max-h-[calc(100dvh-5rem)] lg:overflow-y-auto">
      <ul className="divide-y">
        {promotions.map((promotion) => (
          <li
            key={promotion.id}
            className={cn('flex items-start gap-2 px-3 py-2.5', promotion.id === selected && 'bg-muted')}
          >
            <button
              type="button"
              onClick={() => onSelect(promotion.id)}
              className="grid min-w-0 flex-1 gap-1 rounded-md text-left text-sm outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              <span className="flex items-center gap-1.5 font-medium">
                <ScanBarcodeIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 truncate">{promotion.collectorName}</span>
              </span>
              <span className="text-xs text-muted-foreground tabular-nums">
                {dateTime.format(new Date(promotion.receivedAt))}, {articles(promotion.lineCount)}
              </span>
              <Status promotion={promotion} />
            </button>
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`Usuń promocje z ${promotion.collectorName}`}
              title="Usuń promocje"
              onClick={() => removePromotion(promotion)}
              disabled={remove.isPending}
            >
              <Trash2Icon aria-hidden />
            </Button>
          </li>
        ))}
      </ul>
    </Card>
  )
}

function Details({ storeId, id }: { storeId: string; id: string }) {
  const details = useCollectorPromotion(storeId, id)
  if (details.isPending) return <LoadingPanel className="h-96 rounded-xl" />
  if (details.isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać promocji"
        description={errorMessage(details.error, '')}
        action={{ label: 'Spróbuj ponownie', onClick: () => details.refetch() }}
        inline
      />
    )
  }
  return (
    <div className="grid min-w-0 gap-4">
      <Actions storeId={storeId} details={details.data} />
      <Rows details={details.data} />
    </div>
  )
}

function Actions({ storeId, details }: { storeId: string; details: CollectorPromotionDetails }) {
  const { promotion, rows } = details
  const navigate = useNavigate()
  const canRun = useCan('DATA_ENTRY', 'EDIT')
  const run = useRunPromotionMacro(storeId)
  const active = macroActive(promotion)

  const insert = () => {
    const known = rows.filter((row) => row.known)
    const handoff: PromoSetupHandoff = {
      promoCodes: known.map((row) => row.ean),
      promoItems: Object.fromEntries(known.map((row) => [row.ean, { price: row.price, dateTo: row.dateTo }])),
      promoStart: promotion.startDate,
      promoSource: { label: `z kolektora ${promotion.collectorName}`, collectorPromotionId: promotion.id },
    }
    void navigate(promoSetupPath, { state: handoff })
  }

  const startMacro = () =>
    run.mutate(promotion.id, {
      onSuccess: () =>
        toast.success('Zlecono ustawienie promocji', {
          description: 'Komputer sklepu ustawi je w Mercalys, a cenówki trafią do kolejki plików.',
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się zlecić makra.')),
    })

  const start = promotion.startDate ? formatDocumentDate(promotion.startDate) : 'dziś'
  return (
    <Card className="grid gap-3 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="grid gap-1">
          <h2 className="font-medium">Promocje z kolektora: {promotion.collectorName}</h2>
          <p className="text-sm text-muted-foreground tabular-nums">
            Od {start} do {formatDocumentDate(promotion.endDate)}, {articles(promotion.lineCount)}
            {promotion.insertedAt &&
              `. Wstawione ${dateTime.format(new Date(promotion.insertedAt))}${promotion.insertedBy ? `, ${promotion.insertedBy}` : ''}`}
          </p>
        </div>
        <Status promotion={promotion} />
      </div>
      {(promotion.macro?.message || promotion.macroError) && (
        <p
          className={cn(
            'rounded-lg p-3 text-sm',
            promotion.macro?.status === 'DONE'
              ? 'bg-muted'
              : 'bg-amber-200 text-amber-950 dark:bg-amber-900/60 dark:text-amber-100',
          )}
        >
          {promotion.macro?.message ?? promotion.macroError}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button onClick={insert} disabled={!rows.some((row) => row.known)}>
          <BadgePercentIcon aria-hidden />
          Wstaw do Ustaw promocje
        </Button>
        {canRun && (
          <Button variant="outline" onClick={startMacro} loading={run.isPending} disabled={active}>
            <MonitorPlayIcon aria-hidden />
            {promotion.macro ? 'Ustaw na komputerze sklepu jeszcze raz' : 'Ustaw na komputerze sklepu'}
          </Button>
        )}
      </div>
    </Card>
  )
}

function Rows({ details }: { details: CollectorPromotionDetails }) {
  return (
    <Card className="gap-0 overflow-hidden p-0">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>ITM8</TableHead>
            <TableHead>EAN</TableHead>
            <TableHead>Nazwa</TableHead>
            <TableHead className="text-right">Cena zwykła</TableHead>
            <TableHead className="text-right">Cena promocji</TableHead>
            <TableHead className="text-right">Przecena</TableHead>
            <TableHead className="text-right">Ostatni zakup</TableHead>
            <TableHead className="text-right">Marża %</TableHead>
            <TableHead className="text-right">Koniec</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {details.rows.map((row) => (
            <TableRow key={row.itm8}>
              <TableCell className="tabular-nums">{row.itm8.replace(/^0+/, '')}</TableCell>
              <TableCell className="tabular-nums">{row.ean}</TableCell>
              <TableCell className="min-w-56 whitespace-normal">
                <span className="flex items-center gap-1.5">
                  <span className="font-medium">{row.name}</span>
                  {row.known ? (
                    <ArticleStatsButton article={{ itm8: row.itm8, name: row.name }} />
                  ) : (
                    <Badge className="bg-rose-200 text-rose-950 dark:bg-rose-900/70 dark:text-rose-100">
                      spoza kartoteki
                    </Badge>
                  )}
                </span>
                {row.brand && <span className="block text-xs text-muted-foreground">{row.brand}</span>}
              </TableCell>
              <TableCell className="text-right tabular-nums">{amount(row.regularPrice)}</TableCell>
              <TableCell className="text-right text-base font-semibold tabular-nums">{amount(row.price)}</TableCell>
              <TableCell className="text-right tabular-nums">
                {row.discountPercent == null
                  ? '–'
                  : `${row.discountPercent > 0 ? '−' : '+'}${Math.abs(row.discountPercent)}%`}
              </TableCell>
              <TableCell className="text-right tabular-nums">{amount(row.lastPurchasePrice)}</TableCell>
              <TableCell
                className={cn(
                  'text-right tabular-nums',
                  row.marginPercent != null && row.marginPercent < 0 && 'font-medium text-red-700 dark:text-red-300',
                )}
              >
                {row.marginPercent == null ? '–' : `${money.format(row.marginPercent)}%`}
              </TableCell>
              <TableCell className="text-right tabular-nums">{formatDocumentDate(row.dateTo)}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Card>
  )
}
