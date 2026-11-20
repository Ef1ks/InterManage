import { useState } from 'react'
import { MonitorIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { CopyButton } from '@/components/CopyButton'
import { Form, FormActions, TextField } from '@/components/form'
import { LoadingPanel } from '@/components/LoadingPanel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { ApiError } from '@/lib/api'
import type { StoreAgent } from '@/lib/types'
import { cn } from '@/lib/utils'

import { useAddAgent, useAgentEnabled, useRemoveAgent, useRestoreAgent, useStoreAgents } from './api'
import { setSystemNotificationsEnabled, systemNotificationsEnabled } from './use-live-events'

const dateTime = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'medium' })

const errorMessage = (error: unknown, fallback: string) => (error instanceof ApiError ? error.message : fallback)

/**
 * Agent sklepu (Ustawienia sklepu, tylko właściciel): wybór „pliki i foldery przez agenta / jak dotąd przez
 * przeglądarkę”, klucze agentów (pokazywane raz), stan online, wersja i komputer, powiadomienia systemowe.
 */
export function StoreAgentSettings({ storeId }: { storeId: string }) {
  const agents = useStoreAgents(storeId)
  const add = useAddAgent(storeId)
  const remove = useRemoveAgent(storeId)
  const restore = useRestoreAgent(storeId)
  const enabled = useAgentEnabled(storeId)
  const [name, setName] = useState('')
  const [newKey, setNewKey] = useState<{ name: string; key: string } | null>(null)

  if (agents.isPending) return <LoadingPanel className="h-72 rounded-xl" />
  if (agents.isError) {
    return (
      <p className="text-sm text-red-700 dark:text-red-300">
        {errorMessage(agents.error, 'Nie udało się wczytać agentów.')}
      </p>
    )
  }
  const data = agents.data
  const active = data.agents.filter((agent) => !agent.deletedAt)
  const removed = data.agents.filter((agent) => agent.deletedAt)
  const online = active.some((agent) => agent.online)

  const submit = () => {
    const value = name.trim()
    if (!value) return
    add.mutate(value, {
      onSuccess: (result) => {
        const created = result.agents.find((agent) => agent.key)
        if (created?.key) setNewKey({ name: created.name, key: created.key })
        setName('')
      },
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się dodać agenta.')),
    })
  }

  const removeAgent = (agent: StoreAgent) =>
    remove.mutate(agent.id, {
      onSuccess: () =>
        toast.success(`Usunięto agenta ${agent.name}. Jego klucz już nie działa.`, {
          action: { label: 'Przywróć', onClick: () => restore.mutate(agent.id) },
        }),
      onError: (error) => toast.error(errorMessage(error, 'Nie udało się usunąć agenta.')),
    })

  return (
    <div className="grid gap-4">
      <Card className="grid gap-4 p-4">
        <div className="flex items-start justify-between gap-4">
          <div className="grid gap-1">
            <h3 className="font-medium">Praca z folderami sklepu</h3>
            <p className="text-sm text-muted-foreground">
              {data.enabled
                ? 'Przez agenta: gdy agent jest online, zapisuje pliki dla programu sklepu i pilnuje folderów (ceny z kasy, zmiany cen, dziennik ATHOS). Gdy jest offline, wszystko wraca do przeglądarki.'
                : 'Przez przeglądarkę, jak dotąd: każdy komputer zapisuje do folderów wybranych w przeglądarce i sprawdza je co kilka minut.'}
            </p>
          </div>
          <Switch
            aria-label="Praca z folderami przez agenta sklepu"
            checked={data.enabled}
            disabled={enabled.isPending}
            onCheckedChange={(checked) =>
              enabled.mutate(checked, {
                onSuccess: () =>
                  toast.success(
                    checked ? 'Sklep pracuje z folderami przez agenta' : 'Sklep pracuje z folderami przez przeglądarkę',
                  ),
                onError: (error) => toast.error(errorMessage(error, 'Nie udało się zapisać ustawienia.')),
              })
            }
          />
        </div>
        {data.enabled && !online && (
          <p className="rounded-lg bg-amber-200 px-3 py-2 text-sm text-amber-950 dark:bg-amber-900/60 dark:text-amber-100">
            Żaden agent nie jest teraz online, więc pliki zapisuje przeglądarka, jak dotąd.
          </p>
        )}
        <NotificationsToggle />
      </Card>

      {newKey && (
        <Card className="grid gap-3 border-emerald-500 p-4">
          <h3 className="font-medium">Klucz agenta „{newKey.name}”</h3>
          <p className="text-sm text-muted-foreground">
            Skopiuj go teraz i wklej w agencie przy pierwszym uruchomieniu. Później nie da się go już zobaczyć (można
            dodać nowego agenta).
          </p>
          <div className="flex items-center gap-2 rounded-lg bg-muted px-3 py-2 font-mono text-sm break-all">
            <span className="flex-1">{newKey.key}</span>
            <CopyButton value={newKey.key} label="Skopiuj klucz" />
          </div>
          <div>
            <Button variant="outline" size="sm" onClick={() => setNewKey(null)}>
              Gotowe
            </Button>
          </div>
        </Card>
      )}

      <Card className="grid gap-3 p-4">
        <h3 className="font-medium">Agenci</h3>
        {active.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Brak agentów. Dodaj agenta, skopiuj klucz i uruchom program agenta na komputerze w sklepie (z „Do
            pobrania”).
          </p>
        ) : (
          <ul className="grid gap-2">
            {active.map((agent) => (
              <li key={agent.id} className="flex items-start gap-3 rounded-xl border px-3 py-2.5 text-sm">
                <MonitorIcon aria-hidden className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                <div className="grid min-w-0 flex-1 gap-1">
                  <p className="flex flex-wrap items-center gap-2 font-medium">
                    {agent.name}
                    <Badge
                      className={cn(
                        agent.online
                          ? 'bg-emerald-200 text-emerald-950 dark:bg-emerald-900/70 dark:text-emerald-100'
                          : 'bg-muted text-foreground',
                      )}
                    >
                      {agent.online ? 'online' : 'offline'}
                    </Badge>
                    {agent.lastVersion && (
                      <span className="text-xs text-muted-foreground">wersja {agent.lastVersion}</span>
                    )}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {agent.machineName ? `Komputer ${agent.machineName}` : 'Jeszcze się nie połączył'}
                    {agent.machineUser && ` · ${agent.machineUser}`}
                    {agent.lastSeenAt && ` · ostatni kontakt ${dateTime.format(new Date(agent.lastSeenAt))}`}
                  </p>
                </div>
                <Button
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Usuń agenta ${agent.name}`}
                  title="Usuń agenta (klucz przestaje działać)"
                  onClick={() => removeAgent(agent)}
                  disabled={remove.isPending}
                >
                  <Trash2Icon aria-hidden />
                </Button>
              </li>
            ))}
          </ul>
        )}
        <Form
          onSubmit={(event) => {
            event.preventDefault()
            submit()
          }}
        >
          <TextField
            label="Nazwa nowego agenta, np. Komputer biuro"
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={100}
          />
          <FormActions>
            <Button type="submit" loading={add.isPending} disabled={!name.trim()}>
              <PlusIcon aria-hidden />
              Dodaj agenta
            </Button>
          </FormActions>
        </Form>
        {removed.length > 0 && (
          <div className="grid gap-1 border-t pt-3">
            <p className="text-xs font-medium text-muted-foreground">Usunięci</p>
            {removed.map((agent) => (
              <div key={agent.id} className="flex items-center justify-between gap-2 text-sm text-muted-foreground">
                <span>{agent.name}</span>
                <Button variant="ghost" size="sm" onClick={() => restore.mutate(agent.id)} disabled={restore.isPending}>
                  Przywróć
                </Button>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  )
}

function NotificationsToggle() {
  const supported = typeof Notification !== 'undefined'
  const [on, setOn] = useState(() => supported && systemNotificationsEnabled() && Notification.permission === 'granted')
  if (!supported) return null
  return (
    <div className="flex items-start justify-between gap-4 border-t pt-3">
      <div className="grid gap-1">
        <h3 className="text-sm font-medium">Powiadomienia systemowe w tej przeglądarce</h3>
        <p className="text-sm text-muted-foreground">
          Gdy karta z aplikacją jest w tle: program sklepu wczytał zmianę cen, agent nie zapisał pliku, agent przestał
          działać.
        </p>
      </div>
      <Switch
        aria-label="Powiadomienia systemowe"
        checked={on}
        onCheckedChange={async (checked) => {
          if (!checked) {
            setSystemNotificationsEnabled(false)
            setOn(false)
            return
          }
          const permission = await Notification.requestPermission()
          if (permission !== 'granted') {
            toast.error('Przeglądarka nie pozwoliła na powiadomienia. Zezwól na nie przy pasku adresu.')
            return
          }
          setSystemNotificationsEnabled(true)
          setOn(true)
        }}
      />
    </div>
  )
}
