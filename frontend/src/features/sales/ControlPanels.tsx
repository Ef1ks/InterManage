import { PencilIcon } from 'lucide-react'
import { Link } from 'react-router'

import { Button } from '@/components/ui/button'
import { documentPaths, formatDocumentDate, formatMoney } from '@/features/documents/format'
import { type EditorState, hexOf } from '@/features/sales/LossControlGroups'
import type { LossControlGroup, LossControlSettlements } from '@/lib/types'
import { cn } from '@/lib/utils'

const monthFormat = new Intl.DateTimeFormat('pl-PL', { month: 'long', year: 'numeric' })
const monthLabel = (month: string) => monthFormat.format(new Date(`${month}-01T12:00:00`))

/** Straty z protokołu: artykuły dostawcy do kontroli w raporcie za okres. */
export type ControlLosses = { purchaseValue: number; saleValueGross: number; articleCount: number }

const NO_LOSSES: ControlLosses = { purchaseValue: 0, saleValueGross: 0, articleCount: 0 }

/** Okres rozliczeń jako miesiące, np. „styczeń 2026 – czerwiec 2026”. */
export function settlementPeriod(settlements: LossControlSettlements) {
  return settlements.fromMonth === settlements.toMonth
    ? monthLabel(settlements.fromMonth)
    : `${monthLabel(settlements.fromMonth)} – ${monthLabel(settlements.toMonth)}`
}

/**
 * Panel każdego dostawcy do kontroli nad tabelą: straty jego artykułów w okresie (zakup netto i sprzedaż brutto),
 * gratisy za pokrycie strat od podpiętego dostawcy z listy dostawców (po miesiącu rozliczenia: wybranym przy
 * gratisie albo z daty dokumentu) i ile zostało do rozliczenia w cenach sprzedaży brutto. Klik w nazwę filtruje
 * raport do tego dostawcy.
 */
export function ControlPanels({
  groups,
  losses,
  settlements,
  active,
  onActive,
  canEdit,
  onEdit,
}: {
  groups: LossControlGroup[]
  losses: ReadonlyMap<string, ControlLosses>
  settlements: LossControlSettlements | undefined
  active: string[]
  onActive: (ids: string[]) => void
  canEdit: boolean
  onEdit: (state: EditorState) => void
}) {
  if (groups.length === 0) return null
  const settled = new Map(settlements?.groups.map((group) => [group.groupId, group]))
  const period = settlements ? settlementPeriod(settlements) : null
  return (
    <div className="mb-4 grid gap-3 md:grid-cols-2 2xl:grid-cols-3">
      {groups.map((group) => {
        const on = active.includes(group.id)
        const loss = losses.get(group.id) ?? NO_LOSSES
        const settlement = settled.get(group.id)
        const rest = settlement ? loss.saleValueGross - settlement.saleValueGross : null
        return (
          <section
            key={group.id}
            aria-label={`Dostawca do kontroli ${group.name}`}
            className={cn(
              'grid content-start gap-3 rounded-xl border bg-card p-4 text-sm transition-shadow duration-150',
              on && 'ring-2 ring-foreground/60',
            )}
            style={{ borderLeft: `0.375rem solid ${hexOf(group.color)}` }}
          >
            <div className="flex items-start gap-2">
              <button
                type="button"
                aria-pressed={on}
                onClick={() => onActive(on ? active.filter((id) => id !== group.id) : [...active, group.id])}
                className="min-w-0 flex-1 rounded-md text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/40"
                title={on ? 'Pokaż wszystkich' : 'Pokaż w raporcie tylko tego dostawcę'}
              >
                <span className="block truncate text-base font-semibold">{group.name}</span>
                <span className="block truncate text-muted-foreground">
                  {group.supplierName
                    ? `Rozlicza: ${group.supplierCode ? `${group.supplierCode} · ` : ''}${group.supplierName}`
                    : 'Bez dostawcy z listy dostawców'}
                  {' · '}
                  {loss.articleCount === 1 ? '1 artykuł w raporcie' : `${loss.articleCount} art. w raporcie`}
                </span>
              </button>
              {canEdit && (
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Zmień: ${group.name}`}
                  title={group.supplierId ? 'Zmień dostawcę do kontroli' : 'Podepnij dostawcę z listy dostawców'}
                  onClick={() => onEdit({ group, itm8s: [] })}
                >
                  <PencilIcon />
                </Button>
              )}
            </div>

            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 tabular-nums">
              <div>
                <dt className="text-muted-foreground">Straty, zakup netto</dt>
                <dd className="font-semibold">{formatMoney(loss.purchaseValue)} zł</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Straty, sprzedaż brutto</dt>
                <dd className="font-semibold">{formatMoney(loss.saleValueGross)} zł</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Rozliczone gratisem (brutto)</dt>
                <dd className="font-semibold">{settlement ? `${formatMoney(settlement.saleValueGross)} zł` : '–'}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">Do rozliczenia (brutto)</dt>
                <dd
                  className={cn(
                    'font-semibold',
                    rest != null && rest > 0.005 && 'text-red-700 dark:text-red-300',
                    rest != null && rest <= 0.005 && 'text-green-700 dark:text-green-300',
                  )}
                >
                  {rest == null ? '–' : `${formatMoney(Math.max(rest, 0))} zł`}
                  {rest != null && rest < -0.005 && (
                    <span className="block text-xs font-normal">nadwyżka {formatMoney(-rest)} zł</span>
                  )}
                </dd>
              </div>
            </dl>

            {!group.supplierId ? (
              <p className="border-t pt-2 text-muted-foreground">
                Podepnij dostawcę z listy dostawców (ołówek), żeby widzieć jego gratisy za pokrycie strat.
              </p>
            ) : settlement && settlement.gratis.length > 0 ? (
              <ul className="grid gap-1 border-t pt-2">
                {settlement.gratis.map((gratis) => (
                  <li key={gratis.documentId} className="flex flex-wrap items-baseline gap-x-2 tabular-nums">
                    <span className="font-medium first-letter:uppercase">{monthLabel(gratis.month)}</span>
                    <Link
                      to={documentPaths.document(gratis.documentType, gratis.documentId)}
                      className="underline-offset-4 hover:underline"
                      title="Otwórz dokument: tam zmienisz miesiąc rozliczenia gratisu"
                    >
                      {gratis.documentNumber}
                    </Link>
                    <span className="text-muted-foreground">
                      z {formatDocumentDate(gratis.documentDate)}
                      {gratis.manualMonth && ' · miesiąc zmieniony'}
                    </span>
                    <span className="ml-auto font-medium">{formatMoney(gratis.saleValueGross)} zł</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="border-t pt-2 text-muted-foreground">
                Brak gratisów za pokrycie strat rozliczonych {period ? `w okresie ${period}` : 'w tym okresie'}.
              </p>
            )}
          </section>
        )
      })}
    </div>
  )
}
