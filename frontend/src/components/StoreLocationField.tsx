import { useEffect, useState, type ReactNode } from 'react'
import { toast } from 'sonner'

import { TextField } from '@/components/form'
import { ApiError } from '@/lib/api'
import { useSaveStoreLocations, useStoreLocations } from '@/lib/store-locations'

/**
 * Pole jednej ścieżki sklepu (wspólnej dla wszystkich komputerów sklepu). Wpisaną wartość zapisuje po wyjściu z pola
 * albo Enterze; do tego czasu `onDraftChange` daje bieżący tekst (np. do sprawdzenia albo makra).
 */
export function StoreLocationField({
  storeId,
  locationKey,
  label,
  hint,
  error,
  placeholderPath,
  onDraftChange,
  className,
  numeric,
  maxLength,
}: {
  storeId: string
  locationKey: string
  label: string
  hint?: ReactNode
  error?: string
  /** Ścieżka, której użyjemy bez wpisanej (pokazujemy ją w podpowiedzi). */
  placeholderPath?: string | null
  onDraftChange?: (value: string) => void
  className?: string
  numeric?: boolean
  maxLength?: number
}) {
  const locations = useStoreLocations(storeId)
  const save = useSaveStoreLocations(storeId)
  const saved = locations.data?.[locationKey] ?? ''
  const [value, setValue] = useState(saved)

  // Zapis z innego pola albo innego komputera (po odświeżeniu) wraca do pola, o ile nikt w nim nie pisze.
  useEffect(() => {
    setValue(saved)
    onDraftChange?.(saved)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saved])

  const commit = () => {
    if (value.trim() === saved) return
    save.mutate(
      { [locationKey]: value.trim() },
      { onError: (e) => toast.error(e instanceof ApiError ? e.message : 'Nie udało się zapisać ścieżki') },
    )
  }

  return (
    <TextField
      label={label}
      autoComplete="off"
      spellCheck={false}
      inputMode={numeric ? 'numeric' : undefined}
      maxLength={maxLength ?? 500}
      className={className}
      disabled={locations.isPending}
      value={value}
      onChange={(event) => {
        const next = numeric ? event.target.value.replace(/\D/g, '') : event.target.value
        setValue(next)
        onDraftChange?.(next)
      }}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === 'Enter') commit()
      }}
      error={error}
      hint={hint ?? (placeholderPath && !value.trim() ? `Bez wpisu: ${placeholderPath}` : undefined)}
    />
  )
}
