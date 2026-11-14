import type { ReactNode } from 'react'
import {
  BadgePercentIcon,
  BanknoteIcon,
  ChefHatIcon,
  ClipboardCheckIcon,
  BeefIcon,
  CookingPotIcon,
  CroissantIcon,
  DatabaseBackupIcon,
  DatabaseIcon,
  DownloadIcon,
  HashIcon,
  NewspaperIcon,
  PackageIcon,
  SettingsIcon,
  ShieldIcon,
  StoreIcon,
  TagIcon,
  TruckIcon,
  UserCogIcon,
  UsersIcon,
  type LucideIcon,
} from 'lucide-react'

import { hasPermission } from '@/features/auth/api'
import { bakeryPath } from '@/features/bakery-check/paths'
import { basePath, meaPath } from '@/features/base-delivery/paths'
import { leafletLabelsPath, leafletPath } from '@/features/leaflet/paths'
import { promoSetupPath } from '@/features/promo-setup/paths'
import { downloadsPath } from '@/features/downloads/paths'
import { DeliNewOrdersBadge } from '@/features/deli-order/DeliNewOrdersBadge'
import { IncomingTransfersBadge } from '@/features/documents/IncomingTransfersBadge'
import { NewDownloadsBadge } from '@/features/downloads/DownloadUpdatesBanner'
import { salesPaths } from '@/features/sales/paths'
import { stocktakePath } from '@/features/stocktake/paths'
import { PartnerInvitationsBadge } from '@/features/store-settings/PartnerInvitationsBadge'
import { TimeAttentionBadge } from '@/features/staff-time/TimeAttentionBadge'
import { useCurrentStore } from '@/features/stores/current-store'
import { TOOL_ROUTES, usePermissionCategories } from '@/features/tools/categories'
import type { Me, PermissionCategoryInfo, ToolCategory } from '@/lib/types'

/** Narzędzie w grupie: w zakładkach nad stroną i na kafelkach strony grupy. */
export type NavItem = {
  key: string
  path: string
  label: string
  description: string
  icon: LucideIcon
  /** Np. licznik nowych zamówień; w menu bocznym przy grupie. */
  badge?: ReactNode
  /** Nagłówek sekcji na stronie grupy; kolejne narzędzia z tą samą sekcją stoją razem. */
  section?: string
}

/** Główna kategoria w menu bocznym. Grupa z jednym dostępnym narzędziem prowadzi prosto do niego. */
export type NavGroup = {
  key: string
  path: string
  label: string
  description: string
  icon: LucideIcon
  items: NavItem[]
}

/** Narzędzie z kategorii uprawnień albo strona z własnym warunkiem dostępu (ustawienia, koszty garmażu). */
type ItemSpec =
  | { category: ToolCategory; label?: string; badge?: (me: Me) => ReactNode; section?: string }
  | (Omit<NavItem, 'badge'> & { visible: (me: Me) => boolean; badge?: (me: Me) => ReactNode })

type GroupSpec = Omit<NavGroup, 'items'> & { items: ItemSpec[] }

const canEditDocuments = (me: Me) => hasPermission(me, 'DOCUMENTS', 'EDIT')

const RECEIVING = 'Przyjęcie towaru'
const PURCHASE_CHECKS = 'Weryfikacja zakupów'
const SUPPLIER_LIST = 'Dostawcy'
const STOCK = 'Towar'
const STORE_DATA = 'Dane z systemu sklepu'
const STORE_SCOPE = 'Wybrany sklep'
const COMPANY_SCOPE = 'Firma'

/**
 * Kolejność grup i narzędzi w menu; kolejne narzędzie dopisujemy do pasującej grupy.
 * Klucze grup są zapisane w kolejności menu sklepu (`store_sidebar_settings`), więc istniejących nie zmieniamy.
 * Zamówienia do dostawców (`ORDERS`) nie mają jeszcze modułu, więc nie stoją w menu.
 */
