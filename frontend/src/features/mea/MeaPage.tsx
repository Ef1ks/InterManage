import { ListChecksIcon, LockIcon, RefreshCwIcon, SendIcon, SettingsIcon } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { SearchInput } from '@/components/SearchInput'
import { SortableHead } from '@/components/SortableHead'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { todayIso } from '@/lib/dates'
import { nextSort, sortRows, type SortAccessors, type SortState } from '@/lib/table-sort'
import type { MeaList, MeaListItem, MeaTab, StoreSummary } from '@/lib/types'
import { useLoadMore } from '@/lib/use-load-more'
import { useSearchParamText } from '@/lib/use-search-param-text'
import { cn } from '@/lib/utils'

import { useAthosQueue, useMeaList, useMeaSettings } from './api'
import { useAthosHistorySync, useAthosStatus } from './athos-history'
import { AthosDialog } from './AthosDialog'
import { AthosQueueDialog } from './AthosQueueDialog'
import { MeaSettingsDialog } from './MeaSettingsDialog'
import { meaDocumentPath } from './paths'
import { reservationClosed } from './reservation'

/** Kolejność i etykiety zakładek jak w module MEA eMEA. */
const TABS: { key: MeaTab; label: string }[] = [
  { key: 'TO_SEND', label: 'Do wysłania' },
  { key: 'SENT_NOT_STARTED', label: 'Wysłane – promocja jeszcze się nie rozpoczęła' },
  { key: 'ONGOING', label: 'Trwa promocja' },
  { key: 'ARCHIVE', label: 'Archiwum' },
  { key: 'OTHER', label: 'Inne' },
]

const formatDate = (iso: string | null) =>
  iso ? new Date(iso).toLocaleDateString('pl-PL', { day: 'numeric', month: 'numeric', year: 'numeric' }) : '—'

export function MeaPage() {
  const store = useCurrentStore()
  if (!store) {
    return (
      <>
        <PageHeader title="Moduł MEA" />
        <FullPageMessage title="Brak sklepu" description="MEA wczytuje się osobno dla każdego sklepu." inline />
      </>
    )
  }
  return <StoreMea key={store.id} store={store} />
}

function StoreMea({ store }: { store: StoreSummary }) {
  const settings = useMeaSettings(store.id)
  const list = useMeaList(store.id, settings.data?.configured === true)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [athosOpen, setAthosOpen] = useState(false)
  const [queueOpen, setQueueOpen] = useState(false)
  const athosQueue = useAthosQueue(store.id)
  const athos = useAthosHistorySync(store.id)
  const athosStatus = useAthosStatus(store.id)

  const configured = settings.data?.configured === true
  // Kropka na przycisku: dziennik czeka na zgodę albo jest błąd odczytu (folder niewybrany to świadomy brak).
  const queued = athosQueue.data?.length ?? 0
  const athosAttention = athos.watch.access === 'prompt' || athos.watch.error !== null

  return (
    <>
      <PageHeader
        title="Moduł MEA"
        description={storeLabel(store)}
        actions={
          <div className="flex items-center gap-2">
            <Button
              variant={queued > 0 ? 'default' : 'outline'}
              onClick={() => setQueueOpen(true)}
              title="Zamówienia MEA czekające na wysłanie do ATHOS z komputera z dostępem do ATHOS"
            >
              <ListChecksIcon />
              Kolejka ATHOS
              {queued > 0 && <span className="tabular-nums">({queued})</span>}
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                // Kliknięcie to gest, więc od razu prosimy o zgodę na odczyt folderu, gdy na nią czeka.
                if (athos.watch.access === 'prompt') void athos.check(true)
                setAthosOpen(true)
              }}
              title={
                athosStatus.data?.read
                  ? `Statusy z ATHOS, ostatni odczyt ${new Date(athosStatus.data.importedAt ?? '').toLocaleString('pl-PL')}`
                  : 'Wybierz folder dziennika ATHOS, żeby lista wiedziała, co już wysłano'
              }
              className="relative"
            >
              <SendIcon />
              ATHOS
              {athosAttention && (
                <span
                  aria-label="Wymaga uwagi"
                  className="absolute -top-1 -right-1 size-2.5 rounded-full bg-amber-500"
                />
              )}
            </Button>
            {configured && (
              <Button variant="outline" onClick={() => list.refetch()} loading={list.isFetching}>
                <RefreshCwIcon />
                Odśwież
              </Button>
            )}
            <Button variant="outline" onClick={() => setSettingsOpen(true)}>
              <SettingsIcon />
              Klucze API
            </Button>
          </div>
        }
      />

      {settings.isPending ? (
        <p className="px-1 py-6 text-sm text-muted-foreground">Wczytywanie…</p>
      ) : !configured ? (
        <FullPageMessage
          title="Podaj klucze API MEA"
          description="Wpisz KEY i SECRET z portalu Grupy Muszkieterów (Moje konto → API), żeby wczytać listę MEA tego sklepu."
          inline
          action={{ label: 'Ustaw klucze API', onClick: () => setSettingsOpen(true) }}
        />
      ) : list.isError ? (
        <FullPageMessage
          title="Nie udało się wczytać MEA"
          description={list.error instanceof Error ? list.error.message : 'Spróbuj ponownie za chwilę.'}
          inline
          action={{ label: 'Spróbuj ponownie', onClick: () => void list.refetch() }}
        />
      ) : list.isPending ? (
        <p className="px-1 py-6 text-sm text-muted-foreground">Wczytywanie listy MEA…</p>
      ) : (
        <MeaTable data={list.data} />
      )}

      <MeaSettingsDialog
        storeId={store.id}
        settings={settings.data}
        open={settingsOpen}
        onOpenChange={setSettingsOpen}
      />
      <AthosQueueDialog storeId={store.id} open={queueOpen} onOpenChange={setQueueOpen} />
      <AthosDialog
        storeId={store.id}
        open={athosOpen}
        onOpenChange={setAthosOpen}
        watch={athos.watch}
        onCheck={() => void athos.check(true)}
      />
    </>
  )
}

