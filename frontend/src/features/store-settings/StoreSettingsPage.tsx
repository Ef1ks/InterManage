import { useState } from 'react'
import {
  CheckIcon,
  ChevronRightIcon,
  FileOutputIcon,
  HandshakeIcon,
  type LucideIcon,
  PanelLeftIcon,
  PercentIcon,
  ScaleIcon,
  StoreIcon,
  TagIcon,
  TargetIcon,
} from 'lucide-react'
import { Link, Navigate, Route, Routes } from 'react-router'
import { toast } from 'sonner'

import { BackLink } from '@/components/BackLink'
import { Form, SelectField } from '@/components/form'
import { FullPageMessage } from '@/components/FullPageMessage'
import { HowTo } from '@/components/HowTo'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { useCan, useCurrentUser } from '@/features/auth/api'
import { countFormat } from '@/features/documents/format'
import { usePriceCheckSettings, useRegionBudgets } from '@/features/sales/api'
import {
  useNomenclatureMargins,
  useStorePartnerActions,
  useStorePartners,
  useSupplierSalePrices,
} from '@/features/store-settings/api'
import { storeSettingsPaths } from '@/features/store-settings/paths'
import { NomenclatureMargins } from '@/features/store-settings/NomenclatureMargins'
import { PriceRoundingRules } from '@/features/store-settings/PriceRoundingRules'
import { PriceCheckCategories } from '@/features/store-settings/PriceCheckCategories'
import { PriceCheckSettingsForm } from '@/features/store-settings/PriceCheckSettingsForm'
import { ProtoSettingsForm } from '@/features/store-settings/ProtoSettingsForm'
import { RegionBudgets } from '@/features/store-settings/RegionBudgets'
import { hasCustomSidebarOrder, SidebarOrderEditor } from '@/features/store-settings/SidebarOrderEditor'
import { SupplierSalePrices } from '@/features/store-settings/SupplierSalePrices'
import { storeLabel, useCurrentStore } from '@/features/stores/current-store'
import { ApiError } from '@/lib/api'
import { PROTOCOLS, useProtoSettings } from '@/lib/proto-files'
import type { StorePartner, StoreSummary } from '@/lib/types'
import { cn } from '@/lib/utils'

const dateFormat = new Intl.DateTimeFormat('pl-PL', { dateStyle: 'short' })
const budgetPercent = new Intl.NumberFormat('pl-PL', { maximumFractionDigits: 2 })

const paths = storeSettingsPaths

/**
 * Ustawienia wybranego sklepu: kafelek na każde ustawienie, samo ustawienie na osobnej podstronie. Budżet rejonów
 * do podsumowania sprzedaży i strat i minimalna marża do raportu zakupu bazowego poza bazą ({@code SALES:EDIT}) i sklepy zaprzyjaźnione do ruchów międzymagazynowych
 * ({@code DOCUMENTS:EDIT}), pliki PROTO (edycja wprowadzania danych, dokumentów albo zamówień garmażu); każde tylko z jego
 * uprawnieniem.
 */