const GROUPS: GroupSpec[] = [
  {
    key: 'documents',
    path: TOOL_ROUTES.DOCUMENTS.path,
    label: 'Dokumenty',
    description: 'Dokumenty magazynowe z systemu sklepu.',
    icon: TOOL_ROUTES.DOCUMENTS.icon,
    items: [{ category: 'DOCUMENTS', badge: () => <IncomingTransfersBadge /> }],
  },
  {
    key: 'purchases',
    path: '/zakupy',
    label: 'Zakupy',
    description: 'Przyjęcie towaru z faktur i z bazy, faktury KSeF, weryfikacja zakupów i dostawcy.',
    icon: PackageIcon,
    items: [
      { category: 'DATA_ENTRY', section: RECEIVING },
      {
        key: 'base-delivery',
        section: RECEIVING,
        path: basePath,
        label: 'Dostawa z bazy',
        description: 'Dokument dostawy z bazy w tabeli: nośniki, krótkie terminy, kaucje, nowe artykuły i ceny.',
        icon: TruckIcon,
        visible: (me) => hasPermission(me, 'DATA_ENTRY'),
      },
      { category: 'KSEF_INVOICES', section: RECEIVING },
      {
        key: 'bakery-check',
        section: PURCHASE_CHECKS,
        path: bakeryPath,
        label: 'Weryfikacja piekarni',
        description: 'Ilości z dokumentów WZ kontra faktura z korektami i bon po korektach.',
        icon: CroissantIcon,
        // Bony i faktury są częścią tej pracy: wystarczy wprowadzanie danych, bez Dokumentów i KSeF.
        visible: (me) => hasPermission(me, 'DATA_ENTRY'),
      },
      { category: 'MEAT_PURCHASES', section: PURCHASE_CHECKS },
      { category: 'SUPPLIERS', section: SUPPLIER_LIST },
    ],
  },
  {
    key: 'promotions',
    path: '/promocje',
    label: 'Promocje',
    description: 'Gazetki z centrali, własne promocje, etykiety przy półce i kody MEA.',
    icon: BadgePercentIcon,
    items: [
      {
        key: 'leaflet',
        path: leafletPath,
        label: 'Opracowanie gazetki',
        description: 'Gazetka z centrali: artykuły z Mercalys, ceny promocji, etykiety i kody MEA.',
        icon: NewspaperIcon,
        visible: (me) => hasPermission(me, 'DATA_ENTRY'),
      },
      {
        key: 'promo-setup',
        path: promoSetupPath,
        label: 'Ustaw promocje',
        description: 'Kilka promocji naraz: artykuły, okresy, ceny lub przeceny i ceny promocji dla programu sklepu.',
        icon: BadgePercentIcon,
        visible: (me) => hasPermission(me, 'DATA_ENTRY'),
      },
      {
        key: 'leaflet-labels',
        path: leafletLabelsPath,
        label: 'Etykieta z gazetki',
        description: 'Zeskanuj artykuł: jaką etykietę wydrukować i od kiedy do kiedy obowiązuje.',
        icon: TagIcon,
        visible: (me) => hasPermission(me, 'DATA_ENTRY') || hasPermission(me, 'INVENTORY'),
      },
      {
        key: 'mea-codes',
        path: meaPath,
        label: 'Kody MEA',
        description: 'Kody MEA z dokumentów bazy i gazetek, których jeszcze nie wpisano w Mercalys.',
        icon: HashIcon,
        visible: (me) => hasPermission(me, 'DATA_ENTRY'),
      },
    ],
  },
  {
    key: 'sales',
    path: TOOL_ROUTES.SALES.path,
    label: 'Raporty',
    description: 'Sprzedaż, marża i straty, weryfikacja zakupów, promocje, braki i zerówki.',
    icon: TOOL_ROUTES.SALES.icon,
    items: [{ category: 'SALES', label: 'Raporty' }],
  },
  {
    key: 'deli',
    path: '/garmaz',
    label: 'Garmaż',
    description: 'Zamówienia działów, karty produkcji, kalkulator cen i koszty garmażu.',
    icon: ChefHatIcon,
    items: [
      { category: 'DELI_ORDERS', label: 'Zamówienia', badge: () => <DeliNewOrdersBadge /> },
      { category: 'DELI_PRODUCTION', label: 'Karty produkcji' },
      { category: 'DELI_PRICES', label: 'Kalkulator cen' },
      {
        key: 'deli-costs',
        path: salesPaths.deliCosts,
        label: 'Koszty garmażu',
        description: 'Sprzedaż garmażu (nomenklatura 36) z kosztem z protokołów reprezentacji.',
        icon: CookingPotIcon,
        visible: (me) => hasPermission(me, 'SALES'),
      },
      {
        key: 'counter-report',
        path: salesPaths.counter,
        label: 'Raport lady',
        description: 'Mięso, garmaż i drób (24, 36, 46) w kilku sklepach naraz. Z wydrukiem.',
        icon: BeefIcon,
        visible: (me) => hasPermission(me, 'SALES'),
      },
    ],
  },
  {
    key: 'data',
    path: '/dane',
    label: 'Magazyn i dane',
    description: 'Kartoteka towarów, weryfikacja inwentury i codzienne raporty z systemu sklepu.',
    icon: DatabaseIcon,
    items: [
      { category: 'INVENTORY', section: STOCK },
      {
        key: 'stocktake',
        section: STOCK,
        path: stocktakePath,
        label: 'Weryfikacja inwentury',
        description: 'Co przeliczyć przed zamknięciem inwentury: pomyłki plus/minus, niepoliczony towar i literówki.',
        icon: ClipboardCheckIcon,
        visible: (me) => hasPermission(me, 'INVENTORY'),
      },
      { category: 'IMPORTS', section: STORE_DATA },
    ],
  },
  {
    key: 'store',
    path: '/zespol',
    label: 'Zespół',
    description: 'Pracownicy, grafik, czas pracy i kadry.',
    icon: UsersIcon,
    items: [
      { category: 'STAFF' },
      { category: 'STAFF_SCHEDULE' },
      { category: 'STAFF_TIME', badge: () => <TimeAttentionBadge /> },
      { category: 'STAFF_RECORDS' },
    ],
  },
  {
    key: 'cash',
    path: '/kasy',
    label: 'Kasy',
    description: 'Raport kasowy: dokumenty KP i KW z wydrukiem i pieczątką sklepu.',
    icon: BanknoteIcon,
    items: [{ category: 'CASH_REPORT' }],
  },
  {
    key: 'settings',
    path: '/ustawienia',
    label: 'Ustawienia',
    description: 'Ustawienia wybranego sklepu oraz sklepy, użytkownicy, role, kopia zapasowa i pliki do pobrania.',
    icon: SettingsIcon,
    items: [
      {
        key: 'store-settings',
        section: STORE_SCOPE,
        path: '/ustawienia-sklepu',
        label: 'Ustawienia sklepu',
        description: 'Budżet rejonów, marże, ceny dostawców, sklepy zaprzyjaźnione, protokoły i zmiana cen.',
        icon: SettingsIcon,
        // Budżet i marże (sprzedaż), sklepy zaprzyjaźnione (dokumenty), pliki PROTO (też wprowadzanie danych i garmaż).
        visible: (me) =>
          canEditDocuments(me) ||
          hasPermission(me, 'SALES', 'EDIT') ||
          hasPermission(me, 'DATA_ENTRY', 'EDIT') ||
          hasPermission(me, 'DELI_ORDERS', 'EDIT'),
        badge: (me) => canEditDocuments(me) && <PartnerInvitationsBadge />,
      },
      {
        key: 'stores',
        section: COMPANY_SCOPE,
        path: '/ustawienia/sklepy',
        label: 'Sklepy',
        description: 'Sklepy firmy: numer, nazwa, adres, NIP.',
        icon: StoreIcon,
        visible: (me) => me.owner,
      },
      {
        key: 'users',
        section: COMPANY_SCOPE,
        path: '/ustawienia/uzytkownicy',
        label: 'Użytkownicy',
        description: 'Zaproszenia, role i sklepy pracowników.',
        icon: UserCogIcon,
        visible: (me) => me.owner,
      },
      {
        key: 'roles',
        section: COMPANY_SCOPE,
        path: '/ustawienia/role',
        label: 'Role i uprawnienia',
        description: 'Co wolno każdej roli w poszczególnych narzędziach.',
        icon: ShieldIcon,
        visible: (me) => me.owner,
      },
      {
        key: 'backup',
        section: COMPANY_SCOPE,
        path: '/ustawienia/kopia-zapasowa',
        label: 'Kopia zapasowa',
        description: 'Codzienna kopia bazy na Dysk Google.',
        icon: DatabaseBackupIcon,
        visible: (me) => me.owner,
      },
      {
        key: 'downloads',
        section: COMPANY_SCOPE,
        path: downloadsPath,
        label: 'Do pobrania',
        description: 'Programy do zainstalowania na komputerze, np. AutoJob.',
        icon: DownloadIcon,
        // Pobiera każdy zalogowany, więc Ustawienia widzi każdy (z samym tym kafelkiem bez innych uprawnień).
        visible: () => true,
        badge: () => <NewDownloadsBadge />,
      },
    ],
  },
]

