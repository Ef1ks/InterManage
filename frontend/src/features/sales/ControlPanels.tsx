import { Fragment, useState } from 'react'
import { ChevronRightIcon, PencilIcon } from 'lucide-react'
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

const OPEN_KEY = 'intermanage.lossControlPanelsOpen'

function readOpen() {
  try {
    return window.localStorage.getItem(OPEN_KEY) !== '0'
  } catch {
    return true
  }
}

function saveOpen(open: boolean) {
  try {
    if (open) window.localStorage.removeItem(OPEN_KEY)
    else window.localStorage.setItem(OPEN_KEY, '0')
  } catch {
    // Bez localStorage wybór działa do odświeżenia strony.
  }
}

const money = (value: number) => `${formatMoney(value)} zł`

/**
 * Rozliczenie strat dostawców do kontroli nad tabelą: wiersz na dostawcę ze stratami jego artykułów w okresie (zakup
 * netto i sprzedaż brutto), gratisami za pokrycie strat od podpiętego dostawcy z listy dostawców (po miesiącu
 * rozliczenia: wybranym przy gratisie albo z daty dokumentu) i tym, co zostało do rozliczenia w cenach sprzedaży
 * brutto. Zwarta tabela, bo dostawców bywa kilkanaście; całość da się zwinąć (pamięta przeglądarka), a gratisy
 * dostawcy rozwija klik w kwotę rozliczoną. Klik w nazwę filtruje raport do tego dostawcy.
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
  const [open, setOpen] = useState(readOpen)
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set())
  if (groups.length === 0) return null
  const settled = new Map(settlements?.groups.map((group) => [group.groupId, group]))
  const period = settlements ? settlementPeriod(settlements) : null
  const rows = groups.map((group) => {
    const loss = losses.get(group.id) ?? NO_LOSSES
    const settlement = settled.get(group.id)
    const rest = settlement ? loss.saleValueGross - settlement.saleValueGross : null
    return { group, loss, settlement, rest }
  })
  const totalRest = rows.reduce((sum, row) => sum + Math.max(row.rest ?? 0, 0), 0)
  const toggleOpen = () => {
    setOpen(!open)
    saveOpen(!open)
  }
  const toggleGratis = (id: string) =>
    setExpanded((previous) => {
      const next = new Set(previous)
      if (!next.delete(id)) next.add(id)
      return next
    })

  return (
    <section aria-label="Rozliczenie strat dostawców" className="mb-4 rounded-xl border bg-card text-sm">
      <button
        type="button"
        aria-expanded={open}
        onClick={toggleOpen}
        className="flex w-full flex-wrap items-center gap-x-2 gap-y-1 rounded-xl px-4 py-2.5 text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/40"
      >
        <ChevronRightIcon
          aria-hidden
          className={cn(
            'size-4 shrink-0 text-muted-foreground transition-transform duration-150 ease-out motion-reduce:transition-none',
            open && 'rotate-90',
          )}
        />
        <span className="font-semibold">Rozliczenie strat dostawców ({groups.length})</span>
        {period && <span className="text-muted-foreground">· gratisy: {period}</span>}
        <span className="ml-auto tabular-nums">
          do rozliczenia{' '}
          <span className={cn('font-semibold', totalRest > 0.005 && 'text-red-700 dark:text-red-300')}>
            {money(totalRest)}
          </span>
        </span>
      </button>
      {open && (
        <div className="overflow-x-auto border-t">
          <table className="w-full tabular-nums">
            <thead>
              <tr className="text-xs text-muted-foreground">
                <th className="px-4 py-1.5 text-left font-medium">Dostawca do kontroli</th>
                <th className="px-2 py-1.5 text-right font-medium">Art.</th>
                <th className="px-2 py-1.5 text-right font-medium whitespace-nowrap">Straty netto</th>
                <th className="px-2 py-1.5 text-right font-medium whitespace-nowrap">Straty brutto</th>
                <th className="px-2 py-1.5 text-right font-medium whitespace-nowrap">Rozliczone gratisem</th>
                <th className="px-2 py-1.5 text-right font-medium whitespace-nowrap">Do rozliczenia</th>
                <th className="w-0 px-2 py-1.5">
                  <span className="sr-only">Zmień</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map(({ group, loss, settlement, rest }) => {
                const on = active.includes(group.id)
                const gratisOpen = expanded.has(group.id)
                const gratis = settlement?.gratis ?? []
                return (
                  <Fragment key={group.id}>
                    <tr className={cn('border-t hover:bg-muted/60', on && 'bg-muted')}>
                      <td className="max-w-80 py-1 pr-2 pl-3">
                        <button
                          type="button"
                          aria-pressed={on}
                          onClick={() => onActive(on ? active.filter((id) => id !== group.id) : [...active, group.id])}
                          className="flex w-full min-w-0 items-center gap-2 rounded-md py-0.5 text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/40"
                          title={on ? 'Pokaż wszystkich' : 'Pokaż w raporcie tylko tego dostawcę'}
                        >
                          <span
                            aria-hidden
                            className="h-6 w-1.5 shrink-0 rounded-full"
                            style={{ backgroundColor: hexOf(group.color) }}
                          />
                          <span className="min-w-0">
                            <span className={cn('block truncate', on ? 'font-semibold' : 'font-medium')}>
                              {group.name}
                            </span>
                            <span className="block truncate text-xs text-muted-foreground">
                              {group.supplierName
                                ? `Rozlicza: ${group.supplierCode ? `${group.supplierCode} · ` : ''}${group.supplierName}`
                                : 'Bez dostawcy z listy dostawców'}
                            </span>
                          </span>
                        </button>
                      </td>
                      <td className="px-2 py-1 text-right">{loss.articleCount}</td>
                      <td className="px-2 py-1 text-right whitespace-nowrap">{money(loss.purchaseValue)}</td>
                      <td className="px-2 py-1 text-right whitespace-nowrap">{money(loss.saleValueGross)}</td>
                      <td className="px-2 py-1 text-right whitespace-nowrap">
                        {!group.supplierId ? (
                          <span className="text-muted-foreground" title="Podepnij dostawcę z listy dostawców (ołówek)">
                            –
                          </span>
                        ) : gratis.length > 0 ? (
                          <button
                            type="button"
                            aria-expanded={gratisOpen}
                            onClick={() => toggleGratis(group.id)}
                            className="rounded-md underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/40"
                            title={gratisOpen ? 'Schowaj gratisy' : 'Pokaż gratisy'}
                          >
                            {money(settlement?.saleValueGross ?? 0)}
                            <span className="ml-1 text-xs text-muted-foreground">({gratis.length})</span>
                          </button>
                        ) : (
                          money(0)
                        )}
                      </td>
                      <td
                        className={cn(
                          'px-2 py-1 text-right font-semibold whitespace-nowrap',
                          rest != null && rest > 0.005 && 'text-red-700 dark:text-red-300',
                          rest != null && rest <= 0.005 && 'text-green-700 dark:text-green-300',
                        )}
                        title={rest != null && rest < -0.005 ? `Nadwyżka ${money(-rest)}` : undefined}
                      >
                        {rest == null ? '–' : money(Math.max(rest, 0))}
                      </td>
                      <td className="px-2 py-1">
                        {canEdit && (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            aria-label={`Zmień: ${group.name}`}
                            title={
                              group.supplierId ? 'Zmień dostawcę do kontroli' : 'Podepnij dostawcę z listy dostawców'
                            }
                            onClick={() => onEdit({ group, itm8s: [] })}
                          >
                            <PencilIcon />
                          </Button>
                        )}
                      </td>
                    </tr>
                    {gratisOpen && (
                      <tr className="bg-muted/40">
                        <td colSpan={7} className="px-4 py-2">
                          <ul className="grid gap-1">
                            {gratis.map((item) => (
                              <li key={item.documentId} className="flex flex-wrap items-baseline gap-x-2">
                                <span className="font-medium first-letter:uppercase">{monthLabel(item.month)}</span>
                                <Link
                                  to={documentPaths.document(item.documentType, item.documentId)}
                                  className="underline-offset-4 hover:underline"
                                  title="Otwórz dokument: tam zmienisz miesiąc rozliczenia gratisu"
                                >
                                  {item.documentNumber}
                                </Link>
                                <span className="text-muted-foreground">
                                  z {formatDocumentDate(item.documentDate)}
                                  {item.manualMonth && ' · miesiąc zmieniony'}
                                </span>
                                <span className="ml-auto font-medium">{money(item.saleValueGross)}</span>
                              </li>
                            ))}
                          </ul>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
