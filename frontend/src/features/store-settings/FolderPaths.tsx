import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { CheckCircle2Icon, EyeIcon, FolderInputIcon, MonitorIcon } from 'lucide-react'
import { toast } from 'sonner'

import { TextField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { Card } from '@/components/ui/card'
import { agentsKey, useAgentStatus } from '@/features/store-agent/api'
import { ApiError } from '@/lib/api'
import { DEFAULT_FOLDER, useProtoSettings, useSaveProtoSettings } from '@/lib/proto-files'
import { folderLocationKey, useSaveStoreLocations, useStoreLocations } from '@/lib/store-locations'
import type { AgentStatus } from '@/lib/types'
import { cn } from '@/lib/utils'

/*
 * Wszystkie ścieżki folderów sklepu w jednym miejscu: gdzie program sklepu wczytuje pliki z Intermanage i które foldery
 * Intermanage czyta. Ścieżki są wspólne dla całego sklepu (na serwerze), więc wpisane raz działają na każdym komputerze
 * i dla agenta sklepu, który zapisuje tylko tam, gdzie ścieżka jest wpisana tekstem. Wybór folderu w przeglądarce
 * (okno wyboru) dalej służy przeglądarce, gdy agenta nie ma.
 *
 * Folder protokołów i folder nowych artykułów są w ustawieniach plików PROTO (te same dane co na stronie „Protokoły
 * i zmiana cen”), pozostałe w ścieżkach sklepu pod `folder:<klucz folderu>`, tym samym kluczem, którego używa pole
 * „Ścieżka folderu w sklepie” w oknach zapisu.
 */

type Source = 'proto' | 'productFile' | 'location'

type FolderRow = {
  /** Klucz folderu w przeglądarce (i u agenta). */
  key: string
  source: Source
  label: string
  description: string
  /** Ścieżka, której używamy, gdy pole jest puste. */
  fallback?: string
  /** Pilnowany folder (agent zgłasza zmiany): rodzaj z `watches` agenta. */
  watch?: string
}

function saveRows(storeId: string): FolderRow[] {
  return [
    {
      key: 'deli-order-proto',
      source: 'proto',
      label: 'Protokoły, zmiany cen, etykiety i zamówienia',
      description:
        'Program sklepu wczytuje stąd protokoły, zmiany cen, cenówki, zamówienia i zamówienia garmażu. Agent pilnuje tu też, czy program wczytał zmianę cen.',
      fallback: DEFAULT_FOLDER,
    },
    {
      key: 'data-entry-product-file',
      source: 'productFile',
      label: 'Nowe i zmienione artykuły',
      description: 'Plik z nowymi artykułami z wprowadzania danych i dostawy z bazy.',
    },
    {
      key: 'data-entry-xml',
      source: 'location',
      label: 'Faktury do programu sklepu',
      description: 'Pliki faktur zapisywane we wprowadzaniu danych.',
    },
    {
      key: 'data-entry-lst',
      source: 'location',
      label: 'Listy EAN-ów',
      description: 'Listy kodów z wprowadzania danych (np. brakujące kaucje).',
    },
    {
      key: 'leaflet-pro',
      source: 'location',
      label: 'Ceny promocji',
      description: 'Pliki cen promocji z opracowania gazetki i ustawiania promocji.',
      fallback: 'N:\\ALOA\\PRO\\IMPORT',
    },
    {
      key: 'base-delivery-note-file',
      source: 'location',
      label: 'Plik dostawy z bazy',
      description: 'Plik dostawy dla programu sklepu z „Dostawy z bazy”.',
    },
    {
      key: 'base-delivery-plu',
      source: 'location',
      label: 'Sprawdź PLU',
      description: 'Lista kodów do sprawdzenia wagi z „Dostawy z bazy”.',
    },
    {
      key: `mea-comrnis:${storeId}`,
      source: 'location',
      label: 'Zamówienia MEA do ATHOS',
      description: 'Folder comrnis na serwerze sklepu, z którego ATHOS bierze zamówienia MEA.',
    },
  ]
}

function readRows(storeId: string, protoPath: string): FolderRow[] {
  return [
    {
      key: 'price-files',
      source: 'location',
      label: 'Ceny z kasy',
      description: 'Folder, do którego kasa zapisuje zmiany cen (razem z podfolderem BACKUP).',
      fallback: protoPath,
      watch: 'TRACEART',
    },
    {
      key: `mea-athos:${storeId}`,
      source: 'location',
      label: 'Dziennik ATHOS',
      description: 'Folder appres\\athos2000 na serwerze sklepu z dziennikiem wysyłek MEA.',
      watch: 'ATHOS',
    },
  ]
}

/** Ścieżka z literą dysku albo sieciowa, jak przyjmuje ją agent. */
function pathError(value: string): string | undefined {
  const path = value.trim()
  if (!path) return undefined
  if (path.includes('…')) return 'Wpisz pełną ścieżkę, bez „…”.'
  if (!/^[A-Za-z]:\\/.test(path) && !path.startsWith('\\\\')) {
    return 'Ścieżka musi zaczynać się od litery dysku (N:\\…) albo od \\\\serwer\\…'
  }
  return undefined
}

export function FolderPaths({ storeId, canEditProto }: { storeId: string; canEditProto: boolean }) {
  const locations = useStoreLocations(storeId)
  const proto = useProtoSettings(storeId)
  const agent = useAgentStatus(storeId)
  if (locations.isPending || proto.isPending) return <LoadingPanel className="h-96 rounded-xl" />

  const protoPath = proto.data?.folderPath ?? DEFAULT_FOLDER
  return (
    <div className="grid gap-4">
      <Card className="grid gap-1 p-4">
        <h3 className="font-medium">Zapis plików dla programu sklepu</h3>
        <p className="text-sm text-muted-foreground">
          Pełne ścieżki, takie same na każdym komputerze sklepu, np.{' '}
          <span className="font-mono">N:\AVISOCONFIG\BACKUP</span> albo{' '}
          <span className="font-mono">\\serwer\udział\folder</span>. Agent sklepu zapisuje tylko tam, gdzie ścieżka jest
          wpisana; bez niej plik zapisuje przeglądarka, jak dotąd. Zmiana dociera do agenta w ciągu 5 minut.
        </p>
        <ul className="mt-3 grid gap-3">
          {saveRows(storeId).map((row) => (
            <PathRow
              key={row.key}
              storeId={storeId}
              row={row}
              agent={agent.data}
              canEditProto={canEditProto}
              protoSettings={proto.data}
            />
          ))}
        </ul>
      </Card>
      <Card className="grid gap-1 p-4">
        <h3 className="font-medium">Foldery, które Intermanage czyta</h3>
        <p className="text-sm text-muted-foreground">
          Stąd przychodzą zmiany cen z kasy i wysyłki zamówień MEA. Gdy sklep pracuje przez agenta, pilnuje ich agent;
          bez niego czyta je przeglądarka co kilka minut.
        </p>
        <ul className="mt-3 grid gap-3">
          {readRows(storeId, protoPath).map((row) => (
            <PathRow key={row.key} storeId={storeId} row={row} agent={agent.data} canEditProto={canEditProto} />
          ))}
        </ul>
      </Card>
    </div>
  )
}

function PathRow({
  storeId,
  row,
  agent,
  canEditProto,
  protoSettings,
}: {
  storeId: string
  row: FolderRow
  agent: AgentStatus | undefined
  canEditProto: boolean
  protoSettings?: ReturnType<typeof useProtoSettings>['data']
}) {
  const locations = useStoreLocations(storeId)
  const saveLocations = useSaveStoreLocations(storeId)
  const saveProto = useSaveProtoSettings(storeId)
  const queryClient = useQueryClient()
  const locationKey = folderLocationKey(row.key)

  const saved =
    row.source === 'proto'
      ? protoSettings && !protoSettings.defaultFolder
        ? protoSettings.folderPath
        : ''
      : row.source === 'productFile'
        ? (protoSettings?.productFileFolderPath ?? '')
        : (locations.data?.[locationKey] ?? '')
  const [value, setValue] = useState(saved)
  // Zapis z innego miejsca (strona protokołów, okno zapisu, inny komputer) wraca do pola.
  const [lastSaved, setLastSaved] = useState(saved)
  if (saved !== lastSaved) {
    setLastSaved(saved)
    setValue(saved)
  }

  const error = pathError(value)
  const readOnly = row.source !== 'location' && !canEditProto
  const effective = value.trim() || row.fallback || ''
  const pending = saveLocations.isPending || saveProto.isPending

  const commit = () => {
    const next = value.trim()
    if (next === saved || pathError(next)) return
    const onError = (e: unknown) =>
      toast.error(e instanceof ApiError ? e.message : 'Nie udało się zapisać ścieżki', { id: `path:${row.key}` })
    const onSuccess = () => {
      toast.success(`Zapisano ścieżkę: ${row.label}`, { id: `path:${row.key}` })
      // Lista folderów agenta liczy się ze ścieżek, więc stan agenta od razu się zmienia.
      void queryClient.invalidateQueries({ queryKey: agentsKey(storeId) })
    }
    if (row.source === 'location') {
      saveLocations.mutate({ [locationKey]: next }, { onError, onSuccess })
      return
    }
    if (!protoSettings) return
    saveProto.mutate(
      {
        folderPath:
          row.source === 'proto' ? next || null : protoSettings.defaultFolder ? null : protoSettings.folderPath,
        hiddenProtocols: protoSettings.hiddenProtocols,
        productFileFolderPath:
          row.source === 'productFile' ? next || null : (protoSettings.productFileFolderPath ?? null),
      },
      { onError, onSuccess },
    )
  }

  return (
    <li className="grid gap-2 rounded-xl border p-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] sm:items-start">
      <div className="grid gap-1">
        <p className="flex items-center gap-2 text-sm font-medium">
          {row.watch ? (
            <EyeIcon aria-hidden className="size-4 text-muted-foreground" />
          ) : (
            <FolderInputIcon aria-hidden className="size-4 text-muted-foreground" />
          )}
          {row.label}
        </p>
        <p className="text-xs text-muted-foreground">{row.description}</p>
        <AgentNote row={row} agent={agent} effective={effective} />
      </div>
      <TextField
        label="Ścieżka folderu"
        autoComplete="off"
        spellCheck={false}
        className="font-mono"
        value={value}
        disabled={readOnly || pending}
        error={error}
        hint={
          readOnly
            ? 'Zmienia osoba z edycją wprowadzania danych, dokumentów albo zamówień garmażu.'
            : row.fallback && !value.trim()
              ? `Puste pole: ${row.fallback}`
              : undefined
        }
        onChange={(event) => setValue(event.target.value)}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            commit()
          }
        }}
      />
    </li>
  )
}

function AgentNote({ row, agent, effective }: { row: FolderRow; agent: AgentStatus | undefined; effective: string }) {
  if (!agent?.enabled) return null
  const handled = row.watch ? agent.watches.includes(row.watch) : agent.folders.includes(row.key)
  if (!effective || !handled) {
    return (
      <p className="flex items-center gap-1.5 text-xs font-medium text-amber-800 dark:text-amber-200">
        <MonitorIcon aria-hidden className="size-3.5" />
        Brak ścieżki: {row.watch ? 'czyta przeglądarka' : 'zapisuje przeglądarka'}
      </p>
    )
  }
  return (
    <p
      className={cn(
        'flex items-center gap-1.5 text-xs font-medium',
        agent.online ? 'text-emerald-700 dark:text-emerald-300' : 'text-muted-foreground',
      )}
    >
      <CheckCircle2Icon aria-hidden className="size-3.5" />
      {row.watch ? 'Pilnuje agent sklepu' : 'Zapisuje agent sklepu'}
      {!agent.online && ' (teraz offline, działa przeglądarka)'}
    </p>
  )
}
