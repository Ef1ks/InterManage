import { ChevronDownIcon, DownloadIcon, PrinterIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

import type { InvoicePrintVariant } from './api'
import { PRINT_VARIANT_LABELS, useInvoiceFiles } from './invoice-files'

/**
 * „Drukuj fakturę” z wyborem wariantu (podstawowy: pozycje, opis i dokumenty WZ; pełna faktura) i pobraniem PDF.
 * W tabeli po wczytaniu faktury i w oknie wczytywania.
 */
export function InvoiceFileActions({
  storeId,
  invoiceId,
  size = 'sm',
}: {
  storeId: string
  invoiceId: string
  size?: 'sm' | 'default'
}) {
  const files = useInvoiceFiles(storeId)
  return (
    <div className="flex items-center" role="group" aria-label="Plik faktury">
      <Button
        variant="outline"
        size={size}
        className="rounded-r-none"
        loading={files.busy === 'print'}
        onClick={() => files.print(invoiceId)}
        title={
          files.variant === 'BASIC'
            ? 'Drukuje pozycje faktury, opis i numery dokumentów WZ'
            : 'Drukuje pełną fakturę z KSeF'
        }
      >
        <PrinterIcon />
        Drukuj fakturę
        <span className="text-muted-foreground">({PRINT_VARIANT_LABELS[files.variant]})</span>
      </Button>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={
            <Button
              variant="outline"
              size={size === 'sm' ? 'icon-sm' : 'icon'}
              className="-ml-px rounded-l-none"
              aria-label="Wariant wydruku i pobranie faktury"
              loading={files.busy === 'download'}
            />
          }
        >
          <ChevronDownIcon />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-72">
          <DropdownMenuRadioGroup
            value={files.variant}
            onValueChange={(value) => files.setVariant(value as InvoicePrintVariant)}
          >
            <DropdownMenuRadioItem value="BASIC">Podstawowy: pozycje, opis, dokumenty WZ</DropdownMenuRadioItem>
            <DropdownMenuRadioItem value="FULL">Pełna faktura</DropdownMenuRadioItem>
          </DropdownMenuRadioGroup>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => files.download(invoiceId)}>
            <DownloadIcon />
            Pobierz PDF ({PRINT_VARIANT_LABELS[files.variant]})
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}
