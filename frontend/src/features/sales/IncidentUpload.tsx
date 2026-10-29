import { useState } from 'react'
import { CircleCheckIcon, FileWarningIcon } from 'lucide-react'

import { FileDropZone } from '@/components/FileDropZone'
import { ImportError } from '@/components/ImportError'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Card } from '@/components/ui/card'
import { useCan } from '@/features/auth/api'
import { countFormat, formatDocumentDate } from '@/features/documents/format'
import { useImportIncidents, useIncidents } from '@/features/sales/api'
import type { IncidentImport } from '@/lib/types'

const ACCEPTED_EXTENSIONS = ['.xlsx', '.xlsm']
const dateTimeFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short', timeStyle: 'short' })

/**
 * Plik incydentów zamówień do bazy (artykuły, których baza nie dowiozła, z przyczyną). Dzień incydentu jest w nazwie
 * pliku (`10023_RRMMDD_…_INCYDENTY.xlsx`), więc nazwy z systemu nie zmieniamy. Wgrywa osoba z edycją sprzedaży.
 */
export function IncidentUpload({ storeId, incidentDays }: { storeId: string; incidentDays: number }) {
  const canEdit = useCan('SALES', 'EDIT')
  const overview = useIncidents(storeId)
  const upload = useImportIncidents(storeId)
  const [rejectedFile, setRejectedFile] = useState<string | null>(null)
  const latest = overview.data?.imports[0]

  return (
    <Card className="mb-4 gap-3 p-4">
      <div>
        <h3 className="font-medium">Incydenty zamówień do bazy</h3>
        <p className="text-sm text-muted-foreground tabular-nums">
          {!overview.data
            ? ' '
            : overview.data.lastIncidentDate
              ? `Najnowszy dzień: ${formatDocumentDate(overview.data.lastIncidentDate)}${latest ? ` · wgrany ${dateTimeFormat.format(new Date(latest.importedAt))}${latest.importedBy ? ` przez ${latest.importedBy}` : ''}` : ''}`
              : 'Nie wgrano jeszcze żadnego pliku incydentów'}
        </p>
        <p className="mt-1 max-w-3xl text-sm text-muted-foreground">
          Przyjęcie od innego dostawcy do {incidentDays} dni po incydencie artykułu jest tutaj, a nie w weryfikacji:
          baza go wtedy nie dowiozła. Późniejsze przyjęcie wraca do weryfikacji, chyba że artykuł był w nowszym
          incydencie.
        </p>
      </div>

      {canEdit && (
        <>
          <FileDropZone
            className="min-h-24 py-4"
            accept={ACCEPTED_EXTENSIONS}
            pendingFile={upload.isPending ? upload.variables : undefined}
            onFile={(file) => {
              setRejectedFile(null)
              upload.mutate(file)
            }}
            onReject={(fileName) => {
              upload.reset()
              setRejectedFile(fileName)
            }}
            title="Przeciągnij plik INCYDENTY albo kliknij"
            hint="Plik .xlsx z nazwą z systemu, np. 10023_261028_261028_W_NS_INCYDENTY.xlsx: dzień incydentu jest w nazwie."
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
        </>
      )}
    </Card>
  )
}

function ImportResult({ result }: { result: IncidentImport }) {
  return (
    <Alert className="animate-in fade-in-0 slide-in-from-top-1 duration-200 motion-reduce:animate-none">
      <CircleCheckIcon className="text-emerald-600 dark:text-emerald-400" />
      <AlertTitle>
        Wgrano incydenty z {formatDocumentDate(result.incidentDate)}: {countFormat.format(result.totalRows)} artykułów
      </AlertTitle>
      <AlertDescription className="tabular-nums">
        {result.addedCount === result.totalRows
          ? 'Wszystkie nowe dla tego dnia.'
          : `Nowe dla tego dnia: ${countFormat.format(result.addedCount)}, pozostałe zaktualizowane.`}
      </AlertDescription>
    </Alert>
  )
}
