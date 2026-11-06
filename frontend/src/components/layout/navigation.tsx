import type { ReactNode } from 'react'
import {
  BadgePercentIcon,
  ChefHatIcon,
  CookingPotIcon,
  CroissantIcon,
  DatabaseBackupIcon,
  DatabaseIcon,
  HashIcon,
  NewspaperIcon,
  PackageIcon,
  SettingsIcon,
  ShieldIcon,
  StoreIcon,
  TagIcon,
  TruckIcon,
  UserCogIcon,
  type LucideIcon,
} from 'lucide-react'

import { hasPermission } from '@/features/auth/api'
import { bakeryPath } from '@/features/bakery-check/paths'
import { basePath, meaPath } from '@/features/base-delivery/paths'
import { leafletLabelsPath, leafletPath } from '@/features/leaflet/paths'
import { promoSetupPath } from '@/features/promo-setup/paths'
import { DeliNewOrdersBadge } from '@/features/deli-order/DeliNewOrdersBadge'
import { IncomingTransfersBadge } from '@/features/documents/IncomingTransfersBadge'
import { salesPaths } from '@/features/sales/paths'
import { PartnerInvitationsBadge } from '@/features/store-settings/PartnerInvitationsBadge'
import { TOOL_ROUTES, usePermissionCategories } from '@/features/tools/categories'
import type { Me, PermissionCategory, PermissionCategoryInfo } from '@/lib/types'

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
  | { category: PermissionCategory; label?: string; badge?: (me: Me) => ReactNode; section?: string }
  | (Omit<NavItem, 'badge'> & { visible: (me: Me) => boolean; badge?: (me: Me) => ReactNode })

type GroupSpec = Omit<NavGroup, 'items'> & { items: ItemSpec[] }

const canEditDocuments = (me: Me) => hasPermission(me, 'DOCUMENTS', 'EDIT')

const DELIVERIES = 'Dokumenty i dostawy'
const PROMOTIONS = 'Promocje i gazetki'