/** Tyle wierszy rysujemy od razu; kolejne dochodzą przy przewijaniu. */
const PAGE = 20

type SortKey = 'number' | 'name' | 'from' | 'to' | 'reservation'

/** Sortowanie w adresie po polsku. */
const SORT_PARAMS: Record<SortKey, string> = {
  number: 'numer',
  name: 'nazwa',
  from: 'od',
  to: 'do',
  reservation: 'rezerwacja',
}

const SORT_ACCESSORS: SortAccessors<MeaListItem, SortKey> = {
  number: (item) => Number(item.number),
  name: (item) => item.name,
  from: (item) => item.promoFrom,
  to: (item) => item.promoTo,
  reservation: (item) => item.reservationTo,
}

/** Domyślne sortowanie zakładki: do wysłania od najbliższego końca rezerwacji, archiwum od najnowszej promocji. */
const DEFAULT_SORT: Record<MeaTab, SortState<SortKey>> = {
  TO_SEND: { key: 'reservation', direction: 'asc' },
  SENT_NOT_STARTED: { key: 'from', direction: 'asc' },
  ONGOING: { key: 'to', direction: 'asc' },
  ARCHIVE: { key: 'to', direction: 'desc' },
  OTHER: { key: 'number', direction: 'desc' },
}

const TAB_PARAMS: Record<MeaTab, string> = {
  TO_SEND: 'do-wyslania',
  SENT_NOT_STARTED: 'wyslane',
  ONGOING: 'trwa',
  ARCHIVE: 'archiwum',
  OTHER: 'inne',
}

type OrderedFilter = '' | 'tak' | 'nie'
type ReservationFilter = '' | 'otwarta' | 'zamknieta'

/** Tekst do porównań: małe litery bez polskich znaków. */
const plainText = (value: string) => value.toLowerCase().replace(/ł/g, 'l').normalize('NFD').replace(/\p{M}/gu, '')

