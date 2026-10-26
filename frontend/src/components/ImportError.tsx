import { CircleAlertIcon } from 'lucide-react'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { ApiError } from '@/lib/api'

const numberFormat = new Intl.NumberFormat('pl-PL')

/** Odrzucony import pliku: komunikat z ProblemDetail i lista błędnych wierszy (rowErrors / rowErrorCount). */
export function ImportError({ error }: { error: Error }) {
  const message = error instanceof ApiError ? error.message : 'Nie udało się wysłać pliku. Sprawdź połączenie i spróbuj ponownie.'
  const rowErrors = error instanceof ApiError && Array.isArray(error.problem.rowErrors) ? (error.problem.rowErrors as string[]) : []
  const rowErrorCount = error instanceof ApiError && typeof error.problem.rowErrorCount === 'number' ? error.problem.rowErrorCount : 0

  return (
    <Alert variant="destructive" className="animate-in fade-in-0 duration-200 motion-reduce:animate-none">
      <CircleAlertIcon />
      <AlertTitle>{message}</AlertTitle>
      {rowErrors.length > 0 && (
        <AlertDescription>
          <ul className="mt-1 grid gap-0.5 text-foreground/80">
            {rowErrors.map((rowError) => (
              <li key={rowError}>{rowError}</li>
            ))}
          </ul>
          {rowErrorCount > rowErrors.length && (
            <p className="mt-1">i {numberFormat.format(rowErrorCount - rowErrors.length)} kolejnych błędnych wierszy</p>
          )}
        </AlertDescription>
      )}
    </Alert>
  )
}
