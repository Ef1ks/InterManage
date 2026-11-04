import { PrinterIcon } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { formatDocumentDate, formatMoney } from '@/features/documents/format'
import type { LeafletLine } from '@/lib/types'

import { promoPrice } from './leaflet-rows'

type Props = {
  title: string
  lines: LeafletLine[]
  open: boolean
  onOpenChange: (open: boolean) => void
}

const escape = (text: string) =>
  text.replace(/[&<>"]/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[char] ?? char)

/** Lista do wydruku jako osobny dokument w ukrytej ramce (drukuje się sama lista, bez aplikacji). */
function printList(title: string, lines: LeafletLine[]) {
  const rows = lines
    .map(
      (
        line,
        index,
      ) => `<tr><td>${index + 1}</td><td class="box"></td><td>${escape(line.product?.catalogEan ?? line.ean)}</td>
<td>${escape(line.product?.name ?? line.name)}<div class="muted">${escape(line.name)}</div></td>
<td>${escape(line.product?.brand ?? line.brand ?? '')}</td><td class="num">${escape(formatMoney(promoPrice(line)))}</td>
<td>${formatDocumentDate(line.dateFrom)} – ${formatDocumentDate(line.dateTo)}</td></tr>`,
    )
    .join('')
  const html = `<!doctype html><html lang="pl"><head><meta charset="utf-8"><title>${escape(title)}</title><style>
body{font:11px/1.35 system-ui,sans-serif;margin:16px;color:#000}h1{font-size:15px;margin:0 0 8px}
table{width:100%;border-collapse:collapse}th,td{border:1px solid #999;padding:3px 5px;text-align:left;vertical-align:top}
th{background:#eee}.num{text-align:right;white-space:nowrap}.muted{color:#555;font-size:9px}.box{width:18px}
tr{break-inside:avoid}</style></head><body><h1>${escape(title)}</h1><table><thead><tr><th>Lp.</th><th>✓</th><th>EAN</th>
<th>Artykuł</th><th>Marka</th><th>Cena</th><th>Okres</th></tr></thead><tbody>${rows}</tbody></table></body></html>`
  const frame = document.createElement('iframe')
  frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden'
  frame.srcdoc = html
  frame.onload = () => {
    frame.contentWindow?.focus()
    frame.contentWindow?.print()
    setTimeout(() => frame.remove(), 60_000)
  }
  document.body.append(frame)
}

/**
 * Artykuły z gazetki, przy których na półce trzeba położyć karteczkę „Extra Znaczek”: lista do wydruku, żeby
 * pracownik wiedział, co oznaczyć (każdy artykuł raz, z pustym polem na odhaczenie).
 */
export function ExtraMarksDialog({ title, lines, open, onOpenChange }: Props) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="gap-5 p-6 sm:max-w-4xl">
        <DialogHeader>
          <DialogTitle>Karteczki „Extra Znaczek”</DialogTitle>
          <DialogDescription>
            Artykuły z oznaczeniem „Karteczki do produktu Extra Znaczek” ({lines.length}). Wydrukuj listę i oznacz je na
            półce.
          </DialogDescription>
        </DialogHeader>

        {lines.length === 0 ? (
          <p className="text-sm text-muted-foreground">W tej gazetce nie ma artykułów z karteczką „Extra Znaczek”.</p>
        ) : (
          <div className="max-h-[60vh] overflow-auto rounded-lg border">
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-muted text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">EAN</th>
                  <th className="px-3 py-2 font-medium">Artykuł</th>
                  <th className="px-3 py-2 font-medium">Marka</th>
                  <th className="px-3 py-2 text-right font-medium">Cena</th>
                  <th className="px-3 py-2 font-medium">Okres</th>
                </tr>
              </thead>
              <tbody>
                {lines.map((line) => (
                  <tr key={line.id} className="border-t align-top">
                    <td className="px-3 py-1.5 tabular-nums">{line.product?.catalogEan ?? line.ean}</td>
                    <td className="px-3 py-1.5">
                      {line.product?.name ?? (
                        <span className="text-amber-900 dark:text-amber-200">brak w kartotece</span>
                      )}
                      <div className="text-xs text-muted-foreground">{line.name}</div>
                    </td>
                    <td className="px-3 py-1.5">{line.product?.brand ?? line.brand}</td>
                    <td className="px-3 py-1.5 text-right tabular-nums">{formatMoney(promoPrice(line))}</td>
                    <td className="px-3 py-1.5 whitespace-nowrap tabular-nums">
                      {formatDocumentDate(line.dateFrom)} – {formatDocumentDate(line.dateTo)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <Button size="xl" disabled={lines.length === 0} onClick={() => printList(title, lines)}>
          <PrinterIcon />
          Drukuj listę
        </Button>
      </DialogContent>
    </Dialog>
  )
}
