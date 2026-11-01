import { useMemo, useState } from 'react'
import { ChevronRightIcon, CircleCheckIcon, DownloadIcon, FileWarningIcon } from 'lucide-react'
import { toast } from 'sonner'

import { FileDropZone } from '@/components/FileDropZone'
import { HowTo } from '@/components/HowTo'
import { ImportError } from '@/components/ImportError'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import {
  nomenclatureMarginsPath,
  useImportNomenclatureMargins,
  useNomenclatureMargins,
  useSaveNomenclatureMargins,
} from '@/features/store-settings/api'
import { ApiError, downloadFile } from '@/lib/api'
import type { NomenclatureMargin, NomenclatureMarginImport } from '@/lib/types'
import { cn } from '@/lib/utils'

const ACCEPTED_EXTENSIONS = ['.xlsx', '.xlsm']
const numberFormat = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 2, useGrouping: false })
const toText = (value: number | null) => (value == null ? '' : numberFormat.format(value))

const LEVEL_LABELS = { REGION: 'rejonu', FAMILY: 'rodziny', SUBFAMILY: 'podrodziny' } as const
const LEVEL_INDENT = { REGION: 'pl-4', FAMILY: 'pl-10', SUBFAMILY: 'pl-16' } as const

/** Tekst z pola na liczbę: 20 = 20%, puste = bez progu na tym poziomie; {@code undefined} = błędna wartość. */
function parse(text: string): number | null | undefined {
  const trimmed = text.trim().replace(',', '.').replace('%', '')
  if (trimmed === '') return null
  if (!/^\d{1,2}(\.\d{1,2})?$/.test(trimmed)) return undefined
  return Number(trimmed)
}

/**
 * Marża wymagana na nomenklaturze: ustawia się ją na rejonie (obowiązuje w całym), rodzinie albo podrodzinie;
 * wygrywa najbardziej szczegółowa. Wprowadzanie danych czerwieni marżę artykułu poniżej progu i podaje cenę
 * sugerowaną. Marże wpisuje się w drzewie albo w pliku Excel ze wszystkimi pozycjami nomenklatury.
 */
