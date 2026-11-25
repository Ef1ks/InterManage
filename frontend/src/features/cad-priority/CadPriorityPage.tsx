import { ArrowRightIcon, CheckIcon, ClipboardCopyIcon, TriangleAlertIcon, Undo2Icon } from 'lucide-react'
import { type ReactNode, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { SegmentedField, SwitchField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
import { LoadingPanel } from '@/components/LoadingPanel'
import { PageHeader } from '@/components/PageHeader'
import { ReportFreshness } from '@/components/ReportFreshness'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useAgentComputers } from '@/features/agent-macros/api'
import { RemoteMacroButton, RemoteMacroStatus, useRemoteMacro } from '@/features/agent-macros/RemoteMacro'
import { useCan } from '@/features/auth/api'
import { countFormat, formatDocumentDate, formatMoney, formatPrice, formatQuantity } from '@/features/documents/format'
import { ArticleStatsButton } from '@/features/sales/ArticleStatsButton'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { mercalysOpenLine } from '@/lib/mercalys-login'
import { matchesSearch } from '@/lib/search'
import { nextSort, type SortAccessors, sortRows, type SortState } from '@/lib/table-sort'
import type { CadPriorityItem, CadPriorityReport, StoreSummary } from '@/lib/types'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

import {
  useAutomaticCadPriorities,
  useCadPriorities,
  useRefreshCadPriorities,
  useRunCadPriorities,
  useUndoCadPriorities,
} from './api'

type View = 'todo' | 'waiting'
type TaskFilter = 'all' | 'priority' | 'active'
type SortKey = 'name' | 'itm8' | 'topItm8' | 'stock' | 'topSale'

const SORT: SortAccessors<CadPriorityItem, SortKey> = {
  name: (item) => item.name ?? item.topName,
  itm8: (item) => item.itm8,
  topItm8: (item) => item.topItm8,
  stock: (item) => item.stock,
  topSale: (item) => item.topSalePrice,
}

const shortItm8 = (itm8: string) => itm8.replace(/^0+(?=\d)/, '')
const pill = 'inline-flex items-center rounded-md px-1.5 py-0.5 text-xs font-medium whitespace-nowrap'
/** Kartę z kilkoma ITM8 do wyboru i pominiętą przez makro zaznacza człowiek. */
const selectedByDefault = (item: CadPriorityItem) => item.alternatives.length === 0 && !item.run?.failure

const TASKS: Record<CadPriorityItem['task'], { label: string; className: string }> = {
  PRIORITY: {
    label: 'ustaw priorytet',
    className: 'bg-indigo-200 text-indigo-950 dark:bg-indigo-900/70 dark:text-indigo-100',
  },
  ACTIVE: { label: 'aktywuj', className: 'bg-teal-200 text-teal-950 dark:bg-teal-900/70 dark:text-teal-100' },
  PRIORITY_AND_ACTIVE: {
    label: 'ustaw priorytet i aktywuj',
    className: 'bg-fuchsia-200 text-fuchsia-950 dark:bg-fuchsia-900/70 dark:text-fuchsia-100',
  },
}

const errorText = (error: unknown) => (error instanceof ApiError ? error.message : 'Brak połączenia z serwerem')

/**
 * Artykuły, w których ITM8 z obowiązującego cadencier nie jest priorytetowy, choć ich EAN jest w kartotece sklepu
 * (zwykle pod lokalnym ITM8), albo jest priorytetowy, ale nieaktywny. Poprawia je makro w Mercalys (tu albo na
 * komputerze sklepu, także samo po aktualizacji danych) albo człowiek, a wynik widać w następnym raporcie kartoteki:
 * ustawiony artykuł znika, nieustawiony wraca z ostrzeżeniem.
 */
export function CadPriorityPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="ITM8 z cadencier" />
        <FullPageMessage title="Brak sklepu" description="ITM8 ustawia się osobno w każdym sklepie." inline />
      </>
    )
  }
  return <StorePriorities key={store.id} store={store} />
}

