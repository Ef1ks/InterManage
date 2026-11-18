import { useState } from 'react'
import { toast } from 'sonner'

import { Form, FormActions, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  MERCALYS_LOGIN_PLAYER_VERSION,
  getMercalysPassword,
  mercalysPasswordError,
  setMercalysPassword,
} from '@/lib/mercalys-login'

/**
 * Hasło do Mercalys w tej przeglądarce. Z nim makro przy wyłączonym Mercalys samo go uruchamia i loguje się;
 * bez niego odtwarzacz uruchamia program i czeka na ręczne logowanie.
 */
export function MercalysLoginSection() {
  const [saved, setSaved] = useState(getMercalysPassword)
  const [password, setPassword] = useState(saved)
  const error = mercalysPasswordError(password)

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (error) return
    const previous = saved
    setMercalysPassword(password)
    setSaved(password)
    toast.success(password ? 'Zapisano hasło do Mercalys na tym komputerze' : 'Usunięto hasło do Mercalys', {
      action: {
        label: 'Przywróć',
        onClick: () => {
          setMercalysPassword(previous)
          setSaved(previous)
          setPassword(previous)
        },
      },
    })
  }

  return (
    <section className="mt-8 flex flex-col gap-3">
      <div>
        <h2 className="text-lg font-semibold">Logowanie do Mercalys</h2>
        <p className="text-sm text-muted-foreground">
          Gdy Mercalys jest wyłączony, makro samo go uruchomi i zaloguje się tym hasłem (Intermanage Makra od wersji{' '}
          {MERCALYS_LOGIN_PLAYER_VERSION}). Bez hasła uruchomi program i poczeka, aż zalogujesz się sam. Hasło zostaje
          tylko w tej przeglądarce.
        </p>
      </div>
      <Card className="p-5">
        <Form onSubmit={submit} error={null}>
          <TextField
            label="Hasło do Mercalys"
            type="password"
            noSave
            value={password}
            error={error ?? undefined}
            onChange={(event) => setPassword(event.target.value)}
          />
          <FormActions>
            <Button type="submit" disabled={!!error || password === saved}>
              Zapisz
            </Button>
          </FormActions>
        </Form>
      </Card>
    </section>
  )
}