export function StoreSettingsPage() {
  const store = useCurrentStore()
  const canEditSales = useCan('SALES', 'EDIT')
  const canEditDocuments = useCan('DOCUMENTS', 'EDIT')
  const canEditProto = useCanEditProtoFiles()
  const canEditDataEntry = useCan('DATA_ENTRY', 'EDIT')
  if (!store) {
    return (
      <>
        <PageHeader title="Ustawienia sklepu" />
        <FullPageMessage title="Brak sklepu" description="Ustawienia dotyczą konkretnego sklepu." inline />
      </>
    )
  }
  if (!canEditSales && !canEditDocuments && !canEditProto) {
    return (
      <>
        <PageHeader title="Ustawienia sklepu" description={storeLabel(store)} />
        <FullPageMessage
          title="Brak uprawnień"
          description="Ustawienia sklepu wymagają edycji sprzedaży, dokumentów, wprowadzania danych albo zamówień garmażu."
          inline
        />
      </>
    )
  }
  return (
    <>
      <PageHeader title="Ustawienia sklepu" description={storeLabel(store)} />
      <Routes>
        <Route
          index
          element={
            <SettingsGrid
              key={store.id}
              storeId={store.id}
              canEditSales={canEditSales}
              canEditDocuments={canEditDocuments}
              canEditProto={canEditProto}
              canEditDataEntry={canEditDataEntry}
            />
          }
        />
        {canEditSales && (
          <Route
            path="budzet"
            element={
              <section aria-labelledby="budgets-heading" className="grid gap-4">
                <BackToSettings />
                <div>
                  <h2 id="budgets-heading" className="text-lg font-semibold tracking-tight">
                    Budżet rejonów
                  </h2>
                  <p className="max-w-2xl text-sm text-muted-foreground">
                    Ile ma wynosić marża i ile najwyżej mogą wynieść straty w każdym rejonie. Podsumowanie sprzedaży i
                    strat pokazuje procenty w budżecie na zielono, a poza nim na czerwono. Puste pole = bez budżetu.
                  </p>
                </div>
                <div className="max-w-3xl">
                  <RegionBudgets key={store.id} storeId={store.id} />
                </div>
              </section>
            }
          />
        )}
        {canEditSales && (
          <Route
            path="marze"
            element={
              <section aria-labelledby="margins-heading" className="grid gap-4">
                <BackToSettings />
                <div>
                  <h2 id="margins-heading" className="text-lg font-semibold tracking-tight">
                    Marża wymagana
                  </h2>
                  <p className="max-w-2xl text-sm text-muted-foreground">
                    Jaką marżę (% od sprzedaży brutto) chcemy osiągać na nomenklaturze: na całym rejonie albo osobno na
                    rodzinie i podrodzinie. We wprowadzaniu danych marża artykułu poniżej progu jest czerwona, a kolumna
                    „Cena sug.” podaje cenę sprzedaży, przy której artykuł go osiąga, zaokrągloną według przedziałów
                    poniżej (artykuł z CENA_CAD ma w niej cenę cadencier).
                  </p>
                </div>
                <div className="grid max-w-3xl gap-4">
                  <PriceRoundingRules key={store.id} storeId={store.id} />
                  <NomenclatureMargins key={store.id} storeId={store.id} />
                </div>
              </section>
            }
          />
        )}
        {canEditDataEntry && (
          <Route
            path="ceny-dostawcow"
            element={
              <section aria-labelledby="supplier-prices-heading" className="grid gap-4">
                <BackToSettings />
                <div>
                  <h2 id="supplier-prices-heading" className="text-lg font-semibold tracking-tight">
                    Stałe ceny dostawców
                  </h2>
                  <p className="max-w-2xl text-sm text-muted-foreground">
                    Dostawcy, których wszystkie artykuły sprzedajecie za jedną cenę. We wprowadzaniu danych ta cena jest
                    ceną sugerowaną każdego artykułu z dokumentu dostawcy (nowego i z kartoteki) i wpisuje się sama jako
                    cena sprzedaży przy wczytaniu faktury i zmianie dostawcy. Zmieniona cena artykułu z kartoteki trafia
                    przy zapisie do zmiany cen.
                  </p>
                </div>
                <div className="max-w-2xl">
                  <SupplierSalePrices key={store.id} storeId={store.id} />
                </div>
              </section>
            }
          />
        )}
        {canEditSales && (
          <Route
            path="weryfikacja-cen"
            element={
              <section aria-labelledby="price-check-heading" className="grid gap-4">
                <BackToSettings />
                <div>
                  <h2 id="price-check-heading" className="text-lg font-semibold tracking-tight">
                    Weryfikacja cen
                  </h2>
                  <p className="max-w-2xl text-sm text-muted-foreground">
                    Artykuł z listy cadencier (formularza zamówień do bazy) kupiony u dostawcy innego niż baza trafia do
                    raportu „Zakup bazowy poza bazą”. Listę wgrywa się w Imporcie danych. Minimalna marża oznacza w
                    raporcie artykuły, na których sklep zarabia za mało.
                  </p>
                </div>
                <div className="grid max-w-2xl gap-4">
                  <PriceCheckSettingsForm key={store.id} storeId={store.id} />
                  <PriceCheckCategories key={store.id} storeId={store.id} />
                </div>
              </section>
            }
          />
        )}
        {canEditDocuments && (
          <Route
            path="sklepy-zaprzyjaznione"
            element={
              <div className="grid gap-4">
                <BackToSettings />
                <StorePartners key={store.id} store={store} />
              </div>
            }
          />
        )}
        {canEditProto && (
          <Route
            path="pliki-proto"
            element={
              <section aria-labelledby="proto-heading" className="grid gap-4">
                <BackToSettings />
                <div>
                  <h2 id="proto-heading" className="text-lg font-semibold tracking-tight">
                    Protokoły i zmiana cen
                  </h2>
                  <p className="max-w-2xl text-sm text-muted-foreground">
                    Pliki, które program sklepu sam wczytuje z folderu: zamówienia garmażu, przyjęcia MM oraz protokoły
                    i zmiany cen z wprowadzania danych. Protokoły, z których sklep nie korzysta, można ukryć.
                  </p>
                </div>
                <div className="max-w-2xl">
                  <ProtoSettingsForm key={store.id} storeId={store.id} />
                </div>
              </section>
            }
          />
        )}
        <Route
          path="menu-boczne"
          element={
            <section aria-labelledby="sidebar-heading" className="grid gap-4">
              <BackToSettings />
              <div>
                <h2 id="sidebar-heading" className="text-lg font-semibold tracking-tight">
                  Menu boczne
                </h2>
                <p className="max-w-2xl text-sm text-muted-foreground">
                  Przeciągnij kategorie w kolejności, w jakiej mają stać w menu. Kolejność obowiązuje wszystkich w tym
                  sklepie; każdy widzi tylko kategorie, do których ma dostęp.
                </p>
              </div>
              <div className="max-w-md">
                <SidebarOrderEditor key={store.id} storeId={store.id} />
              </div>
            </section>
          }
        />
        <Route path="*" element={<Navigate to={paths.overview} replace />} />
      </Routes>
    </>
  )
}