function MeaTable({ data }: { data: MeaList }) {
  const [params, setParams] = useSearchParams()
  const search = useSearchParamText('szukaj')
  const tab =
    (Object.entries(TAB_PARAMS).find(([, value]) => value === params.get('zakladka'))?.[0] as MeaTab | undefined) ??
    'ONGOING'
  const ordered = (params.get('zamowienie') ?? '') as OrderedFilter
  const reservation = (params.get('rezerwacja') ?? '') as ReservationFilter
  const sortParam = Object.entries(SORT_PARAMS).find(([, value]) => value === params.get('sortuj'))?.[0] as
    SortKey | undefined
  const sortKey = sortParam ?? DEFAULT_SORT[tab].key
  const sortDirection = sortParam
    ? params.get('kierunek') === 'malejaco'
      ? 'desc'
      : 'asc'
    : DEFAULT_SORT[tab].direction
  const sort = useMemo<SortState<SortKey>>(() => ({ key: sortKey, direction: sortDirection }), [sortKey, sortDirection])

  const update = (change: Record<string, string | null>) =>
    setParams(
      (current) => {
        const next = new URLSearchParams(current)
        for (const [key, value] of Object.entries(change)) {
          if (value) next.set(key, value)
          else next.delete(key)
        }
        return next
      },
      { replace: true },
    )

  const today = todayIso()
  const words = useMemo(() => plainText(search.debounced).split(/\s+/).filter(Boolean), [search.debounced])

  // Filtry poza zakładką; liczby przy zakładkach liczą się z nich, więc widać, gdzie są wyniki wyszukiwania.
  const filtered = useMemo(
    () =>
      data.items.filter((item) => {
        const text = plainText(`${item.number} ${item.name}`)
        return (
          words.every((word) => text.includes(word)) &&
          (ordered === '' || item.ordered === (ordered === 'tak')) &&
          (reservation === '' || reservationClosed(item.reservationTo, today) === (reservation === 'zamknieta'))
        )
      }),
    [data.items, words, ordered, reservation, today],
  )

  const counts = useMemo(() => {
    const result: Record<MeaTab, number> = { TO_SEND: 0, SENT_NOT_STARTED: 0, ONGOING: 0, ARCHIVE: 0, OTHER: 0 }
    for (const item of filtered) result[item.tab] += 1
    return result
  }, [filtered])

  const rows = useMemo(
    () =>
      sortRows(
        filtered.filter((item) => item.tab === tab),
        SORT_ACCESSORS,
        sort,
      ),
    [filtered, tab, sort],
  )

  // Widoczne wiersze zerują się przy każdej zmianie listy (klucz), a kolejne dochodzą przy przewijaniu.
  const viewKey = `${tab}|${words.join(' ')}|${ordered}|${reservation}|${sort.key}|${sort.direction}`
  const [shown, setShown] = useState({ key: viewKey, count: PAGE })
  const count = shown.key === viewKey ? shown.count : PAGE
  const visible = rows.slice(0, count)
  const more = useLoadMore<HTMLDivElement>(
    () => setShown({ key: viewKey, count: count + PAGE }),
    visible.length < rows.length,
    visible.length,
  )

  const onSort = (key: SortKey) => {
    const next = nextSort(sort, key)
    update({ sortuj: SORT_PARAMS[next.key], kierunek: next.direction === 'desc' ? 'malejaco' : null })
  }

  const filtersActive = search.text !== '' || ordered !== '' || reservation !== ''

  return (
    <>
      <div className="mb-3 flex flex-wrap gap-2">
        {TABS.map(({ key, label }) => (
          <button
            key={key}
            type="button"
            onClick={() => update({ zakladka: TAB_PARAMS[key], sortuj: null, kierunek: null })}
            className={cn(
              'rounded-lg border px-3 py-1.5 text-sm transition-colors',
              tab === key ? 'border-brand bg-brand/10 font-medium text-brand' : 'hover:bg-accent',
            )}
          >
            {label}
            <span className="ml-1.5 text-muted-foreground tabular-nums">{counts[key]}</span>
          </button>
        ))}
      </div>

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <SearchInput
          value={search.text}
          onChange={search.setText}
          placeholder="Szukaj po numerze albo nazwie MEA"
          className="w-full sm:w-80"
        />
        <FilterChips
          label="Zamówienie E-MEA"
          value={ordered}
          options={[
            ['', 'Wszystkie'],
            ['tak', 'Złożone'],
            ['nie', 'Niezłożone'],
          ]}
          onChange={(value) => update({ zamowienie: value || null })}
        />
        <FilterChips
          label="Rezerwacja"
          value={reservation}
          options={[
            ['', 'Wszystkie'],
            ['otwarta', 'Otwarta'],
            ['zamknieta', 'Zamknięta'],
          ]}
          onChange={(value) => update({ rezerwacja: value || null })}
        />
        {filtersActive && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              search.setText('')
              update({ szukaj: null, zamowienie: null, rezerwacja: null })
            }}
          >
            Wyczyść filtry
          </Button>
        )}
      </div>

      <div className="overflow-x-auto rounded-xl border bg-card">
        <Table>
          <TableHeader>
            <TableRow>
              <SortableHead column="number" sort={sort} onSort={onSort}>
                Numer
              </SortableHead>
              <SortableHead column="name" sort={sort} onSort={onSort}>
                Nazwa
              </SortableHead>
              <SortableHead column="from" sort={sort} onSort={onSort}>
                Od
              </SortableHead>
              <SortableHead column="to" sort={sort} onSort={onSort}>
                Do
              </SortableHead>
              <SortableHead column="reservation" sort={sort} onSort={onSort}>
                Rezerwacja do
              </SortableHead>
              <TableHead>Zamówienie E-MEA</TableHead>
              <TableHead>Wysłano z ATHOS</TableHead>
              <TableHead className="text-right">Pliki</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={8} className="py-6 text-center text-muted-foreground">
                  {filtersActive ? 'Nic nie pasuje do wyszukiwania i filtrów.' : 'Brak pozycji w tej zakładce.'}
                </TableCell>
              </TableRow>
            ) : (
              visible.map((item) => <MeaRow key={item.number} item={item} today={today} />)
            )}
          </TableBody>
        </Table>
        {visible.length < rows.length && <div ref={more} aria-hidden className="h-px" />}
      </div>

      <p className="mt-3 text-xs text-muted-foreground">
        Pokazano {visible.length} z {rows.length}
        {data.dateFrom && ` · dane od ${formatDate(data.dateFrom)}`}
      </p>
    </>
  )
}