/** Dawne adresy stron grup (zakładki, historia przeglądarki) i dokąd prowadzą teraz. */
export const MOVED_GROUP_PATHS: Record<string, string> = { '/sklep': '/zespol' }

/** Wszystkie kategorie menu do ustawienia kolejności, także te, których ta osoba nie widzi. */
export type SidebarGroup = Pick<GroupSpec, 'key' | 'label' | 'icon'>
export const SIDEBAR_GROUPS: SidebarGroup[] = GROUPS.map(({ key, label, icon }) => ({ key, label, icon }))

/** Układa kategorie według zapisanej kolejności; kategorie spoza niej (np. nowe) idą na koniec w kolejności z `GROUPS`. */
export function orderByKeys<T extends { key: string }>(groups: T[], order: string[] = []): T[] {
  const place = (group: T) => {
    const index = order.indexOf(group.key)
    return index === -1 ? order.length : index
  }
  return [...groups].sort((a, b) => place(a) - place(b))
}

/** Ścieżki stron grup (bez grup, których ścieżka to od razu narzędzie, np. Sprzedaż). */
export const NAV_GROUP_PATHS = GROUPS.filter((group) =>
  group.items.every((item) => ('category' in item ? TOOL_ROUTES[item.category].path : item.path) !== group.path),
).map((group) => group.path)