/** Pliki PROTO ustawia każdy, kto je zapisuje: wprowadzanie danych, dokumenty (przyjęcia MM) i zamówienia garmażu. */
function useCanEditProtoFiles() {
  const dataEntry = useCan('DATA_ENTRY', 'EDIT')
  const documents = useCan('DOCUMENTS', 'EDIT')
  const deliOrders = useCan('DELI_ORDERS', 'EDIT')
  return dataEntry || documents || deliOrders
}

function BackToSettings() {
  return (
    <BackLink to={paths.overview} className="mb-0">
      Ustawienia sklepu
    </BackLink>
  )
}

/** Kafelki ustawień ze stanem w skrócie: ile rejonów ma budżet, minimalna marża, ilu jest znajomych i czy czekają zaproszenia. */
function SettingsGrid({
  storeId,
  canEditSales,
  canEditDocuments,
  canEditProto,
  canEditDataEntry,
}: {
  storeId: string
  canEditSales: boolean
  canEditDocuments: boolean
  canEditProto: boolean
  canEditDataEntry: boolean
}) {
  const me = useCurrentUser()
  const budgets = useRegionBudgets(storeId, canEditSales)
  const priceCheck = usePriceCheckSettings(storeId, canEditSales)
  const margins = useNomenclatureMargins(storeId, canEditSales)
  const partners = useStorePartners(storeId, canEditDocuments)
  const proto = useProtoSettings(storeId, canEditProto)
  const supplierPrices = useSupplierSalePrices(storeId, canEditDataEntry)
  const visibleProtocols = proto.data && PROTOCOLS.length - proto.data.hiddenProtocols.length
  const budgeted = budgets.data?.filter(
    (budget) => budget.marginPercent != null || budget.maxLossPercent != null,
  ).length
  const withMargin = margins.data?.filter((item) => item.marginPercent != null).length
  const accepted = partners.data?.partners.filter((partner) => partner.acceptedAt).length
  const pending = partners.data?.partners.filter((partner) => !partner.acceptedAt && !partner.sent).length ?? 0

  return (
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      {canEditSales && (
        <SettingsTile
          to={paths.budgets}
          icon={TargetIcon}
          title="Budżet rejonów"
          description="Docelowa marża i maksymalne straty każdego rejonu do podsumowania sprzedaży i strat."
          status={
            budgets.data
              ? budgeted
                ? `Budżet ma ${budgeted} z ${budgets.data.length} rejonów`
                : 'Budżet nie jest jeszcze ustawiony'
              : ' '
          }
        />
      )}
      {canEditSales && (
        <SettingsTile
          to={paths.margins}
          icon={PercentIcon}
          title="Marża wymagana"
          description="Marża do osiągnięcia na nomenklaturze i zaokrąglanie cen sugerowanych we wprowadzaniu danych."
          status={
            margins.data
              ? withMargin
                ? `Ustawiona na ${countFormat.format(withMargin)} pozycjach`
                : 'Marże nie są jeszcze ustawione'
              : ' '
          }
        />
      )}
      {canEditDataEntry && (
        <SettingsTile
          to={paths.supplierPrices}
          icon={TagIcon}
          title="Stałe ceny dostawców"
          description="Jedna cena sprzedaży dla wszystkich artykułów dostawcy, wpisywana sama we wprowadzaniu danych."
          status={
            supplierPrices.data
              ? supplierPrices.data.length
                ? `Ustawiona u ${countFormat.format(supplierPrices.data.length)} ${supplierPrices.data.length === 1 ? 'dostawcy' : 'dostawców'}`
                : 'Żaden dostawca nie ma stałej ceny'
              : ' '
          }
        />
      )}
      {canEditSales && (
        <SettingsTile
          to={paths.priceCheck}
          icon={ScaleIcon}
          title="Weryfikacja cen"
          description="Minimalna marża i własne kategorie do raportów weryfikacji zakupów."
          status={
            priceCheck.data
              ? priceCheck.data.minMarginPercent != null
                ? `Marża min. ${budgetPercent.format(priceCheck.data.minMarginPercent)}%`
                : 'Bez minimalnej marży'
              : ' '
          }
        />
      )}
      {canEditDocuments && (
        <SettingsTile
          to={paths.partners}
          icon={HandshakeIcon}
          title="Sklepy zaprzyjaźnione"
          description="Sklepy, dla których można wystawiać ruchy międzymagazynowe do wprowadzenia u nich."
          status={partners.data ? (accepted ? `Zaprzyjaźnione: ${accepted}` : 'Brak sklepów zaprzyjaźnionych') : ' '}
          alert={pending > 0 ? `Zaproszenia do akceptacji: ${pending}` : undefined}
        />
      )}
      {canEditProto && (
        <SettingsTile
          to={paths.protoFiles}
          icon={FileOutputIcon}
          title="Protokoły i zmiana cen"
          description="Folder, z którego program sklepu wczytuje protokoły i zmiany cen, oraz protokoły dostępne we wprowadzaniu danych."
          status={proto.data ? `${proto.data.folderPath} · protokoły: ${visibleProtocols} z ${PROTOCOLS.length}` : ' '}
        />
      )}
      <SettingsTile
        to={paths.sidebar}
        icon={PanelLeftIcon}
        title="Menu boczne"
        description="Kolejność kategorii w menu, wspólna dla całego sklepu."
        status={hasCustomSidebarOrder(me.sidebarOrders[storeId]) ? 'Własna kolejność' : 'Kolejność domyślna'}
      />
    </div>
  )
}