/** Wybór jednej z kilku wartości filtra jako przyciski. */
function FilterChips<V extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: V
  options: [V, string][]
  onChange: (value: V) => void
}) {
  return (
    <div role="group" aria-label={label} className="flex items-center gap-1 rounded-lg border p-0.5 text-sm">
      <span className="px-2 text-xs text-muted-foreground">{label}</span>
      {options.map(([option, text]) => (
        <button
          key={option || 'all'}
          type="button"
          aria-pressed={value === option}
          onClick={() => onChange(option)}
          className={cn(
            'rounded-md px-2 py-1 transition-colors',
            value === option ? 'bg-accent font-medium text-foreground' : 'text-muted-foreground hover:bg-accent/60',
          )}
        >
          {text}
        </button>
      ))}
    </div>
  )
}

function MeaRow({ item, today }: { item: MeaListItem; today: string }) {
  const navigate = useNavigate()
  const open = () => navigate(meaDocumentPath(item.number))
  const closed = reservationClosed(item.reservationTo, today)
  return (
    <TableRow
      tabIndex={0}
      onClick={open}
      onKeyDown={(event) => event.key === 'Enter' && open()}
      className="cursor-pointer focus-visible:bg-accent/60 focus-visible:outline-none"
    >
      <TableCell className="font-semibold tabular-nums">{item.number}</TableCell>
      <TableCell>{item.name}</TableCell>
      <TableCell className="tabular-nums">{formatDate(item.promoFrom)}</TableCell>
      <TableCell className="tabular-nums">{formatDate(item.promoTo)}</TableCell>
      <TableCell className={cn('tabular-nums', closed && 'text-muted-foreground')}>
        <span
          className="inline-flex items-center gap-1"
          title={closed ? 'Rezerwacja zamknięta: dokument tylko do podglądu' : undefined}
        >
          {formatDate(item.reservationTo)}
          {closed && <LockIcon aria-label="Rezerwacja zamknięta" className="size-3.5" />}
        </span>
      </TableCell>
      <TableCell>
        {item.ordered ? (
          <span className="rounded bg-emerald-200 px-1.5 text-xs font-medium text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50">
            złożone
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </TableCell>
      <TableCell className="tabular-nums">
        {item.athosSentAt ? (
          <span className="inline-flex flex-col">
            <span>
              {new Date(item.athosSentAt).toLocaleString('pl-PL', {
                day: '2-digit',
                month: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </span>
            <span className="text-xs text-muted-foreground">
              {item.athosPositions} poz. · {item.athosColis} COLI
            </span>
          </span>
        ) : item.orderSavedAt ? (
          <span className="inline-flex flex-col" title="Zapisano z aplikacji, dziennik ATHOS jeszcze tego nie pokazał">
            <span>
              {new Date(item.orderSavedAt).toLocaleString('pl-PL', {
                day: '2-digit',
                month: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
              })}
            </span>
            <span className="text-xs text-sky-700 dark:text-sky-300">zapisano · {item.orderColis} COLI</span>
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </TableCell>
      <TableCell className="text-right text-muted-foreground tabular-nums">{item.files.length}</TableCell>
    </TableRow>
  )
}
