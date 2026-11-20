import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CheckCircle2Icon, EyeIcon, FolderInputIcon, MonitorIcon, RotateCcwIcon, SplitIcon } from 'lucide-react'
import { toast } from 'sonner'

import { TextField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCurrentUser } from '@/features/auth/api'
import { agentsKey, useAgentStatus } from '@/features/store-agent/api'
import { api, ApiError } from '@/lib/api'
import { useProtoSettings, useSaveProtoSettings } from '@/lib/proto-files'
import { folderLocationKey, useSaveStoreLocations } from '@/lib/store-locations'
import type { AgentStatus, FolderPath, ProtoSettings } from '@/lib/types'
import { cn } from '@/lib/utils'

/*
 * Ścieżki folderów ustala aplikacja (domyślne, wspólne dla wszystkich sklepów, `DefaultFolders` na serwerze). Sklep może
 * wpisać własną ścieżkę, która obowiązuje tylko w nim, albo wrócić do domyślnej. Samych ścieżek nie pokazujemy
 * każdemu: widzi je tylko właściciel, pozostali widzą „Domyślna” albo „Własna” i mogą wpisać nową albo przywrócić
 * domyślną. Rodzaje plików z tym samym folderem (serwer daje skrót ścieżki, `folder`) są jednym wierszem; „Ustaw
 * osobno” rozdziela wiersz.
 *
 * Folder plików PROTO i folder nowych artykułów zapisują się przez ustawienia plików PROTO sklepu, pozostałe przez
 * ścieżki sklepu (`folder:<klucz>`). Pusta ścieżka przywraca domyślną.
 */

type Source = 'proto' | 'productFile' | 'location'

type FolderInfo = { label: string; description: string; source: Source; watch?: string }

const INFO: Record<string, FolderInfo> = {
  'deli-order-proto': {
    label: 'Protokoły, zmiany cen, etykiety i zamówienia',
    description:
      'Program sklepu wczytuje stąd protokoły, zmiany cen, cenówki i zamówienia; tu też widać, że wczytał zmianę cen.',
    source: 'proto',
  },
  'data-entry-product-file': {
    label: 'Nowe i zmienione artykuły',
    description: 'Plik z nowymi artykułami z wprowadzania danych i dostawy z bazy.',
    source: 'productFile',
  },
  'data-entry-xml': {
    label: 'Faktury do programu sklepu',
    description: 'Pliki faktur zapisywane we wprowadzaniu danych.',
    source: 'location',
  },
  'data-entry-lst': {
    label: 'Listy EAN-ów',
    description: 'Listy kodów z wprowadzania danych (np. brakujące kaucje).',
    source: 'location',
  },
  'leaflet-pro': {
    label: 'Ceny promocji',
    description: 'Pliki cen promocji z opracowania gazetki i ustawiania promocji.',
    source: 'location',
  },
  'base-delivery-note-file': {
    label: 'Plik dostawy z bazy',
    description: 'Plik dostawy dla programu sklepu z „Dostawy z bazy”.',
    source: 'location',
  },
  'base-delivery-plu': {
    label: 'Sprawdź PLU',
    description: 'Lista kodów do sprawdzenia wagi z „Dostawy z bazy”.',
    source: 'location',
  },
  'mea-comrnis': {
    label: 'Zamówienia MEA do ATHOS',
    description: 'Folder, z którego ATHOS bierze zamówienia MEA.',
    source: 'location',
  },
  'price-files': {
    label: 'Ceny z kasy (odczyt)',
    description: 'Folder, do którego kasa zapisuje zmiany cen (razem z podfolderem BACKUP).',
    source: 'location',
    watch: 'TRACEART',
  },
  'mea-athos': {
    label: 'Dziennik ATHOS (odczyt)',
    description: 'Folder z dziennikiem wysyłek MEA.',
    source: 'location',
    watch: 'ATHOS',
  },
}

const baseKey = (key: string) => key.replace(/^(mea-athos|mea-comrnis):.*$/, '$1')
const infoOf = (key: string): FolderInfo => INFO[baseKey(key)] ?? { label: key, description: '', source: 'location' }