/** Kolejność grup i narzędzi w menu; kolejne narzędzie dopisujemy do pasującej grupy. */
const GROUPS: GroupSpec[] = [
  {
    key: 'purchases',
    path: '/zakupy',
    label: 'Zakupy i dokumenty',
    description: 'Dokumenty z systemu sklepu, wprowadzanie towaru, dostawy z bazy, gazetki i promocje.',
    icon: PackageIcon,
    items: [
      { category: 'DOCUMENTS', section: DELIVERIES, badge: () => <IncomingTransfersBadge /> },
      { category: 'DATA_ENTRY', section: DELIVERIES },
      {
        key: 'base-delivery',
        section: DELIVERIES,
        path: basePath,
        label: 'Dostawa z bazy',
        description: 'Dokument dostawy z bazy w tabeli: nośniki, krótkie terminy, kaucje, nowe artykuły i ceny.',
        icon: TruckIcon,
        visible: (me) => hasPermission(me, 'DATA_ENTRY'),
      },
      {
        key: 'bakery-check',
        section: DELIVERIES,
        path: bakeryPath,
        label: 'Weryfikacja piekarni',
        description: 'Ilości z dokumentów WZ kontra faktura z korektami i bon po korektach.',
        icon: CroissantIcon,
        visible: (me) =>
          hasPermission(me, 'DATA_ENTRY') && hasPermission(me, 'DOCUMENTS') && hasPermission(me, 'KSEF_INVOICES'),
      },
      { category: 'ORDERS', section: DELIVERIES },
      {
        key: 'leaflet',
        section: PROMOTIONS,
        path: leafletPath,
        label: 'Opracowanie gazetki',
        description: 'Gazetka z centrali: artykuły z Mercalys, pliki cen .PRO, etykiety i kody MEA.',
        icon: NewspaperIcon,
        visible: (me) => hasPermission(me, 'DATA_ENTRY'),
      },
      {
        key: 'promo-setup',
        section: PROMOTIONS,
        path: promoSetupPath,
        label: 'Ustaw promocje',
        description: 'Kilka promocji naraz: artykuły, okresy, ceny lub przeceny i pliki cen .PRO.',
        icon: BadgePercentIcon,
        visible: (me) => hasPermission(me, 'DATA_ENTRY'),
      },
      {
        key: 'mea-codes',
        section: PROMOTIONS,
        path: meaPath,
        label: 'Kody MEA',
        description: 'Kody MEA z dokumentów bazy i gazetek, których jeszcze nie wpisano w Mercalys.',
        icon: HashIcon,
        visible: (me) => hasPermission(me, 'DATA_ENTRY'),
      },
    ],
  },
  {
    key: 'ksef',
    path: TOOL_ROUTES.KSEF_INVOICES.path,
    label: 'Faktury KSeF',
    description: 'Faktury zakupowe z Krajowego Systemu e-Faktur.',
    icon: TOOL_ROUTES.KSEF_INVOICES.icon,
    items: [{ category: 'KSEF_INVOICES' }],
  },
  {
    key: 'suppliers',
    path: TOOL_ROUTES.SUPPLIERS.path,
    label: 'Dostawcy',
    description: 'Kartoteka dostawców i ich kontakty.',
    icon: TOOL_ROUTES.SUPPLIERS.icon,
    items: [{ category: 'SUPPLIERS' }],
  },
  {
    key: 'sales',
    path: TOOL_ROUTES.SALES.path,
    label: 'Sprzedaż i raporty',
    description: 'Raporty sprzedaży, strat i marży oraz zakup mięsa.',
    icon: TOOL_ROUTES.SALES.icon,
    items: [{ category: 'SALES', label: 'Raporty sprzedaży' }, { category: 'MEAT_PURCHASES' }],
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
    ],
  },
  {
    key: 'data',
    path: '/dane',
    label: 'Magazyn i dane',
    description: 'Wgrywanie raportów z systemu sklepu i magazyn.',
    icon: DatabaseIcon,
    items: [
      { category: 'IMPORTS' },
      { category: 'INVENTORY' },
      {
        key: 'leaflet-labels',
        path: leafletLabelsPath,
        label: 'Etykieta z gazetki',
        description: 'Zeskanuj artykuł: jaką etykietę wydrukować i od kiedy do kiedy obowiązuje.',
        icon: TagIcon,
        visible: (me) => hasPermission(me, 'DATA_ENTRY') || hasPermission(me, 'INVENTORY'),
      },
    ],
  },
  {
    key: 'store',
    path: '/sklep',
    label: 'Sklep',
    description: 'Pracownicy i grafik oraz sklepy firmy.',
    icon: StoreIcon,
    items: [
      { category: 'STAFF' },
      {
        key: 'stores',
        path: '/ustawienia/sklepy',
        label: 'Sklepy',
        description: 'Sklepy firmy: numer, nazwa, adres, NIP.',
        icon: StoreIcon,
        visible: (me) => me.owner,
      },
    ],
  },
  {
    key: 'settings',
    path: '/ustawienia',
    label: 'Ustawienia',
    description: 'Ustawienia wybranego sklepu, role, użytkownicy i kopia zapasowa.',
    icon: SettingsIcon,
    items: [
      {
        key: 'store-settings',
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
        key: 'roles',
        path: '/ustawienia/role',
        label: 'Role i uprawnienia',
        description: 'Co wolno każdej roli w poszczególnych narzędziach.',
        icon: ShieldIcon,
        visible: (me) => me.owner,
      },
      {
        key: 'users',
        path: '/ustawienia/uzytkownicy',
        label: 'Użytkownicy',
        description: 'Zaproszenia, role i sklepy pracowników.',
        icon: UserCogIcon,
        visible: (me) => me.owner,
      },
      {
        key: 'backup',
        path: '/ustawienia/kopia-zapasowa',
        label: 'Kopia zapasowa',
        description: 'Codzienna kopia bazy na Dysk Google.',
        icon: DatabaseBackupIcon,
        visible: (me) => me.owner,
      },
    ],
  },
]

/** Ścieżki stron grup (bez grup, których ścieżka to od razu narzędzie, np. Sprzedaż). */
export const NAV_GROUP_PATHS = GROUPS.filter((group) =>
  group.items.every((item) => !('category' in item) || TOOL_ROUTES[item.category].path !== group.path),
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

/** Grupy z narzędziami dostępnymi dla użytkownika; puste grupy są pominięte. */
export function useNavGroups(me: Me): NavGroup[] {
  const { data: categories = [] } = usePermissionCategories()
  return GROUPS.map((group) => ({
    ...group,
    items: group.items.map((spec) => resolveItem(spec, me, categories)).filter((item) => item !== null),
  })).filter((group) => group.items.length > 0)
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