function StorePriorities({ store }: { store: StoreSummary }) {
  const report = useCadPriorities(store.id)
  return (
    <>
      <span data-wide-page hidden />
      <PageHeader title="ITM8 z cadencier" description={storeLabel(store)} />
      <HowTo
        id="cad-priority"
        className="mb-4"
        title="Jak ustawić ITM8 z cadencier"
        steps={[
          'Lista pokazuje artykuły z obowiązującego cadencier, w których priorytetowy jest inny ITM8 albo ITM8 z cadencier jest nieaktywny.',
          'Zaznacz artykuły i wybierz „Wykonaj na wolnym komputerze” albo „Makro do Mercalys” (Alt+E na tym komputerze). Makro zaznacza na karcie Priorytetowy i Aktywny.',
          'Artykuły przechodzą do „Czekają na raport”. Po wgraniu nowej bazy danych ustawione znikają, a nieustawione wracają na listę.',
          'Z włączonym „Poprawiaj po aktualizacji danych” robi to codziennie sam komputer sklepu.',
        ]}
      />
      {report.isError ? (
        <Card className="items-center px-6 py-8 text-center">
          <p className="font-medium">{errorText(report.error)}</p>
          <Button variant="outline" onClick={() => void report.refetch()}>
            Spróbuj ponownie
          </Button>
        </Card>
      ) : !report.data ? (
        <LoadingPanel className="h-96 rounded-xl" />
      ) : (
        <Priorities storeId={store.id} report={report.data} />
      )}
    </>
  )
}