const folderPathsKey = (storeId: string) => ['stores', storeId, 'file-locations', 'folders'] as const

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
  const folders = useQuery({
    queryKey: folderPathsKey(storeId),
    queryFn: () => api<FolderPath[]>(`/stores/${storeId}/file-locations/folders`),
  })
  const proto = useProtoSettings(storeId)
  const agent = useAgentStatus(storeId)
  const owner = useCurrentUser().owner
  const [split, setSplit] = useState<string[]>([])
  if (folders.isPending || proto.isPending) return <LoadingPanel className="h-96 rounded-xl" />
  if (folders.isError) {
    return <p className="text-sm text-red-700 dark:text-red-300">Nie udało się wczytać folderów.</p>
  }

  const groups: FolderPath[][] = []
  for (const folder of folders.data) {
    const group = split.includes(folder.folder)
      ? undefined
      : groups.find((members) => members[0].folder === folder.folder)
    if (group) group.push(folder)
    else groups.push([folder])
  }

  return (
    <Card className="grid gap-1 p-4">
      <h3 className="font-medium">Foldery</h3>
      <p className="text-sm text-muted-foreground">
        Ścieżki ustala aplikacja i są takie same we wszystkich sklepach. Gdy w tym sklepie folder jest gdzie indziej,
        wpisz nową ścieżkę: obowiązuje tylko w tym sklepie, a „Przywróć domyślną” wraca do ustalonej.
        {owner ? ' Ścieżki widzi tylko właściciel.' : ''} Rodzaje plików z tym samym folderem są w jednym wierszu.
      </p>
      <ul className="mt-3 grid gap-3">
        {groups.map((members) => (
          <GroupRow
            key={members.map((member) => member.key).join('|')}
            storeId={storeId}
            members={members}
            agent={agent.data}
            owner={owner}
            canEditProto={canEditProto}
            protoSettings={proto.data}
            onSplit={() => setSplit((current) => [...current, members[0].folder])}
          />
        ))}
      </ul>
    </Card>
  )
}

