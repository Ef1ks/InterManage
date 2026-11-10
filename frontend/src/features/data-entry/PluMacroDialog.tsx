import { useState } from 'react'
import { ClipboardCopyIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

import { buildPluMacro, type PluSection } from './mercalys-macro'
import type { PluItem } from './plu-macro'

/** Wybrana sekcja wagi i format etykiety każdej sekcji: ustawienie stanowiska, zapamiętane w przeglądarce. */
const SETTINGS_KEY = 'intermanage.pluMacro'
const SECTIONS: PluSection[] = ['1', '2']

type Settings = { section: PluSection; formats: Record<PluSection, string> }

const DEFAULT_SETTINGS: Settings = { section: '1', formats: { '1': '83', '2': '' } }

function readSettings(): Settings {
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? 'null') as Partial<Settings> | null
    if (saved) {
      return {
        section: saved.section === '2' ? '2' : '1',
        formats: { ...DEFAULT_SETTINGS.formats, ...saved.formats },
      }
    }
  } catch {
    // brak dostępu do localStorage: domyślne ustawienia
  }
  return DEFAULT_SETTINGS
}

function writeSettings(settings: Settings) {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings))
  } catch {
    // ustawienie zostaje tylko do zamknięcia okna
  }
}

type Props = {
  open: boolean
  onOpenChange: (open: boolean) => void
  items: PluItem[]
}

/**
 * Makro kodów PLU: każdy nowy kod 24 musi mieć w Mercalys kod PLU z sekcji wagi, komentarz „KOD: <PLU>” i format etykiety.
 * Na start zaznaczone są nowe artykuły; makro przechodzi je po kolei w jednej pętli.
 */
export function PluMacroDialog({ open, onOpenChange, items }: Props) {
  const [settings, setSettings] = useState(readSettings)
  const [excluded, setExcluded] = useState<Set<string>>(new Set())
  const [wasOpen, setWasOpen] = useState(false)

  // Każde otwarcie: zaznaczone nowe artykuły.
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) setExcluded(new Set(items.filter((item) => !item.isNew).map((item) => item.ean)))
  }

  const chosen = items.filter((item) => !excluded.has(item.ean))
  const format = settings.formats[settings.section].trim()
  const formatError = /^\d{1,2}$/.test(format) ? null : 'Wpisz format etykiety tej sekcji (1-2 cyfry)'

  const update = (next: Settings) => {
    setSettings(next)
    writeSettings(next)
  }

  const toggle = (ean: string) =>
    setExcluded((current) => {
      const next = new Set(current)
      if (next.has(ean)) next.delete(ean)
      else next.add(ean)
      return next
    })

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(
        buildPluMacro({ eans: chosen.map((item) => item.ean), section: settings.section, labelFormat: format }),
      )
    } catch {
      toast.error('Nie udało się skopiować makra do schowka')
      return
    }
    toast.success(`Makro w schowku: ${chosen.length} kod${chosen.length === 1 ? '' : 'ów'} PLU`, {
      description: `Naciśnij Alt+E. Sekcja wagi ${settings.section}, format etykiety ${format}. Potrzebny odtwarzacz od wersji 41.`,
    })
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-4 p-6 sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Kody PLU dla wagi</DialogTitle>
          <DialogDescription>
            Każdy nowy kod 24 musi mieć kod PLU. Makro po kolei otwiera kartę artykułu, nadaje kod PLU z sekcji wagi,
            wpisuje „KOD: …” w tekście dowolnym i ustawia format etykiety. Najpierw wczytaj w Mercalys plik nowych
            artykułów.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-wrap items-end gap-4">
          <div className="grid gap-1 text-sm">
            <span className="text-muted-foreground">Sekcja wagi</span>
            <div className="flex gap-1">
              {SECTIONS.map((section) => (
                <Button
                  key={section}
                  variant={settings.section === section ? 'default' : 'outline'}
                  className="w-12"
                  onClick={() => update({ ...settings, section })}
                >
                  {section}
                </Button>
              ))}
            </div>
          </div>
          {SECTIONS.map((section) => (
            <label key={section} className="grid gap-1 text-sm">
              <span
                className={cn('text-muted-foreground', settings.section === section && 'font-medium text-foreground')}
              >
                Format etykiety, sekcja {section}
              </span>
              <Input
                inputMode="numeric"
                maxLength={2}
                className="w-24 tabular-nums dark:bg-input/30"
                value={settings.formats[section]}
                onChange={(event) =>
                  update({
                    ...settings,
                    formats: { ...settings.formats, [section]: event.target.value.replace(/\D/g, '') },
                  })
                }
              />
            </label>
          ))}
        </div>

        {items.length === 0 ? (
          <p className="text-sm text-muted-foreground">W tabeli nie ma kodów 24 z pełnym EAN-em.</p>
        ) : (
          <div className="grid max-h-[50vh] gap-0.5 overflow-y-auto rounded-lg border p-2">
            {items.map((item) => (
              <label
                key={item.ean}
                className={cn(
                  'flex cursor-pointer items-center gap-3 rounded-md px-2 py-1 text-sm hover:bg-muted',
                  !excluded.has(item.ean) && 'bg-primary/10',
                )}
              >
                <input
                  type="checkbox"
                  className="size-4 accent-primary"
                  checked={!excluded.has(item.ean)}
                  onChange={() => toggle(item.ean)}
                />
                <span className="tabular-nums">{item.ean}</span>
                <span className="min-w-0 flex-1 truncate">{item.name}</span>
                {item.isNew && (
                  <span className="rounded bg-violet-200 px-1.5 text-xs font-medium text-violet-950 dark:bg-violet-900 dark:text-violet-50">
                    nowy
                  </span>
                )}
              </label>
            ))}
          </div>
        )}

        <div className="flex flex-wrap items-center justify-end gap-3">
          {formatError && <p className="mr-auto text-sm text-destructive">{formatError}</p>}
          <Button onClick={() => void copy()} disabled={chosen.length === 0 || formatError !== null}>
            <ClipboardCopyIcon />
            Skopiuj makro{chosen.length > 0 && ` (${chosen.length})`}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
