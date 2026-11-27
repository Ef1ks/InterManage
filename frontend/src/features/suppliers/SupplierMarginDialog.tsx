import { useMemo, useState } from 'react'
import { PercentIcon, PlusIcon, Trash2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { useSalesNomenclatures } from '@/features/sales/api'
import { type SupplierMarginInput, useSaveSupplierMargin, useSupplierMargins } from '@/features/store-settings/api'
import { ApiError } from '@/lib/api'
import { cn } from '@/lib/utils'

type Props = {
  storeId: string
  supplier: { id: string; name: string; code: string | null }
  canEdit: boolean
  open: boolean
  onOpenChange: (open: boolean) => void
}

const LEVEL_LABELS: Record<number, string> = { 2: 'rejon', 4: 'rodzina', 6: 'podrodzina' }

const parsePercent = (text: string) => {
  const normalized = text.replace(/\s|%/g, '').replace(',', '.')
  if (normalized === '') return null
  const value = Number(normalized)
  return /^\d+(\.\d{1,2})?$/.test(normalized) && value < 100 ? value : undefined
}

const formatPercent = (value: number | null) => (value === null ? '' : String(value).replace('.', ','))

/**
 * Stała marża dostawcy: marża domyślna i wyjątki na rejonach, rodzinach albo podrodzinach (np. 30%, a rejony 01 i 02
 * 25%). We wprowadzaniu danych zastępuje marżę wymaganą na nomenklaturze przy dokumentach tego dostawcy: z niej
 * liczy się cena sugerowana (z zaokrągleniem cen z ustawień) i ostrzeżenie o za małej marży.
 */
export function SupplierMarginDialog(props: Props) {
  return (
    <Dialog open={props.open} onOpenChange={props.onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-xl">{props.open && <Body {...props} />}</DialogContent>
    </Dialog>
  )
}

function Body({ storeId, supplier, canEdit, onOpenChange }: Props) {
  const margins = useSupplierMargins(storeId)
  const nomenclatures = useSalesNomenclatures(storeId)
  const save = useSaveSupplierMargin(storeId)
  const current = margins.data?.find((item) => item.supplierId === supplier.id)
  const [defaultText, setDefaultText] = useState(() => formatPercent(current?.defaultPercent ?? null))
  const [rules, setRules] = useState(() =>
    (current?.rules ?? []).map((rule) => ({ code: rule.code, text: formatPercent(rule.marginPercent) })),
  )
  const [search, setSearch] = useState('')
  // Wartości z serwera dochodzą po otwarciu okna: wpisujemy je raz, póki nikt nic nie zmienił.
  const [loadedFrom, setLoadedFrom] = useState(current)
  if (current !== loadedFrom && loadedFrom === undefined) {
    setLoadedFrom(current)
    setDefaultText(formatPercent(current?.defaultPercent ?? null))
    setRules((current?.rules ?? []).map((rule) => ({ code: rule.code, text: formatPercent(rule.marginPercent) })))
  }

  const names = useMemo(
    () => new Map((nomenclatures.data ?? []).map((item) => [item.code, item.name])),
    [nomenclatures.data],
  )
  const chosen = new Set(rules.map((rule) => rule.code))
  const matches = useMemo(() => {
    const words = search
      .toLowerCase()
      .normalize('NFD')
      .replace(/\p{Diacritic}/gu, '')
      .split(/\s+/)
      .filter(Boolean)
    if (words.length === 0) return []
    return (nomenclatures.data ?? [])
      .filter((item) => {
        const text = `${item.code} ${item.name}`
          .toLowerCase()
          .normalize('NFD')
          .replace(/\p{Diacritic}/gu, '')
        return words.every((word) => text.includes(word))
      })
      .slice(0, 30)
  }, [search, nomenclatures.data])

  const defaultPercent = parsePercent(defaultText)
  const parsedRules = rules.map((rule) => ({ code: rule.code, marginPercent: parsePercent(rule.text) }))
  const invalid = defaultPercent === undefined || parsedRules.some((rule) => rule.marginPercent == null)

  const addRule = (code: string) => {
    if (chosen.has(code)) return
    setRules((list) => [...list, { code, text: '' }].sort((a, b) => a.code.localeCompare(b.code)))
    setSearch('')
  }

  const submit = (input: SupplierMarginInput, message: string) =>
    save.mutate(
      { supplierId: supplier.id, margin: input },
      {
        onSuccess: () => {
          const previous: SupplierMarginInput = {
            defaultPercent: current?.defaultPercent ?? null,
            rules: (current?.rules ?? []).map((rule) => ({ code: rule.code, marginPercent: rule.marginPercent })),
          }
          toast.success(message, {
            action: {
              label: 'Przywróć',
              onClick: () =>
                save.mutate(
                  { supplierId: supplier.id, margin: previous },
                  { onError: () => toast.error('Nie udało się przywrócić marży dostawcy') },
                ),
            },
          })
          onOpenChange(false)
        },
        onError: (error) =>
          toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać marży dostawcy'),
      },
    )

  const onSave = () => {
    if (invalid) return
    submit(
      {
        defaultPercent: defaultPercent ?? null,
        rules: parsedRules.map((rule) => ({ code: rule.code, marginPercent: rule.marginPercent as number })),
      },
      `Zapisano marżę dostawcy ${supplier.code ?? supplier.name}`,
    )
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>Stała marża dostawcy</DialogTitle>
        <DialogDescription>
          {supplier.code ? `${supplier.code} · ` : ''}
          {supplier.name}. Przy dokumentach tego dostawcy cena sugerowana liczy się z tej marży (z zaokrągleniem cen z
          ustawień) zamiast z marży wymaganej na nomenklaturze. Wyjątek na podrodzinie ma pierwszeństwo przed rodziną, a
          rodzina przed rejonem.
        </DialogDescription>
      </DialogHeader>

      <label className="flex items-center justify-between gap-3 rounded-lg bg-muted/60 p-3 text-sm font-medium">
        Marża domyślna
        <span className="flex items-center gap-1.5">
          <Input
            inputMode="decimal"
            value={defaultText}
            placeholder="np. 30"
            disabled={!canEdit}
            aria-invalid={defaultPercent === undefined}
            onChange={(event) => setDefaultText(event.target.value)}
            className="h-9 w-24 text-right tabular-nums"
          />
          <PercentIcon className="size-4 text-muted-foreground" />
        </span>
      </label>
      <p className="-mt-2 text-xs text-muted-foreground">
        Puste pole: artykuły spoza wyjątków liczą się z marży wymaganej na nomenklaturze.
      </p>

      <section className="flex flex-col gap-2" aria-label="Wyjątki na nomenklaturze">
        <h3 className="text-sm font-medium">Wyjątki na nomenklaturze ({rules.length})</h3>
        {rules.length > 0 && (
          <ul className="max-h-[32vh] overflow-y-auto rounded-lg border">
            {rules.map((rule, index) => {
              const value = parsePercent(rule.text)
              return (
                <li key={rule.code} className="flex items-center gap-3 border-b px-3 py-1.5 text-sm last:border-b-0">
                  <span className="w-16 shrink-0 font-mono tabular-nums">{rule.code}</span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate">{names.get(rule.code) ?? 'nieznana pozycja'}</span>
                    <span className="block text-xs text-muted-foreground">{LEVEL_LABELS[rule.code.length]}</span>
                  </span>
                  <Input
                    inputMode="decimal"
                    value={rule.text}
                    placeholder="%"
                    autoFocus={rule.text === '' && index === rules.length - 1}
                    disabled={!canEdit}
                    aria-label={`Marża: ${rule.code}`}
                    aria-invalid={value == null}
                    onChange={(event) =>
                      setRules((list) =>
                        list.map((item) => (item.code === rule.code ? { ...item, text: event.target.value } : item)),
                      )
                    }
                    className="h-8 w-20 text-right tabular-nums"
                  />
                  {canEdit && (
                    <Button
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Usuń wyjątek ${rule.code}`}
                      onClick={() => setRules((list) => list.filter((item) => item.code !== rule.code))}
                    >
                      <Trash2Icon />
                    </Button>
                  )}
                </li>
              )
            })}
          </ul>
        )}
        {canEdit && (
          <div className="flex flex-col gap-1">
            <Input
              value={search}
              placeholder="Dodaj wyjątek: wpisz kod albo nazwę rejonu, rodziny, podrodziny"
              onChange={(event) => setSearch(event.target.value)}
              onKeyDown={(event) => {
                const first = matches.find((item) => !chosen.has(item.code))
                if (event.key === 'Enter' && first) {
                  event.preventDefault()
                  addRule(first.code)
                }
              }}
            />
            {matches.length > 0 && (
              <ul className="max-h-48 overflow-y-auto rounded-lg border">
                {matches.map((item) => (
                  <li key={item.code}>
                    <button
                      type="button"
                      disabled={chosen.has(item.code)}
                      onClick={() => addRule(item.code)}
                      className={cn(
                        'flex w-full items-center gap-3 px-3 py-1.5 text-left text-sm hover:bg-accent disabled:opacity-50',
                        item.code.length === 2 && 'font-medium',
                      )}
                    >
                      <PlusIcon className="size-3.5 shrink-0 text-muted-foreground" />
                      <span className="w-16 shrink-0 font-mono tabular-nums">{item.code}</span>
                      <span className="min-w-0 flex-1 truncate">{item.name}</span>
                      <span className="text-xs text-muted-foreground">{LEVEL_LABELS[item.code.length]}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </section>

      {current?.updatedAt && (
        <p className="text-xs text-muted-foreground">
          Ostatnia zmiana: {new Date(current.updatedAt).toLocaleString('pl-PL')}
          {current.updatedBy ? ` · ${current.updatedBy}` : ''}
        </p>
      )}

      <DialogFooter className="sm:justify-between">
        {canEdit && current ? (
          <Button
            variant="outline"
            disabled={save.isPending}
            onClick={() =>
              submit({ defaultPercent: null, rules: [] }, `Usunięto marżę dostawcy ${supplier.code ?? supplier.name}`)
            }
          >
            <Trash2Icon />
            Usuń marżę dostawcy
          </Button>
        ) : (
          <span />
        )}
        <div className="flex gap-2">
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            {canEdit ? 'Anuluj' : 'Zamknij'}
          </Button>
          {canEdit && (
            <Button loading={save.isPending} disabled={invalid} onClick={onSave}>
              Zapisz
            </Button>
          )}
        </div>
      </DialogFooter>
    </>
  )
}