export function NomenclatureMargins({ storeId }: { storeId: string }) {
  const { data, isPending, isError, refetch } = useNomenclatureMargins(storeId)
  const save = useSaveNomenclatureMargins(storeId)
  const upload = useImportNomenclatureMargins(storeId)
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [rejectedFile, setRejectedFile] = useState<string | null>(null)
  const [downloading, setDownloading] = useState(false)

  const items = useMemo(() => data ?? [], [data])
  const byCode = useMemo(() => new Map(items.map((item) => [item.code, item])), [items])

  const textOf = (item: NomenclatureMargin) => draft[item.code] ?? toText(item.marginPercent)
  /** Marża ustawiona na pozycji z uwzględnieniem niezapisanych zmian (błędna wartość = brak). */
  const ownOf = (code: string) => {
    const item = byCode.get(code)
    if (!item) return null
    return code in draft ? (parse(draft[code]) ?? null) : item.marginPercent
  }
  /** Obowiązująca na pozycji: własna albo pierwsza wyżej; z kodem, skąd pochodzi. */
  const effectiveOf = (code: string) => {
    for (let length = code.length; length >= 2; length -= 2) {
      const value = ownOf(code.slice(0, length))
      if (value !== null) return { value, from: code.slice(0, length) }
    }
    return null
  }

  const changed = items.filter((item) => item.code in draft && parse(draft[item.code]) !== item.marginPercent)
  const changedCodes = new Set(changed.map((item) => item.code))
  const invalid = changed.filter((item) => parse(draft[item.code]) === undefined)
  const setCount = items.filter((item) => ownOf(item.code) !== null).length

  const visible = items.filter((item) => {
    if (item.level === 'REGION') return true
    if (!expanded.has(item.code.slice(0, 2))) return false
    return item.level === 'FAMILY' || expanded.has(item.code.slice(0, 4))
  })
  const toggle = (code: string) =>
    setExpanded((current) => {
      const next = new Set(current)
      if (next.has(code)) {
        // Zwinięcie zwija też poziomy pod spodem.
        for (const open of current) if (open.startsWith(code)) next.delete(open)
      } else {
        next.add(code)
      }
      return next
    })
  /** Ile pozycji pod kodem ma własną marżę (widać przy zwiniętej grupie). */
  const setBelow = (code: string) =>
    items.filter((item) => item.code.length > code.length && item.code.startsWith(code) && ownOf(item.code) !== null)
      .length

  const submit = () =>
    save.mutate(
      changed.map((item) => ({ code: item.code, marginPercent: parse(draft[item.code]) ?? null })),
      {
        onSuccess: () => {
          setDraft({})
          toast.success('Zapisano marże wymagane')
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać marż'),
      },
    )

  const downloadTemplate = async () => {
    setDownloading(true)
    try {
      await downloadFile(`${nomenclatureMarginsPath(storeId)}/template`, 'marze_wymagane.xlsx')
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać pliku')
    } finally {
      setDownloading(false)
    }
  }

  if (isError) {
    return (
      <Card className="items-center px-6 py-8 text-center">
        <p className="font-medium">Nie udało się wczytać marż</p>
        <Button variant="outline" onClick={() => refetch()}>
          Spróbuj ponownie
        </Button>
      </Card>
    )
  }

  return (
    <div className="grid gap-4">
      <Card className="gap-3 p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h3 className="font-medium">Plik Excel</h3>
            <p className="text-sm text-muted-foreground">
              Wszystkie pozycje nomenklatury z kolumną „Marża %” do uzupełnienia.
            </p>
          </div>
          <Button variant="outline" loading={downloading} disabled={items.length === 0} onClick={downloadTemplate}>
            <DownloadIcon />
            Pobierz plik do uzupełnienia
          </Button>
        </div>
        <HowTo
          id="nomenclature-margins-file"
          title="Jak wgrać marże z Excela"
          steps={[
            'Pobierz plik do uzupełnienia: są w nim wszystkie rejony, rodziny i podrodziny z zapisanymi marżami.',
            'W kolumnie „Marża %” wpisz zwykłe liczby: 20 to 20%. Marża na rejonie obowiązuje w całym rejonie, na rodzinie albo podrodzinie zastępuje tę z wyższego poziomu. Puste pole = bez progu na tym poziomie.',
            'Nie usuwaj wierszy: plik musi mieć wszystkie pozycje nomenklatury. Wgrany plik zastępuje wszystkie marże naraz.',
          ]}
        />
        <FileDropZone
          className="min-h-24 py-4"
          accept={ACCEPTED_EXTENSIONS}
          pendingFile={upload.isPending ? upload.variables : undefined}
          onFile={(file) => {
            setRejectedFile(null)
            upload.mutate(file, { onSuccess: () => setDraft({}) })
          }}
          onReject={(fileName) => {
            upload.reset()
            setRejectedFile(fileName)
          }}
          title="Przeciągnij uzupełniony plik albo kliknij"
          hint="Plik .xlsx z kolumnami Kod i Marża %."
        />
        <div aria-live="polite">
          {rejectedFile && (
            <Alert variant="destructive">
              <FileWarningIcon />
              <AlertTitle>Plik {rejectedFile} ma złe rozszerzenie</AlertTitle>
              <AlertDescription>Wgraj plik zapisany jako .xlsx lub .xlsm.</AlertDescription>
            </Alert>
          )}
          {upload.isError && <ImportError error={upload.error} />}
          {upload.isSuccess && <ImportResult result={upload.data} />}
        </div>
      </Card>

      <Card className="gap-0 py-0">
        <div className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3">
          <p className="text-sm text-muted-foreground tabular-nums">
            {isPending ? ' ' : setCount ? `Marża ustawiona na ${setCount} z ${items.length} pozycji` : 'Marże nie są jeszcze ustawione'}
          </p>
          {expanded.size > 0 && (
            <Button variant="ghost" size="sm" onClick={() => setExpanded(new Set())}>
              Zwiń wszystko
            </Button>
          )}
        </div>
        {isPending ? (
          <div className="p-4">
            <div aria-hidden className="h-40 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
          </div>
        ) : items.length === 0 ? (
          <p className="px-4 py-8 text-center text-sm text-muted-foreground">
            Sklep nie ma jeszcze nomenklatury. Wgraj ją w Imporcie danych.
          </p>
        ) : (
          <div role="table" className="tabular-nums">
            <div role="row" className="flex items-end gap-3 border-b px-4 py-2 text-sm font-medium text-muted-foreground">
              <span role="columnheader" className="flex-1">
                Nomenklatura
              </span>
              <span role="columnheader" className="w-24 text-right">
                Marża %
              </span>
              <span role="columnheader" className="w-32 text-right">
                Obowiązuje
              </span>
            </div>
            {visible.map((item) => {
              const text = textOf(item)
              const effective = effectiveOf(item.code)
              const inherited = effective && effective.from !== item.code ? effective : null
              const open = expanded.has(item.code)
              const below = item.level !== 'SUBFAMILY' && !open ? setBelow(item.code) : 0
              const label = `${item.code} ${item.name}`
              return (
                <div
                  key={item.code}
                  role="row"
                  className={cn(
                    'flex items-center gap-3 border-b py-1.5 pr-4 last:border-b-0',
                    LEVEL_INDENT[item.level],
                    item.level === 'REGION' && 'bg-muted/50 font-medium',
                    changedCodes.has(item.code) && 'bg-amber-200 dark:bg-amber-900',
                  )}
                >
                  <span role="cell" className="flex min-w-0 flex-1 items-center gap-1.5">
                    {item.level === 'SUBFAMILY' ? (
                      <span aria-hidden className="size-6 shrink-0" />
                    ) : (
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        className="size-6 shrink-0"
                        aria-expanded={open}
                        aria-label={`${open ? 'Zwiń' : 'Rozwiń'} ${label}`}
                        onClick={() => toggle(item.code)}
                      >
                        <ChevronRightIcon className={cn('transition-transform duration-150', open && 'rotate-90')} />
                      </Button>
                    )}
                    <span className="shrink-0 text-xs text-muted-foreground">{item.code}</span>
                    <span className="truncate" title={item.name}>
                      {item.name}
                    </span>
                    {below > 0 && (
                      <span className="shrink-0 text-xs font-normal text-muted-foreground">· niżej ustawione: {below}</span>
                    )}
                  </span>
                  {/* Siatka do wpisywania liczb: pola bez pływającej etykiety, opisane nagłówkiem kolumny. */}
                  <span role="cell" className="w-24">
                    <Input
                      inputMode="decimal"
                      aria-label={`Marża % ${label}`}
                      aria-invalid={parse(text) === undefined}
                      value={text}
                      onChange={(event) => setDraft((current) => ({ ...current, [item.code]: event.target.value }))}
                      placeholder={inherited ? numberFormat.format(inherited.value) : '–'}
                      className="ml-auto h-8 w-20 text-right font-normal"
                    />
                  </span>
                  <span role="cell" className="w-32 text-right text-sm font-normal">
                    {effective ? (
                      <>
                        {numberFormat.format(effective.value)}%
                        {inherited && (
                          <span className="block text-xs text-muted-foreground">
                            z {LEVEL_LABELS[byCode.get(inherited.from)?.level ?? 'REGION']} {inherited.from}
                          </span>
                        )}
                      </>
                    ) : (
                      <span className="text-muted-foreground">bez progu</span>
                    )}
                  </span>
                </div>
              )
            })}
          </div>
        )}
        <div className="sticky bottom-0 flex flex-wrap items-center justify-end gap-3 border-t bg-card px-4 py-3">
          {invalid.length > 0 ? (
            <p className="mr-auto text-sm text-destructive">
              Popraw wartości: liczba od 0 do 99,99 z najwyżej dwoma miejscami po przecinku (20 = 20%).
            </p>
          ) : changed.length > 0 ? (
            <p className="mr-auto text-sm text-muted-foreground">Zmienione pozycje: {changed.length}</p>
          ) : null}
          {Object.keys(draft).length > 0 && (
            <Button variant="ghost" size="lg" onClick={() => setDraft({})}>
              Anuluj
            </Button>
          )}
          <Button size="lg" loading={save.isPending} disabled={changed.length === 0 || invalid.length > 0} onClick={submit}>
            Zapisz marże
          </Button>
        </div>
      </Card>
    </div>
  )
}

function ImportResult({ result }: { result: NomenclatureMarginImport }) {
  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>Wgrano marże z pliku</AlertTitle>
      <AlertDescription className="tabular-nums">
        Zmienione pozycje: {result.changedCount} · z ustawioną marżą: {result.withMarginCount}
      </AlertDescription>
    </Alert>
  )
}
