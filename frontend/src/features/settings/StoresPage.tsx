import { useState } from 'react'
import { PencilIcon, PlusIcon } from 'lucide-react'

import { FullPageMessage } from '@/components/FullPageMessage'
import { PageHeader } from '@/components/PageHeader'
import { TableSkeletonRows } from '@/components/TableSkeleton'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { useStores } from '@/features/settings/api'
import { StoreFormDialog } from '@/features/settings/StoreFormDialog'
import type { Store } from '@/lib/types'

export function StoresPage() {
  const { data: stores, isPending, isError, refetch } = useStores()
  const [editor, setEditor] = useState<{ open: boolean; store?: Store }>({ open: false })

  return (
    <>
      <PageHeader
        title="Sklepy"
        description="Lokalizacje, którymi zarządzasz. Dostęp pracowników do sklepów ustawiasz w Użytkownikach."
        actions={
          <Button size="lg" onClick={() => setEditor({ open: true })}>
            <PlusIcon />
            Dodaj sklep
          </Button>
        }
      />

      {isError ? (
        <FullPageMessage
          title="Nie udało się wczytać sklepów"
          action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
          inline
        />
      ) : !isPending && stores.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center">
          <p className="font-medium">Nie ma jeszcze żadnego sklepu</p>
          <p className="max-w-sm text-sm text-muted-foreground">
            Dodaj sklep z numerem punktu sprzedaży z raportów, np. 10023. Bez sklepu nie da się importować danych.
          </p>
          <Button size="lg" className="mt-2" onClick={() => setEditor({ open: true })}>
            <PlusIcon />
            Dodaj pierwszy sklep
          </Button>
        </Card>
      ) : (
        <Card className="py-0">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="pl-4">Sklep</TableHead>
                <TableHead>Numer</TableHead>
                <TableHead className="text-right">Pracownicy</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="w-16 pr-4">
                  <span className="sr-only">Akcje</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isPending ? (
                <TableSkeletonRows columns={5} />
              ) : (
                stores.map((store) => (
                  <TableRow key={store.id}>
                    <TableCell className="py-3 pl-4">
                      <div className="font-medium">{store.name}</div>
                      {store.address && <div className="text-xs text-muted-foreground">{store.address}</div>}
                    </TableCell>
                    <TableCell className="tabular-nums">{store.number}</TableCell>
                    <TableCell className="text-right tabular-nums">{store.userCount}</TableCell>
                    <TableCell>
                      {store.active ? (
                        <Badge variant="outline" className="gap-1.5">
                          <span aria-hidden className="size-1.5 rounded-full bg-emerald-500" />
                          Aktywny
                        </Badge>
                      ) : (
                        <Badge variant="secondary">Wyłączony</Badge>
                      )}
                    </TableCell>
                    <TableCell className="pr-4 text-right">
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label={`Edytuj sklep ${store.name}`}
                        title="Edytuj"
                        onClick={() => setEditor({ open: true, store })}
                      >
                        <PencilIcon />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </Card>
      )}

      <StoreFormDialog
        open={editor.open}
        store={editor.store}
        onOpenChange={(open) => setEditor((current) => ({ ...current, open }))}
      />
    </>
  )
}
