import { useMutation } from '@tanstack/react-query'
import { Building2Icon } from 'lucide-react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { api, ApiError } from '@/lib/api'
import type { GusCompany } from '@/lib/gus'

const nipDigits = (value: string) => value.replace(/[\s-]/g, '')

/**
 * „Pobierz z GUS” obok pola NIP: pobiera nazwę i adres firmy z wyszukiwarki REGON i oddaje je formularzowi
 * (`onFound`), który sam wpisuje je w swoje pola. Niczego nie zapisuje. Aktywny przy 10 cyfrach NIP-u.
 */
export function GusButton({ nip, onFound }: { nip: string; onFound: (company: GusCompany) => void }) {
  const lookup = useMutation({
    mutationFn: (digits: string) => api<GusCompany>(`/gus/companies?nip=${digits}`),
    meta: { invalidates: false },
  })
  const digits = nipDigits(nip)
  const ready = /^\d{10}$/.test(digits)

  const run = () =>
    lookup.mutate(digits, {
      onSuccess: (company) => {
        onFound(company)
        toast.success('Uzupełniono dane z GUS', { description: company.name ?? undefined })
        if (company.endedAt) {
          toast.warning('Firma zakończyła działalność', {
            description: `Według GUS od ${company.endedAt.split('-').reverse().join('.')}.`,
          })
        }
      },
      onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się pobrać danych z GUS'),
    })

  return (
    <Button
      type="button"
      variant="outline"
      size="xl"
      className="shrink-0 px-4"
      disabled={!ready}
      loading={lookup.isPending}
      onClick={run}
      title={ready ? 'Nazwa i adres firmy z rejestru REGON' : 'Wpisz 10 cyfr NIP-u'}
    >
      <Building2Icon />
      Pobierz z GUS
    </Button>
  )
}