function GroupRow({
  storeId,
  members,
  agent,
  owner,
  canEditProto,
  protoSettings,
  onSplit,
}: {
  storeId: string
  members: FolderPath[]
  agent: AgentStatus | undefined
  owner: boolean
  canEditProto: boolean
  protoSettings: ProtoSettings | undefined
  onSplit: () => void
}) {
  const saveLocations = useSaveStoreLocations(storeId)
  const saveProto = useSaveProtoSettings(storeId)
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState('')

  const custom = members.some((member) => member.custom)
  const needsProto = members.some((member) => infoOf(member.key).source !== 'location')
  const readOnly = needsProto && !canEditProto
  const pending = saveLocations.isPending || saveProto.isPending
  const label = members.map((member) => infoOf(member.key).label).join(' · ')
  const error = pathError(value)

  /** Zapisuje ścieżkę wszystkim rodzajom w wierszu; pusta = domyślna. */
  const apply = async (path: string, done: string) => {
    try {
      const locationChanges: Record<string, string> = {}
      for (const member of members) {
        if (infoOf(member.key).source === 'location') locationChanges[folderLocationKey(member.key)] = path
      }
      if (Object.keys(locationChanges).length > 0) await saveLocations.mutateAsync(locationChanges)
      const protoMember = members.some((member) => infoOf(member.key).source === 'proto')
      const productMember = members.some((member) => infoOf(member.key).source === 'productFile')
      if ((protoMember || productMember) && protoSettings) {
        await saveProto.mutateAsync({
          folderPath: protoMember ? path || null : protoSettings.defaultFolder ? null : protoSettings.folderPath,
          hiddenProtocols: protoSettings.hiddenProtocols,
          productFileFolderPath: productMember ? path || null : protoSettings.productFileFolderPath,
        })
      }
      toast.success(`${done}: ${label}`, { id: `path:${label}` })
      setEditing(false)
      setValue('')
      void queryClient.invalidateQueries({ queryKey: folderPathsKey(storeId) })
      // Lista folderów agenta liczy się ze ścieżek, więc stan agenta od razu się zmienia.
      void queryClient.invalidateQueries({ queryKey: agentsKey(storeId) })
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Nie udało się zapisać ścieżki', { id: `path:${label}` })
    }
  }

  const submit = () => {
    const next = value.trim()
    if (!next || pathError(next)) return
    void apply(next, 'Zapisano nową ścieżkę')
  }

  return (
    <li className="grid gap-3 rounded-xl border p-3 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)] sm:items-start">
      <ul className="grid gap-2">
        {members.map((member) => {
          const info = infoOf(member.key)
          return (
            <li key={member.key} className="grid gap-0.5">
              <p className="flex items-center gap-2 text-sm font-medium">
                {info.watch ? (
                  <EyeIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                ) : (
                  <FolderInputIcon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
                )}
                {info.label}
              </p>
              <p className="pl-6 text-xs text-muted-foreground">{info.description}</p>
              <AgentNote member={member} info={info} agent={agent} />
            </li>
          )
        })}
      </ul>
      <div className="grid gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Badge
            className={cn(
              custom
                ? 'bg-amber-200 text-amber-950 dark:bg-amber-900/70 dark:text-amber-100'
                : 'bg-muted text-foreground',
            )}
          >
            {custom ? 'Własna ścieżka (tylko ten sklep)' : 'Domyślna ścieżka'}
          </Badge>
          {owner && members[0].path && (
            <span className="min-w-0 truncate font-mono text-xs" title={members[0].path}>
              {members[0].path}
            </span>
          )}
        </div>
        {owner && custom && members[0].defaultPath && (
          <p className="text-xs text-muted-foreground">
            Domyślna: <span className="font-mono">{members[0].defaultPath}</span>
          </p>
        )}
        {editing ? (
          <div className="grid gap-2">
            <TextField
              label="Nowa ścieżka folderu dla tego sklepu"
              autoComplete="off"
              spellCheck={false}
              className="font-mono"
              value={value}
              autoFocus
              disabled={pending}
              error={error}
              hint="Pełna ścieżka, np. N:\FOLDER albo \\serwer\udział\folder."
              onChange={(event) => setValue(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault()
                  submit()
                }
                if (event.key === 'Escape') setEditing(false)
              }}
            />
            <div className="flex gap-2">
              <Button size="sm" onClick={submit} loading={pending} disabled={!value.trim() || !!error}>
                Zapisz
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setEditing(false)} disabled={pending}>
                Anuluj
              </Button>
            </div>
          </div>
        ) : (
          <div className="flex flex-wrap gap-2">
            <Button size="sm" variant="outline" onClick={() => setEditing(true)} disabled={readOnly || pending}>
              Wpisz nową ścieżkę
            </Button>
            {custom && (
              <Button
                size="sm"
                variant="ghost"
                onClick={() => void apply('', 'Przywrócono domyślną ścieżkę')}
                disabled={readOnly || pending}
              >
                <RotateCcwIcon aria-hidden />
                Przywróć domyślną
              </Button>
            )}
            {members.length > 1 && (
              <Button size="sm" variant="ghost" onClick={onSplit} disabled={pending}>
                <SplitIcon aria-hidden />
                Ustaw osobno
              </Button>
            )}
          </div>
        )}
        {readOnly && (
          <p className="text-xs text-muted-foreground">
            Zmienia osoba z edycją wprowadzania danych, dokumentów albo zamówień garmażu.
          </p>
        )}
      </div>
    </li>
  )
}

function AgentNote({ member, info, agent }: { member: FolderPath; info: FolderInfo; agent: AgentStatus | undefined }) {
  if (!agent?.enabled) return null
  const handled = info.watch ? agent.watches.includes(info.watch) : agent.folders.includes(member.key)
  if (!handled) {
    return (
      <p className="flex items-center gap-1.5 pl-6 text-xs font-medium text-amber-800 dark:text-amber-200">
        <MonitorIcon aria-hidden className="size-3.5" />
        {info.watch ? 'Czyta przeglądarka' : 'Zapisuje przeglądarka'}
      </p>
    )
  }
  return (
    <p
      className={cn(
        'flex items-center gap-1.5 pl-6 text-xs font-medium',
        agent.online ? 'text-emerald-700 dark:text-emerald-300' : 'text-muted-foreground',
      )}
    >
      <CheckCircle2Icon aria-hidden className="size-3.5" />
      {info.watch ? 'Pilnuje agent sklepu' : 'Zapisuje agent sklepu'}
      {!agent.online && ' (teraz offline, działa przeglądarka)'}
    </p>
  )
}
