// Typy odpowiedzi API, te same co w frontend/src/lib/types.ts (tylko to, czego używa aplikacja).

export type PermissionLevel = 'VIEW' | 'EDIT'

export type StoreSummary = {
  id: string
  number: string
  name: string
  active: boolean
}

export type Me = {
  id: string
  username: string
  displayName: string
  roleName: string
  owner: boolean
  permissions: Partial<Record<string, PermissionLevel>>
  passwordChangeRequired: boolean
  stores: StoreSummary[]
  /** Zdjęcie profilowe (ustawiane w przeglądarce, Moje konto), `null` = inicjały. */
  avatar: { id: string; url: string } | null
}

export type MobileLogin = { token: string; me: Me }

export type ProductListItem = {
  itm8: string
  ean: string
  name: string | null
  brand: string | null
  subfamilyCode: string | null
  stock: number | null
  rotation: number | null
  salePrice: number | null
  promoPrice: number | null
  active: boolean | null
}

export type ProductListPage = {
  items: ProductListItem[]
  total: number
  hasMore: boolean
}

export type NomenclatureOption = { code: string; name: string }

export type SalesArticle = {
  itm8: string
  ean: string
  name: string | null
  brand: string | null
  supplier: string | null
  unit: string | null
  active: boolean | null
  salePrice: number | null
  purchasePrice: number | null
  vatPercent: number | null
  stock: number | null
  rotation: number | null
  caseSize: number | null
  reportGeneratedAt: string | null
  nomenclaturePath: NomenclatureOption[]
  cadencier: boolean
  cadencierPrice: number | null
  promoPrice: number | null
  promoFrom: string | null
  promoTo: string | null
}

export type SalesReportsOverview = {
  firstSaleDate: string | null
  lastSaleDate: string | null
  lastReportGeneratedAt: string | null
}

export type SalesFigures = {
  quantity: number
  costValue: number | null
  saleValueGross: number
  saleValueNet: number
  marginValue: number | null
}

export type SalesHistory = {
  from: string
  to: string
  days: { date: string; figures: SalesFigures; lossValue: number }[]
  total: SalesFigures
  lossValue: number
}

export type ArticleDelivery = {
  documentId: string
  documentNumber: string
  documentDate: string
  supplierCode: string | null
  supplierName: string | null
  quantity: number
  purchasePrice: number | null
  purchaseValue: number
  corrected: boolean
  originalQuantity: number | null
  originalPurchasePrice: number | null
  originalPurchaseValue: number | null
  meaCodes: string[]
}

export type ArticleProtocol = {
  documentId: string
  documentType: string
  documentNumber: string
  documentDate: string
  label: string
  gratis: boolean
  quantity: number
}

export type ProductPromotion = {
  startDate: string
  endDate: string
  regularSalePrice: number | null
  promoPrice: number | null
  catalogPurchasePrice: number | null
  marginPercent: number | null
  cancelled: boolean
}

export type ProductPriceChange = {
  ean: string | null
  changedAt: string | null
  source: 'TILL' | 'REPORT' | 'DATA_ENTRY'
  previousSalePrice: number | null
  salePrice: number | null
  previousPromoPrice: number | null
  promoPrice: number | null
  promoFrom: string | null
  promoTo: string | null
  description: string | null
  user: string | null
}

export type ProductPriceHistory = {
  mainEan: string | null
  promotions: ProductPromotion[]
  changes: ProductPriceChange[]
}

/** Co pokazuje wykres wyników sklepu na pulpicie (ustawienie konta, wspólne z aplikacją w przeglądarce). */
export type DashboardChartMetric = 'QUANTITY' | 'SALES' | 'MARGIN'

export type DashboardSettings = { salesChartMetric: DashboardChartMetric }

export type LeaveKind =
  | 'ANNUAL'
  | 'ON_DEMAND'
  | 'CHILD_CARE'
  | 'FORCE_MAJEURE'
  | 'CARE'
  | 'REHABILITATION'
  | 'OCCASIONAL'
  | 'UNPAID'

/** Własny grafik: ostatnie publikacje, każdy dzień okresu; godziny jako GG:MM:SS. */
export type MySchedule = {
  employee: boolean
  from: string
  to: string
  days: {
    date: string
    shifts: { start: string; end: string; storeNumber: string; storeName: string; department: string }[]
    dayOff: { kind: 'W5' | 'WN'; forDate: string | null } | null
    leave: LeaveKind | null
  }[]
}
