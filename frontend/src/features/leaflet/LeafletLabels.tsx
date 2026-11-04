import { ChevronDownIcon, TagsIcon } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { ApiError, fetchPostedFile, saveBlob } from '@/lib/api'
import { useProtoFolder } from '@/lib/proto-files'
import { ensureFolderAccess, saveToFolder } from '@/lib/saved-folders'
import type { LeafletLine } from '@/lib/types'

import { LABEL_FORMATS, type LabelFormat, labelEans } from './leaflet-rows'

/**
 * Etykiety cenowe z całej gazetki (także pozycji z kodem MEA, bez względu na filtry tabeli): przycisk z wyborem formatu cenówki (PRO_GAZ_FOR_30, PRO_PRO_FOR_67,
 * NAJTANSZE, BIAL_FOR_67) i „Generuj wszystkie etykiety” (plik na każdy format, który ma artykuły). Każdy artykuł
 * z kartoteki raz w formacie, choć gazetka ma go pod kilkoma EAN-ami. Pliki ETIQUETTE z formatem w nazwie idą do
 * folderu plików PROTO, skąd program sklepu sam je wczytuje; bez folderu się pobierają.
 */
export function LeafletLabels({ storeId, lines }: { storeId: string; lines: LeafletLine[] }) {
  const folder = useProtoFolder(storeId)
  const [format, setFormat] = useState<LabelFormat>('PRO_GAZ_FOR_30')
  const [pending, setPending] = useState<'one' | 'all' | null>(null)
  const counts = Object.fromEntries(
    LABEL_FORMATS.map((item) => [item.format, labelEans(lines, item.format)]),
  ) as Record<LabelFormat, ReturnType<typeof labelEans>>
  const withArticles = LABEL_FORMATS.filter((item) => counts[item.format].eans.length > 0)
  const current = LABEL_FORMATS.find((item) => item.format === format) ?? LABEL_FORMATS[0]

  const generate = async (formats: LabelFormat[], mode: 'one' | 'all') => {
    setPending(mode)
    try {
      // Zgoda na zapis w folderze tylko zaraz po kliknięciu, zanim cokolwiek pójdzie do serwera.
      const folderReady = await ensureFolderAccess(folder.key)
      const saved: string[] = []
      for (const chosen of formats) {
        const { eans } = counts[chosen]
        if (eans.length === 0) continue
        const { fileName, blob } = await fetchPostedFile(
          `/stores/${storeId}/data-entry/export/labels`,
          { lines: eans.map((ean) => ({ ean, count: 1 })), format: chosen },
          `ETIQUETTE_${chosen}.traite0`,
        )
        if (folderReady) await saveToFolder(folder.key, fileName, blob)
        else saveBlob(blob, fileName)
        saved.push(`${chosen}: ${eans.length}`)
      }
      const skipped = formats.reduce((sum, chosen) => sum + counts[chosen].withoutProduct, 0)
      toast.success(saved.length === 1 ? 'Zapisano plik etykiet' : `Zapisano pliki etykiet: ${saved.length}`, {
        description: [
          saved.join(', '),
          skipped > 0 ? `bez artykułu z kartoteki pominięto: ${skipped}` : null,
          folderReady ? null : 'pobrane: folder protokołów nie jest wybrany w tej przeglądarce',
        ]
          .filter(Boolean)
          .join(' · '),
        duration: 10_000,
      })
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać pliku etykiet')
    } finally {
      setPending(null)
    }
  }

  return (
    <>
      <div className="flex items-center" role="group" aria-label="Etykiety w formacie">
        <Button
          variant="outline"
          size="sm"
          className="rounded-r-none"
          loading={pending === 'one'}
          disabled={pending !== null || counts[format].eans.length === 0}
          onClick={() => generate([format], 'one')}
          title={current.description}
        >
          <TagsIcon />
          Etykiety {format}
          <span className="text-muted-foreground tabular-nums">({counts[format].eans.length})</span>
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger
            render={
              <Button
                variant="outline"
                size="icon-sm"
                className="-ml-px rounded-l-none"
                aria-label="Wybierz format etykiet"
              />
            }
          >
            <ChevronDownIcon />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-80">
            <DropdownMenuGroup>
              <DropdownMenuLabel>Format cenówki</DropdownMenuLabel>
            </DropdownMenuGroup>
            <DropdownMenuRadioGroup value={format} onValueChange={(value) => setFormat(value as LabelFormat)}>
              {LABEL_FORMATS.map((item) => (
                <DropdownMenuRadioItem key={item.format} value={item.format}>
                  <span className="flex min-w-0 flex-col">
                    <span className="font-medium">
                      {item.format}{' '}
                      <span className="text-muted-foreground tabular-nums">({counts[item.format].eans.length})</span>
                    </span>
                    <span className="text-xs text-muted-foreground">{item.description}</span>
                  </span>
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <Button
        variant="outline"
        size="sm"
        loading={pending === 'all'}
        disabled={pending !== null || withArticles.length === 0}
        onClick={() =>
          generate(
            withArticles.map((item) => item.format),
            'all',
          )
        }
        title="Osobny plik etykiet dla każdego formatu z artykułami"
      >
        <TagsIcon />
        Generuj wszystkie etykiety ({withArticles.length})
      </Button>
    </>
  )
}
