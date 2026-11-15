import { RefreshCwIcon, SettingsIcon } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { Button } from '@/components/ui/button'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import type { MeaList, MeaListItem, MeaTab, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useMeaList, useMeaSettings } from './api'
import { MeaSettingsDialog } from './MeaSettingsDialog'
import { meaDocumentPath } from './paths'

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
  const [tab, setTab] = useState<MeaTab>('ONGOING')

  const configured = settings.data?.configured === true

  return (
    <>
      <PageHeader
        title="Moduł MEA"
        description={storeLabel(store)}
        actions={
          <div className="flex items-center gap-2">
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
        <MeaTable data={list.data} tab={tab} onTab={setTab} />
      )}

      <MeaSettingsDialog
        storeId={store.id}
        settings={settings.data}
        open={settingsOpen}
        onOpenChange={setSettingsOpen}
      />
    </>
  )
}

function MeaTable({ data, tab, onTab }: { data: MeaList; tab: MeaTab; onTab: (tab: MeaTab) => void }) {
  const counts = useMemo(() => {
    const result: Record<MeaTab, number> = { TO_SEND: 0, SENT_NOT_STARTED: 0, ONGOING: 0, ARCHIVE: 0, OTHER: 0 }
    for (const item of data.items) result[item.tab] += 1
    return result
  }, [data.items])

  const rows = data.items.filter((item) => item.tab === tab)

  return (
    <>
      <div className="mb-4 flex flex-wrap gap-2">
        {TABS.map(({ key, label }) => (
          <button
            key={key}
            type="button"
            onClick={() => onTab(key)}
            className={cn(
              'rounded-lg border px-3 py-1.5 text-sm transition-colors',
              tab === key ? 'border-brand bg-brand/10 font-medium text-brand' : 'hover:bg-accent',
            )}
          >
            {label}
            <span className="ml-1.5 tabular-nums text-muted-foreground">{counts[key]}</span>
          </button>
        ))}
      </div>

      <div className="overflow-x-auto rounded-xl border bg-card">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b text-left text-muted-foreground">
              <th className="px-3 py-2 font-medium">Numer</th>
              <th className="px-3 py-2 font-medium">Nazwa</th>
              <th className="px-3 py-2 font-medium">Od</th>
              <th className="px-3 py-2 font-medium">Do</th>
              <th className="px-3 py-2 font-medium">Rezerwacja do</th>
              <th className="px-3 py-2 font-medium">Zamówienie E-MEA</th>
              <th className="px-3 py-2 text-right font-medium">Pliki</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={7} className="px-3 py-6 text-center text-muted-foreground">
                  Brak pozycji w tej zakładce.
                </td>
              </tr>
            ) : (
              rows.map((item) => <MeaRow key={item.number} item={item} />)
            )}
          </tbody>
        </table>
      </div>

      <p className="mt-3 text-xs text-muted-foreground">
        Pozycji: {rows.length}
        {data.dateFrom && ` · dane od ${formatDate(data.dateFrom)}`}
      </p>
    </>
  )
}

function MeaRow({ item }: { item: MeaListItem }) {
  const navigate = useNavigate()
  const open = () => navigate(meaDocumentPath(item.number))
  return (
    <tr
      tabIndex={0}
      onClick={open}
      onKeyDown={(event) => event.key === 'Enter' && open()}
      className="cursor-pointer border-b last:border-0 hover:bg-accent/60 focus-visible:bg-accent/60 focus-visible:outline-none"
    >
      <td className="px-3 py-2 font-semibold tabular-nums">{item.number}</td>
      <td className="px-3 py-2">{item.name}</td>
      <td className="px-3 py-2 tabular-nums">{formatDate(item.promoFrom)}</td>
      <td className="px-3 py-2 tabular-nums">{formatDate(item.promoTo)}</td>
      <td className="px-3 py-2 tabular-nums">{formatDate(item.reservationTo)}</td>
      <td className="px-3 py-2">
        {item.ordered ? (
          <span className="rounded bg-emerald-200 px-1.5 text-xs font-medium text-emerald-950 dark:bg-emerald-900 dark:text-emerald-50">
            złożone
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        )}
      </td>
      <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">{item.files.length}</td>
    </tr>
  )
}
