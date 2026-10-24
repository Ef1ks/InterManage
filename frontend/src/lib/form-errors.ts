import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'

import { ApiError } from '@/lib/api'

/**
 * Przenosi błędy walidacji z backendu na pola formularza.
 * Zwraca komunikat ogólny do pokazania nad formularzem albo null, gdy wszystko trafiło do pól.
 * `aliases` mapuje nazwę pola z backendu na pole formularza, gdy się różnią.
 */
export function applyServerErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly Path<T>[],
  aliases: Record<string, Path<T>> = {},
): string | null {
  if (!(error instanceof ApiError)) {
    return 'Nie udało się połączyć z serwerem.'
  }
  let unmatched = false
  for (const [serverField, message] of Object.entries(error.fieldErrors)) {
    const field = aliases[serverField] ?? serverField
    if ((fields as readonly string[]).includes(field)) {
      setError(field as Path<T>, { type: 'server', message })
    } else {
      unmatched = true
    }
  }
  const hasFieldErrors = Object.keys(error.fieldErrors).length > 0
  return !hasFieldErrors || unmatched ? error.message : null
}
