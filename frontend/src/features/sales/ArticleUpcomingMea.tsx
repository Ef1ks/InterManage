import { Link } from 'react-router'

import { useCan } from '@/features/auth/api'
import { meaDocumentPath } from '@/features/mea/paths'
import { useArticleMea } from '@/features/sales/api'
import { parseIsoDate } from '@/lib/dates'
import type { ArticleMea, ArticleMeaStatus } from '@/lib/types'
import { cn } from '@/lib/utils'

const day = new Intl.DateTimeFormat('pl-PL', { day: '2-digit', month: '2-digit' })
const weekday = new Intl.DateTimeFormat('pl-PL', { weekday: 'short' })
const moment = new Intl.DateTimeFormat('pl-PL', {
  day: '2-digit',
  month: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
})

const STATUS_STYLES: Record<ArticleMeaStatus, string> = {
  ORDERED: 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50',
  QUEUED: 'bg-sky-200 text-sky-950 dark:bg-sky-900 dark:text-sky-50',
  SENT: 'bg-sky-200 text-sky-950 dark:bg-sky-900 dark:text-sky-50',
  OPEN: 'bg-amber-300 text-amber-950 dark:bg-amber-800 dark:text-amber-50',
  NOT_ORDERED: 'bg-muted text-foreground',
  CLOSED: 'bg-red-200 text-red-950 dark:bg-red-900 dark:text-red-50',
  UNKNOWN: 'bg-muted text-muted-foreground',
}

function formatDay(iso: string | null) {
  return iso ? day.format(parseIsoDate(iso)) : null
}

function statusLabel(mea: ArticleMea): { label: string; detail: string | null } {
  const ordered = mea.orderedAt ? moment.format(new Date(mea.orderedAt)) : null
  const by = mea.orderedBy ? ` · ${mea.orderedBy}` : ''
  const athos = mea.athosSentAt
    ? `wysłano z ATHOS ${moment.format(new Date(mea.athosSentAt))}` +
      (mea.athosColis != null ? `, całe MEA ${mea.athosColis} kart.` : '')
    : null
  switch (mea.status) {
    case 'ORDERED':
      return { label: 'Zamówione', detail: [ordered && `zapisano ${ordered}${by}`, athos].filter(Boolean).join(' · ') }
    case 'NOT_ORDERED':
      return {
        label: 'Nie zamówiono tego artykułu',
        detail: [ordered && `zamówienie MEA z ${ordered}${by}`, athos].filter(Boolean).join(' · '),
      }
    case 'QUEUED':
      return { label: 'Czeka w kolejce do ATHOS', detail: ordered && `dodano ${ordered}${by}` }
    case 'SENT':
      return { label: 'Wysłane z ATHOS', detail: `${athos} · ilości artykułu nie znamy (zamówienie spoza aplikacji)` }
    case 'OPEN':
      return {
        label: mea.reservationTo ? `Do zamówienia do ${formatDay(mea.reservationTo)}` : 'Do zamówienia',
        detail: null,
      }
    case 'CLOSED':
      return { label: 'Rezerwacja zamknięta, nie zamówiono', detail: null }
    case 'UNKNOWN':
      return {
        label: 'Rezerwacja zamknięta',
        detail: 'nie wiadomo, czy zamówiono: sklep nie wczytał dziennika ATHOS',
      }
  }
}

/**
 * Gazetki MEA artykułu, które trwają albo dopiero będą: okres promocji, stan zamówienia i rozrzuty z datami dostaw.
 * Ilości kartonów znamy tylko z zamówień zapisanych w aplikacji. Bez nadchodzących gazetek sekcji nie ma.
 */
export function ArticleUpcomingMea({ storeId, itm8 }: { storeId: string; itm8: string }) {
  const mea = useArticleMea(storeId, itm8)
  const canOpen = useCan('DATA_ENTRY', 'VIEW')
  const list = mea.data
  if (!list || list.length === 0) return null

  return (
    <section className="grid min-w-0 gap-2" aria-labelledby="article-upcoming-mea">
      <h3 id="article-upcoming-mea" className="text-sm font-semibold">
        Nadchodzące gazetki MEA
      </h3>
      <ul className="grid gap-2">
        {list.map((item) => {
          const { label, detail } = statusLabel(item)
          const from = formatDay(item.promoFrom)
          const to = formatDay(item.promoTo)
          const pill = 'rounded bg-amber-200 px-1.5 font-semibold text-amber-950 dark:bg-amber-900 dark:text-amber-50'
          return (
            <li key={item.number} className="grid gap-2 rounded-lg border px-3 py-2.5 text-sm">
              <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
                {canOpen ? (
                  <Link
                    to={meaDocumentPath(item.number)}
                    className={cn(pill, 'tabular-nums underline-offset-4 hover:underline')}
                  >
                    MEA {item.number}
                  </Link>
                ) : (
                  <span className={cn(pill, 'tabular-nums')}>MEA {item.number}</span>
                )}
                {from && to && (
                  <span className="font-medium tabular-nums">
                    promocja {from}–{to}
                  </span>
                )}
                {item.title && <span className="min-w-0 truncate text-muted-foreground">{item.title}</span>}
                <span className={cn('ml-auto rounded px-2 py-0.5 text-xs font-semibold', STATUS_STYLES[item.status])}>
                  {label}
                </span>
              </div>
              {item.dispatches.length > 0 && (
                <div className="flex flex-wrap gap-1.5">
                  {item.dispatches.map((dispatch) => {
                    const date = parseIsoDate(dispatch.date)
                    const pieces =
                      dispatch.colis != null && dispatch.colis > 0 && item.unitsPerPack
                        ? dispatch.colis * item.unitsPerPack
                        : null
                    return (
                      <span
                        key={dispatch.index}
                        className={cn(
                          'rounded-md border px-2 py-1 text-xs tabular-nums',
                          dispatch.colis != null && dispatch.colis > 0 && 'border-emerald-400 dark:border-emerald-700',
                        )}
                        title={pieces != null ? `${pieces} szt. (${item.unitsPerPack} szt. w kartonie)` : undefined}
                      >
                        <span className="font-semibold">R{dispatch.index}</span> {weekday.format(date)}{' '}
                        {day.format(date)}
                        {dispatch.colis != null && (
                          <span
                            className={cn('ml-1.5', dispatch.colis > 0 ? 'font-semibold' : 'text-muted-foreground')}
                          >
                            {dispatch.colis} kart.{pieces != null && ` · ${pieces} szt.`}
                          </span>
                        )}
                      </span>
                    )
                  })}
                </div>
              )}
              {detail && <p className="text-xs text-muted-foreground">{detail}</p>}
            </li>
          )
        })}
      </ul>
    </section>
  )
}