function Priorities({ storeId, report }: { storeId: string; report: CadPriorityReport }) {
  const [params, setParams] = useSearchParams()
  const view: View = params.get('widok') === 'czekaja' ? 'waiting' : 'todo'
  const { text: query, setText: setQuery } = useSearchParamText('szukaj')
  const [sort, setSort] = useState<SortState<SortKey>>({ key: 'name', direction: 'asc' })
  const [checks, setChecks] = useState<Map<string, boolean>>(new Map())
  const [choices, setChoices] = useState<Map<string, string>>(new Map())
  const [task, setTask] = useState<TaskFilter>('all')
  const canEdit = useCan('DATA_ENTRY', 'EDIT')
  const { data: computers = [] } = useAgentComputers(storeId, canEdit)
  const remote = useRemoteMacro(storeId)
  const refresh = useRefreshCadPriorities(storeId)
  const automatic = useAutomaticCadPriorities(storeId)
  const run = useRunCadPriorities(storeId)
  const undo = useUndoCadPriorities(storeId)

  const todo = useMemo(() => report.items.filter((item) => item.status !== 'WAITING'), [report.items])
  const waiting = useMemo(() => report.items.filter((item) => item.status === 'WAITING'), [report.items])
  const shown = useMemo(
    () =>
      sortRows(
        (view === 'todo' ? todo : waiting).filter(
          (item) =>
            (task === 'all' || (task === 'active') === (item.task === 'ACTIVE')) &&
            matchesSearch(
              [
                item.name,
                item.brand,
                item.ean,
                item.itm8,
                item.topItm8,
                item.topName,
                item.topEan,
                item.supplier,
                ...item.alternatives.map((alternative) => alternative.itm8),
              ],
              query,
            ),
        ),
        SORT,
        sort,
      ),
    [view, todo, waiting, task, query, sort],
  )
  const isChecked = (item: CadPriorityItem) => checks.get(item.itm8) ?? selectedByDefault(item)
  const check = (items: CadPriorityItem[], value: boolean) =>
    setChecks((current) => {
      const next = new Map(current)
      items.forEach((item) => next.set(item.itm8, value))
      return next
    })
  // Zlecamy tylko to, co widać: wyszukiwanie zawęża też zaznaczenie.
  const chosen = view === 'todo' ? shown.filter(isChecked) : []
  const allChecked = shown.length > 0 && chosen.length === shown.length

  const undoRuns = (ids: string[], message: string) =>
    undo.mutate(
      { ids },
      {
        onSuccess: () =>
          toast(message, {
            action: { label: 'Przywróć', onClick: () => undo.mutate({ ids, restore: true }, { onError }) },
          }),
        onError,
      },
    )
  const onError = (error: unknown) => {
    toast.error(errorText(error))
  }

  const chosenItems = () => chosen.map((item) => ({ itm8: item.itm8, topItm8: choices.get(item.itm8) ?? item.topItm8 }))

  const startRemotely = async (agentId?: string) => {
    await remote.run({ macro: 'cad-priorities', items: chosenItems(), agentId })
    // Wynik artykułów serwer zapisuje tuż po makrze; potem i tak przyjdzie wiadomość na żywo.
    await refresh()
  }

  const switchAutomatic = (enabled: boolean) =>
    automatic.mutate(enabled, {
      onSuccess: () =>
        toast.success(
          enabled ? 'Włączono poprawianie po aktualizacji danych' : 'Wyłączono poprawianie po aktualizacji',
        ),
      onError,
    })

  const start = async (macro: boolean) => {
    try {
      const result = await run.mutateAsync({ macro, items: chosenItems() })
      const undoAction = {
        label: 'Cofnij',
        onClick: () => undoRuns(result.runIds, 'Artykuły wróciły do ustawienia'),
      }
      if (result.macro === null) {
        toast.success(`Oznaczono jako ustawione: ${result.runIds.length}`, { action: undoAction })
        return
      }
      try {
        await navigator.clipboard.writeText(
          result.macro.replace(/WINOPEN\|([^\r\n]*)/, (_, window: string) => mercalysOpenLine(window)),
        )
      } catch {
        await undo.mutateAsync({ ids: result.runIds })
        toast.error('Nie udało się skopiować makra do schowka')
        return
      }
      toast.success(`Makro w schowku: artykułów ${result.runIds.length}`, {
        description: 'Naciśnij lewy Alt+E, żeby uruchomić je w Mercalys.',
        action: undoAction,
      })
    } catch (error) {
      onError(error)
    }
  }

  if (!report.nrCad || !report.catalogGeneratedAt) {
    return (
      <Card className="items-center gap-2 px-6 py-10 text-center">
        <TriangleAlertIcon aria-hidden className="size-6 text-amber-600 dark:text-amber-400" />
        <p className="font-medium">
          {report.nrCad ? 'Sklep nie ma jeszcze wgranej bazy danych' : 'Brak obowiązującego cadencier'}
        </p>
        <p className="max-w-md text-sm text-muted-foreground">
          {report.nrCad
            ? 'Wgraj bazę danych w Imporcie danych, żeby porównać ją z cadencier.'
            : 'Sprawdź plan i pliki TOP w Imporcie danych.'}
        </p>
      </Card>
    )
  }

  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground tabular-nums">
        <span>
          Cadencier <span className="font-medium text-foreground">{report.nrCad}</span>
          {report.from && ` od ${formatDocumentDate(report.from)}`}: {countFormat.format(report.topCount)} artykułów,{' '}
          {countFormat.format(report.setCount)} z priorytetowym i aktywnym ITM8
          {report.variantCount > 0 && `, ${report.variantCount} ma inny ITM8 z tego cadencier (np. paletowy)`}
          {report.absentCount > 0 && `, ${countFormat.format(report.absentCount)} poza kartoteką sklepu`}
        </span>
        <ReportFreshness generatedAt={report.catalogGeneratedAt} />
      </div>

      {canEdit && (
        <SwitchField
          className="mb-3 bg-card"
          label="Poprawiaj po aktualizacji danych"
          description="Codziennie po wgraniu nowej bazy danych preferowany komputer sklepu sam poprawia priorytet i aktywność. Artykuły z kilkoma ITM8 do wyboru czekają na Twój wybór."
          checked={report.automatic}
          onCheckedChange={switchAutomatic}
          disabled={automatic.isPending}
        />
      )}

      <div className="mb-3 flex flex-wrap items-center gap-3">
        <SegmentedField<View>
          label="Widok"
          value={view}
          onChange={(next) =>
            setParams(
              (current) => {
                const updated = new URLSearchParams(current)
                if (next === 'waiting') updated.set('widok', 'czekaja')
                else updated.delete('widok')
                return updated
              },
              { replace: true },
            )
          }
          options={[
            { value: 'todo', label: `Do ustawienia (${todo.length})` },
            { value: 'waiting', label: `Czekają na raport (${waiting.length})` },
          ]}
        />
        <SegmentedField<TaskFilter>
          label="Do zrobienia"
          value={task}
          onChange={setTask}
          options={[
            { value: 'all', label: 'Wszystko' },
            { value: 'priority', label: 'Priorytet' },
            { value: 'active', label: 'Tylko aktywność' },
          ]}
        />
        <div className="min-w-64 flex-1">
          <SearchInput value={query} onChange={setQuery} placeholder="Szukaj: nazwa, EAN, ITM8, marka, dostawca" />
        </div>
        {view === 'todo' && (
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="outline"
              disabled={chosen.length === 0 || run.isPending}
              title="ITM8 ustawione w Mercalys bez makra: artykuły czekają na następny raport kartoteki"
              onClick={() => void start(false)}
            >
              <CheckIcon />
              Ustawione ręcznie ({chosen.length})
            </Button>
            <Button
              variant="outline"
              disabled={chosen.length === 0 || remote.busy}
              loading={run.isPending}
              title="Makro do schowka: lewy Alt+E uruchamia je w Mercalys na tym komputerze"
              onClick={() => void start(true)}
            >
              <ClipboardCopyIcon />
              Makro do Mercalys ({chosen.length})
            </Button>
            {canEdit && computers.length > 0 && (
              <RemoteMacroButton
                computers={computers}
                label={`Wykonaj na wolnym komputerze (${chosen.length})`}
                busy={remote.busy}
                disabled={chosen.length === 0 || remote.busy}
                onRun={(agentId) => void startRemotely(agentId)}
              />
            )}
          </div>
        )}
      </div>
      {remote.job && (
        <div className="mb-3">
          <RemoteMacroStatus job={remote.job} onCancel={remote.cancel} />
        </div>
      )}

      {shown.length === 0 ? (
        <Card className="items-center gap-1 px-6 py-10 text-center">
          <p className="font-medium">
            {query || task !== 'all'
              ? 'Brak artykułów dla tych filtrów'
              : view === 'todo'
                ? 'Wszystkie ITM8 z cadencier są priorytetowe i aktywne'
                : 'Nic nie czeka na raport kartoteki'}
          </p>
        </Card>
      ) : (
        <Card className="overflow-x-auto py-0">
          <Table className="tabular-nums">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                {view === 'todo' && (
                  <TableHead className="w-8 pl-4">
                    <input
                      type="checkbox"
                      checked={allChecked}
                      onChange={() => check(shown, !allChecked)}
                      aria-label={allChecked ? 'Odznacz wszystkie' : 'Zaznacz wszystkie widoczne'}
                      className="size-4 accent-blue-600"
                    />
                  </TableHead>
                )}
                <SortHead column="name" sort={sort} onSort={setSort}>
                  Artykuł w sklepie
                </SortHead>
                <SortHead column="itm8" sort={sort} onSort={setSort}>
                  ITM8 teraz
                </SortHead>
                <SortHead column="topItm8" sort={sort} onSort={setSort}>
                  ITM8 z cadencier
                </SortHead>
                <SortHead column="stock" sort={sort} onSort={setSort} right>
                  Stan
                </SortHead>
                <SortHead column="topSale" sort={sort} onSort={setSort} right>
                  Cena w cadencier
                </SortHead>
                <TableHead className="pr-4">{view === 'todo' ? 'Uwagi' : 'Zlecone'}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.map((item) => (
                <PriorityRow
                  key={item.itm8}
                  item={item}
                  checked={view === 'todo' ? isChecked(item) : null}
                  onCheck={(value) => check([item], value)}
                  choice={choices.get(item.itm8) ?? item.topItm8}
                  onChoice={(topItm8) => setChoices((current) => new Map(current).set(item.itm8, topItm8))}
                  onUndo={() => item.run && undoRuns([item.run.id], 'Artykuł wrócił do ustawienia')}
                />
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
    </>
  )
}

function SortHead({
  column,
  sort,
  onSort,
  right,
  children,
}: {
  column: SortKey
  sort: SortState<SortKey>
  onSort: (sort: SortState<SortKey>) => void
  right?: boolean
  children: ReactNode
}) {
  return (
    <SortableHead
      column={column}
      sort={sort}
      onSort={(key) => onSort(nextSort(sort, key))}
      align={right ? 'right' : 'left'}
    >
      {children}
    </SortableHead>
  )
}

function PriorityRow({
  item,
  checked,
  onCheck,
  choice,
  onChoice,
  onUndo,
}: {
  item: CadPriorityItem
  /** {@code null}: artykuł czeka na raport, bez zaznaczania. */
  checked: boolean | null
  onCheck: (value: boolean) => void
  choice: string
  onChoice: (topItm8: string) => void
  onUndo: () => void
}) {
  const options = [
    { itm8: item.topItm8, name: item.topName, purchasePrice: item.topPurchasePrice },
    ...item.alternatives,
  ]
  const chosen = options.find((option) => option.itm8 === choice) ?? options[0]
  return (
    <TableRow>
      {checked !== null && (
        <TableCell className="w-8 pl-4">
          <input
            type="checkbox"
            checked={checked}
            onChange={(event) => onCheck(event.target.checked)}
            aria-label={`Zaznacz: ${item.name ?? item.topName}`}
            className="size-4 accent-blue-600"
          />
        </TableCell>
      )}
      <TableCell className={cn('max-w-96 whitespace-normal', checked === null && 'pl-4')}>
        <p className="font-medium">
          {item.name ?? 'Bez nazwy'}
          <ArticleStatsButton article={item} className="ml-1" />
        </p>
        <p className="text-xs text-muted-foreground">
          {[item.brand, item.ean && `EAN ${item.ean}`, item.supplier].filter(Boolean).join(' · ')}
        </p>
      </TableCell>
      <TableCell>{shortItm8(item.itm8)}</TableCell>
      <TableCell className="max-w-80 whitespace-normal">
        <span className="flex items-center gap-1.5">
          <ArrowRightIcon aria-hidden className="size-3.5 shrink-0 text-muted-foreground" />
          {options.length > 1 && checked !== null ? (
            <select
              value={chosen.itm8}
              onChange={(event) => onChoice(event.target.value)}
              aria-label="ITM8 z cadencier"
              className="h-8 max-w-full rounded-md border border-input bg-background px-2 text-sm font-semibold outline-none focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
            >
              {options.map((option) => (
                <option key={option.itm8} value={option.itm8}>
                  {shortItm8(option.itm8)} · {option.name}
                </option>
              ))}
            </select>
          ) : (
            <span className="font-semibold">{shortItm8(chosen.itm8)}</span>
          )}
          <CopyButton value={shortItm8(chosen.itm8)} label="Kopiuj ITM8 z cadencier" />
        </span>
        {options.length === 1 && item.topName !== item.name && (
          <span className="block text-xs text-muted-foreground">{item.topName}</span>
        )}
      </TableCell>
      <TableCell className="text-right">{item.stock == null ? '–' : formatQuantity(item.stock)}</TableCell>
      <TableCell className="text-right">
        {item.topSalePrice == null ? '–' : `${formatMoney(item.topSalePrice)} zł`}
        {chosen.purchasePrice != null && (
          <span className="block text-xs text-muted-foreground">zakup {formatPrice(chosen.purchasePrice)} zł</span>
        )}
      </TableCell>
      <TableCell className="pr-4 whitespace-normal">
        {checked === null ? (
          <WaitingRun item={item} onUndo={onUndo} />
        ) : (
          <span className="flex flex-wrap gap-1">
            <span className={cn(pill, TASKS[item.task].className)}>{TASKS[item.task].label}</span>
            {item.status === 'FAILED' && item.run && (
              <span
                className={cn(pill, 'bg-red-200 whitespace-normal text-red-950 dark:bg-red-900/70 dark:text-red-100')}
              >
                {item.run.failure
                  ? `makro pominęło ${dateTime(item.run.createdAt)}: ${item.run.failure}`
                  : `nieustawione po zleceniu z ${dateTime(item.run.createdAt)}`}
              </span>
            )}
            {options.length > 1 && (
              <span className={cn(pill, 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100')}>
                {options.length} ITM8 z tym EAN-em: wybierz
              </span>
            )}
            {item.topKnown && item.task !== 'ACTIVE' && (
              <span
                className={cn(pill, 'bg-sky-200 text-sky-950 dark:bg-sky-900/70 dark:text-sky-100')}
                title="Ten ITM8 był już w kartotece sklepu, ale nie ma go w ostatnim raporcie"
              >
                ITM8 był w kartotece
              </span>
            )}
            {item.match !== 'MAIN_EAN' && (
              <span className={cn(pill, 'bg-violet-200 text-violet-950 dark:bg-violet-900/70 dark:text-violet-100')}>
                {item.match === 'MULTI_CODE' ? `multikod ${item.topEan}` : `kod wagi ${item.topEan}`}
              </span>
            )}
          </span>
        )}
      </TableCell>
    </TableRow>
  )
}

function WaitingRun({ item, onUndo }: { item: CadPriorityItem; onUndo: () => void }) {
  if (!item.run) return null
  return (
    <span className="flex items-center gap-2 text-sm">
      <span>
        {RUN_LABELS[item.run.source]} {dateTime(item.run.createdAt)}
        {item.run.createdBy && <span className="block text-xs text-muted-foreground">{item.run.createdBy}</span>}
      </span>
      <Button variant="ghost" size="sm" className="ml-auto" onClick={onUndo}>
        <Undo2Icon />
        Cofnij
      </Button>
    </span>
  )
}

const RUN_LABELS = { MACRO: 'makro', MANUAL: 'ręcznie', AGENT: 'po aktualizacji danych' } as const

const dateTime = (iso: string) =>
  new Date(iso).toLocaleString('pl-PL', { day: 'numeric', month: 'numeric', hour: '2-digit', minute: '2-digit' })
