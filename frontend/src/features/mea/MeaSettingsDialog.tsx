import { useState } from 'react'
import { toast } from 'sonner'

import { Form, FormActions, FormNote, TextField } from '@/components/form'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { ApiError } from '@/lib/api'
import type { MeaSettings } from '@/lib/types'

import { useSaveMeaSettings } from './api'

/**
 * Klucze API portalu Grupy Muszkieterów dla sklepu („Moje konto → API”). KEY loguje i wystarcza do czytania list;
 * SECRET potrzebny dopiero do wysyłki zamówienia. Zapisane klucze nie wracają do przeglądarki, pole pokazuje tylko,
 * czy są ustawione.
 */
export function MeaSettingsDialog({
  storeId,
  settings,
  open,
  onOpenChange,
}: {
  storeId: string
  settings: MeaSettings | undefined
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const save = useSaveMeaSettings(storeId)
  const [apiKey, setApiKey] = useState('')
  const [apiSecret, setApiSecret] = useState('')

  const submit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!apiKey.trim() && !settings?.configured) {
      toast.error('Podaj klucz API (KEY) z portalu')
      return
    }
    save.mutate(
      { apiKey: apiKey.trim(), apiSecret: apiSecret.trim() },
      {
        onSuccess: () => {
          setApiKey('')
          setApiSecret('')
          onOpenChange(false)
          toast.success('Zapisano klucze API')
        },
        onError: (error) => toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać kluczy API'),
      },
    )
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Klucze API MEA</DialogTitle>
          <DialogDescription>
            Z portalu Grupy Muszkieterów: Moje konto → API. KEY i SECRET są osobne dla każdego sklepu.
          </DialogDescription>
        </DialogHeader>
        <Form onSubmit={submit} error={null}>
          <TextField
            label={settings?.configured ? 'Nowy KEY (puste = bez zmian)' : 'KEY'}
            value={apiKey}
            onChange={(event) => setApiKey(event.target.value)}
            noSave
            autoFocus
          />
          <TextField
            label={settings?.hasSecret ? 'Nowy SECRET (puste = bez zmian)' : 'SECRET (potrzebny do wysyłki zamówień)'}
            value={apiSecret}
            onChange={(event) => setApiSecret(event.target.value)}
            noSave
          />
          <FormNote>
            Numer sklepu (shopID) bierzemy z punktu sprzedaży: {settings?.shopId ?? 'brak numeru sklepu'}.
          </FormNote>
          <FormActions>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Anuluj
            </Button>
            <Button type="submit" loading={save.isPending}>
              Zapisz
            </Button>
          </FormActions>
        </Form>
      </DialogContent>
    </Dialog>
  )
}