function SettingsTile({
  to,
  icon: Icon,
  title,
  description,
  status,
  alert,
}: {
  to: string
  icon: LucideIcon
  title: string
  description: string
  status: string
  alert?: string
}) {
  return (
    <Link
      to={to}
      className={cn(
        'group flex flex-col gap-3 rounded-xl border bg-card p-4 transition-[border-color,box-shadow] duration-150 outline-none',
        'hover:border-foreground/20 hover:shadow-[0_2px_8px_-2px_rgb(0_0_0/0.08)] focus-visible:ring-3 focus-visible:ring-ring/50',
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <span className="flex size-10 items-center justify-center rounded-lg bg-muted text-foreground">
          <Icon aria-hidden className="size-5" />
        </span>
        <ChevronRightIcon
          aria-hidden
          className="size-4 text-muted-foreground transition-transform duration-150 group-hover:translate-x-0.5"
        />
      </div>
      <div className="min-w-0">
        <h2 className="font-semibold">{title}</h2>
        <p className="mt-0.5 text-sm text-muted-foreground">{description}</p>
      </div>
      <div className="mt-auto grid gap-1 text-sm tabular-nums">
        <p className="text-muted-foreground">{status}</p>
        {alert && <p className="font-medium text-amber-700 dark:text-amber-400">{alert}</p>}
      </div>
    </Link>
  )
}

function StorePartners({ store }: { store: StoreSummary }) {
  const { data, isPending, isError, refetch } = useStorePartners(store.id)
  const { invite, accept, remove, restore } = useStorePartnerActions(store.id)
  const [picked, setPicked] = useState('')

  const onError = (error: unknown) =>
    toast.error(error instanceof ApiError ? error.message : 'Nie udało się zapisać zmiany')

  const removePartner = (partner: StorePartner, done: string) =>
    remove.mutate(partner.id, {
      onSuccess: () =>
        toast.success(done, {
          action: {
            label: 'Przywróć',
            onClick: () => restore.mutate(partner.id, { onError }),
          },
        }),
      onError,
    })

  if (isError) {
    return (
      <FullPageMessage
        title="Nie udało się wczytać sklepów zaprzyjaźnionych"
        action={{ label: 'Spróbuj ponownie', onClick: () => refetch() }}
        inline
      />
    )
  }

  const partners = data?.partners ?? []
  const received = partners.filter((partner) => !partner.acceptedAt && !partner.sent)
  const accepted = partners.filter((partner) => partner.acceptedAt)
  const sent = partners.filter((partner) => !partner.acceptedAt && partner.sent)

  return (
    <section aria-labelledby="partners-heading" className="grid gap-4">
      <div>
        <h2 id="partners-heading" className="text-lg font-semibold tracking-tight">
          Sklepy zaprzyjaźnione
        </h2>
        <p className="max-w-2xl text-sm text-muted-foreground">
          Ruch międzymagazynowy można wystawić dla sklepu zaprzyjaźnionego. Sklep zobaczy go w Dokumentach jako dokument
          do wprowadzenia u siebie.
        </p>
      </div>

      <HowTo
        id="store-partners"
        title="Jak zaprzyjaźnić sklepy"
        steps={[
          'Zaproś sklep poniżej.',
          'Osoba z tamtego sklepu akceptuje zaproszenie w swoich Ustawieniach sklepu.',
          'Od tej chwili oba sklepy mogą wskazać się nawzajem w ruchu międzymagazynowym (Dokumenty → Ruchy międzymagazynowe).',
        ]}
      />

      {received.length > 0 && (
        <Card className="gap-0 border-amber-300 py-0 dark:border-amber-500/40">
          <h3 className="border-b px-4 py-3 text-sm font-semibold">Zaproszenia do tego sklepu</h3>
          <ul className="divide-y">
            {received.map((partner) => (
              <li key={partner.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
                <PartnerLabel partner={partner} detail={invitedLine(partner)} />
                <div className="flex gap-2">
                  <Button
                    loading={accept.isPending && accept.variables === partner.id}
                    onClick={() =>
                      accept.mutate(partner.id, {
                        onSuccess: () => toast.success(`${storeLabel(partner.store)} jest teraz zaprzyjaźniony`),
                        onError,
                      })
                    }
                  >
                    <CheckIcon />
                    Akceptuj
                  </Button>
                  <Button variant="ghost" onClick={() => removePartner(partner, 'Odrzucono zaproszenie')}>
                    Odrzuć
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Card className="gap-0 py-0">
        {isPending ? (
          <div aria-hidden className="m-4 h-16 animate-pulse rounded-lg bg-muted motion-reduce:animate-none" />
        ) : accepted.length === 0 && sent.length === 0 ? (
          <div className="flex flex-col items-center gap-1 px-6 py-8 text-center">
            <HandshakeIcon aria-hidden className="mb-1 size-6 text-muted-foreground" />
            <p className="font-medium">Brak sklepów zaprzyjaźnionych</p>
            <p className="max-w-sm text-sm text-muted-foreground">
              Zaproś sklep poniżej, a gdy zaakceptuje, pojawi się tutaj.
            </p>
          </div>
        ) : (
          <ul className="divide-y">
            {accepted.map((partner) => (
              <li key={partner.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
                <PartnerLabel
                  partner={partner}
                  detail={`zaprzyjaźniony od ${dateFormat.format(new Date(partner.acceptedAt!))}`}
                />
                <Button
                  variant="ghost"
                  onClick={() => removePartner(partner, `Zakończono znajomość z ${storeLabel(partner.store)}`)}
                >
                  Zakończ
                </Button>
              </li>
            ))}
            {sent.map((partner) => (
              <li key={partner.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
                <PartnerLabel
                  partner={partner}
                  detail={invitedLine(partner)}
                  badge={
                    <Badge variant="outline" className="text-muted-foreground">
                      Czeka na akceptację
                    </Badge>
                  }
                />
                <Button variant="ghost" onClick={() => removePartner(partner, 'Cofnięto zaproszenie')}>
                  Cofnij
                </Button>
              </li>
            ))}
          </ul>
        )}

        <Form
          className="flex flex-wrap items-start gap-3 border-t px-4 py-4"
          onSubmit={(event) => {
            event.preventDefault()
            if (!picked) return
            invite.mutate(picked, {
              onSuccess: () => {
                toast.success('Wysłano zaproszenie')
                setPicked('')
              },
              onError,
            })
          }}
        >
          <SelectField
            label="Zaproś sklep"
            options={(data?.invitableStores ?? []).map((candidate) => ({
              value: candidate.id,
              label: storeLabel(candidate),
            }))}
            value={picked}
            onChange={setPicked}
            disabled={!data || data.invitableStores.length === 0}
            hint={data && data.invitableStores.length === 0 ? 'Wszystkie sklepy są już zaproszone.' : undefined}
            className="w-full sm:w-80"
          />
          <Button type="submit" size="xl" loading={invite.isPending} disabled={!picked}>
            Zaproś
          </Button>
        </Form>
      </Card>
    </section>
  )
}

function PartnerLabel({ partner, detail, badge }: { partner: StorePartner; detail: string; badge?: React.ReactNode }) {
  return (
    <div className="flex min-w-0 flex-1 items-center gap-3">
      <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted">
        <StoreIcon aria-hidden className="size-4" />
      </span>
      <div className="min-w-0">
        <p className="flex flex-wrap items-center gap-2 font-medium">
          {storeLabel(partner.store)}
          {badge}
        </p>
        <p className="text-sm text-muted-foreground tabular-nums">{detail}</p>
      </div>
    </div>
  )
}

function invitedLine(partner: StorePartner) {
  const date = dateFormat.format(new Date(partner.invitedAt))
  return partner.invitedBy ? `zaprosił(a) ${partner.invitedBy}, ${date}` : `zaproszenie z ${date}`
}
