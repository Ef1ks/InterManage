import { useEffect, useRef } from 'react'
import { Loader2Icon } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router'

import { FormAlert } from '@/components/form'
import { AuthLayout } from '@/components/layout/AuthLayout'
import { Button } from '@/components/ui/button'
import { useActivateAccount } from '@/features/auth/api'
import { ApiError } from '@/lib/api'

/** Cel linku z maila: /aktywacja?token=… Zużywa token, loguje i prowadzi do ustawienia hasła. */
export function ActivationPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const activate = useActivateAccount()
  const navigate = useNavigate()
  // Token jest jednorazowy, a StrictMode uruchamia efekty dwa razy - wysyłamy go tylko raz.
  const sent = useRef(false)

  useEffect(() => {
    if (!token || sent.current) return
    sent.current = true
    activate.mutate(token, { onSuccess: () => navigate('/ustaw-haslo', { replace: true }) })
  }, [token, activate, navigate])

  if (!token || activate.isError) {
    const message = !token
      ? 'Ten link jest niekompletny. Otwórz go jeszcze raz z wiadomości e-mail.'
      : activate.error instanceof ApiError
        ? activate.error.message
        : 'Nie udało się połączyć z serwerem. Spróbuj ponownie za chwilę.'
    return (
      <AuthLayout title="Nie udało się aktywować konta">
        <div className="grid gap-4">
          <FormAlert>{message}</FormAlert>
          <Button size="xl" variant="outline" render={<Link to="/logowanie" />} className="w-full">
            Przejdź do logowania
          </Button>
        </div>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title="Aktywujemy Twoje konto" description="To potrwa tylko chwilę.">
      <div className="flex items-center gap-3 text-sm text-muted-foreground" role="status">
        <Loader2Icon className="size-4 animate-spin" aria-hidden />
        Sprawdzamy link aktywacyjny…
      </div>
    </AuthLayout>
  )
}
