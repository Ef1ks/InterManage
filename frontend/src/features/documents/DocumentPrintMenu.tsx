import { useState } from 'react'
import { FileSpreadsheetIcon, FileTextIcon, PrinterIcon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { ApiError, downloadFile, printFile } from '@/lib/api'

type Action = 'print' | 'pdf' | 'xlsx'

const ACTIONS: { action: Action; label: string; hint: string; icon: typeof PrinterIcon }[] = [
  { action: 'print', label: 'Drukuj', hint: 'Otwiera okno drukowania', icon: PrinterIcon },
  { action: 'pdf', label: 'Pobierz PDF', hint: 'Linie, nomenklatura, VAT, podpisy', icon: FileTextIcon },
  { action: 'xlsx', label: 'Pobierz Excel', hint: 'To samo z kwotami jako liczbami', icon: FileSpreadsheetIcon },
]

/** Wydruk dokumentu: okno drukowania, PDF albo Excel. `path` daje adres pliku w danym formacie. */
export function DocumentPrintMenu({
  path,
  number,
  size = 'sm',
}: {
  path: (format: 'pdf' | 'xlsx') => string
  number: string
  size?: 'sm' | 'default'
}) {
  const [pending, setPending] = useState(false)

  const run = async (action: Action) => {
    setPending(true)
    try {
      if (action === 'print') await printFile(path('pdf'))
      else await downloadFile(path(action), `dokument-${number}.${action}`)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się przygotować wydruku')
    } finally {
      setPending(false)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="outline" size={size} loading={pending} />}>
        <PrinterIcon />
        Wydruk
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-60">
        {ACTIONS.map(({ action, label, hint, icon: Icon }) => (
          <DropdownMenuItem key={action} onClick={() => run(action)} className="items-start gap-2.5 py-2">
            <Icon className="mt-0.5" />
            <span className="grid">
              <span>{label}</span>
              <span className="text-xs text-muted-foreground">{hint}</span>
            </span>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