function resolveItem(spec: ItemSpec, me: Me, categories: PermissionCategoryInfo[]): NavItem | null {
  if ('category' in spec) {
    const info = categories.find((category) => category.code === spec.category)
    if (!info || !hasPermission(me, spec.category)) return null
    const { path, icon } = TOOL_ROUTES[spec.category]
    return {
      key: spec.category,
      path,
      icon,
      label: spec.label ?? info.label,
      description: info.description,
      badge: spec.badge?.(me),
      section: spec.section,
    }
  }
  if (!spec.visible(me)) return null
  const { visible: _visible, badge, ...item } = spec
  return { ...item, badge: badge?.(me) }
}

/** Grupy z narzędziami dostępnymi dla użytkownika w kolejności sklepu; puste grupy są pominięte. */
export function useNavGroups(me: Me): NavGroup[] {
  const { data: categories = [] } = usePermissionCategories()
  const store = useCurrentStore()
  return orderByKeys(GROUPS, store ? me.sidebarOrders[store.id] : undefined)
    .map((group) => ({
      ...group,
      items: group.items.map((spec) => resolveItem(spec, me, categories)).filter((item) => item !== null),
    }))
    .filter((group) => group.items.length > 0)
}

/** Grupa i narzędzie, w których jest ścieżka: najdłuższa pasująca ścieżka narzędzia (koszty garmażu leżą pod /sprzedaz). */
export function locate(groups: NavGroup[], pathname: string): { group: NavGroup; item: NavItem | null } | null {
  let found: { group: NavGroup; item: NavItem | null } | null = null
  let length = -1
  const under = (path: string) => pathname === path || pathname.startsWith(`${path}/`)
  for (const group of groups) {
    for (const item of group.items) {
      if (under(item.path) && item.path.length > length) {
        found = { group, item }
        length = item.path.length
      }
    }
    if (pathname === group.path && group.path.length > length) {
      found = { group, item: null }
      length = group.path.length
    }
  }
  return found
}

/** Dokąd prowadzi grupa w menu: na stronę grupy, a przy jednym narzędziu prosto do niego. */
export const groupTarget = (group: NavGroup) => (group.items.length === 1 ? group.items[0] : group)
