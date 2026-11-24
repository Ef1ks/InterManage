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
  /** Konto zleca zadania (właściciel albo włączone przez niego zlecanie). */
  canAssignTasks?: boolean
  permissions: Partial<Record<string, PermissionLevel>>
  passwordChangeRequired: boolean
  stores: StoreSummary[]
  /** Zdjęcie profilowe (ustawiane w przeglądarce, Moje konto), `null` = inicjały. */
  avatar: { id: string; url: string } | null
  /** Uprawnienia w każdym sklepie (ranga konta w sklepie); `permissions` to już uprawnienia wybranego sklepu. */
  storePermissions?: Record<string, Partial<Record<string, PermissionLevel>>>
  /** Ranga konta w sklepie (id sklepu → nazwa). */
  storeRoles?: Record<string, string>
  /** Sklepy, w których konto zleca zadania. */
  taskAssignStores?: string[]
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
  /** Był w najnowszym raporcie kartoteki; bez tego ITM8 zniknął z systemu sklepu (np. połączony z innym). */
  inLastReport: boolean
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
  /** Dzisiejsze ceny artykułu aktywnego w cadencier (plik TOP): zakup netto, sprzedaż brutto. */
  cadencierCurrent: {
    nrCad: string
    from: string
    purchasePrice: number | null
    salePrice: number | null
  } | null
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
  | 'B2B_PAID'
  | 'SICK'

/** Własny grafik: ostatnie publikacje, każdy dzień okresu; godziny jako GG:MM:SS. */
export type MySchedule = {
  employee: boolean
  from: string
  to: string
  days: {
    date: string
    shifts: { start: string; end: string; storeNumber: string; storeName: string; department: string }[]
    dayOff: { kind: 'W5' | 'WN' | 'W'; forDate: string | null } | null
    leave: LeaveKind | null
  }[]
}

/** Rodzaj wniosku pracownika: o odcinek karty czasu pracy albo zgłoszenie L4. */
export type TimeRequestKind =
  | 'OVERTIME'
  | 'WORK_OFF'
  | 'PRIVATE_EXIT'
  | 'LATE_OR_EARLY'
  | 'FORCE_MAJEURE'
  | 'CHILD_CARE'
  | 'OTHER_PAID'
  | 'SICK_LEAVE'

export type TimeRequestStatus = 'PENDING' | 'MANAGER_APPROVED' | 'APPROVED' | 'REJECTED' | 'CANCELLED'

/** Własny wniosek (`GET /me/time-requests`); `start`/`end` przy odcinku karty, `workDate`..`dateTo` przy L4. */
export type TimeRequest = {
  id: string
  kind: TimeRequestKind
  workDate: string
  dateTo: string | null
  start: string | null
  end: string | null
  minutes: number | null
  reason: string | null
  status: TimeRequestStatus
  needsHr: boolean
  createdAt: string
  manager: { by: string | null; at: string; note: string | null } | null
  hr: { by: string | null; at: string; note: string | null } | null
  canCancel: boolean
}

/** Różnice między odbiciami a grafikiem z ostatnich 14 dni (`GET /me/time-anomalies`). */
export type MyTimeAnomalies = {
  employee: boolean
  /** Ile różnic czeka na wniosek. */
  open: number
  items: {
    date: string
    /** OVERTIME: czas poza grafikiem, SHORTFALL: brak z grafiku, MISSING_EXIT: wejście bez wyjścia. */
    part: 'OVERTIME' | 'SHORTFALL' | 'MISSING_EXIT'
    start: string
    end: string | null
    minutes: number
    plannedStart: string | null
    plannedEnd: string | null
    kinds: TimeRequestKind[]
    request: { id: string; kind: TimeRequestKind; status: TimeRequestStatus } | null
  }[]
}

export type TaskPriority = 'NORMAL' | 'URGENT'
export type TaskStatus = 'OPEN' | 'DONE' | 'ACCEPTED'
export type TaskPerson = { id: string; displayName: string }

export type TaskItem = {
  id: string
  itm8: string | null
  ean: string | null
  name: string
  details: string | null
  checked: boolean
  checkedAt: string | null
  checkedByName: string | null
  note: string | null
}

/** Zadanie sklepu (skopiowane z frontend/src/lib/types.ts, tylko używane pola). */
export type StoreTask = {
  id: string
  title: string
  description: string | null
  dueDate: string | null
  priority: TaskPriority
  status: TaskStatus
  /** Wykonawca; `null` przy zadaniu dla całego sklepu (`forStore`). */
  assignee: TaskPerson | null
  /** Zadanie dla całego sklepu: wykonuje ktokolwiek z dostępem do sklepu. */
  forStore: boolean
  /** Zlecenie kilku osobom naraz: każda ma swoją kopię, tu postęp wszystkich. */
  group: TaskGroup | null
  createdBy: TaskPerson | null
  createdAt: string
  sourceLabel: string | null
  /** Ścieżka strony w aplikacji w przeglądarce (z filtrami), na której zlecono zadanie. */
  sourceUrl: string | null
  doneBy: TaskPerson | null
  doneComment: string | null
  returnComment: string | null
  items: TaskItem[]
  attachments: TaskAttachment[]
  itemCount: number
  checkedCount: number
  canManage: boolean
  canComplete: boolean
}

/** `storeOpen`: otwarte zadania całego sklepu. */
export type TaskCounts = { open: number; waiting: number; storeOpen: number }

export type TaskGroup = {
  id: string
  total: number
  done: number
  members: { taskId: string; person: TaskPerson | null; status: TaskStatus }[]
}

/** Zrzut ekranu przy zadaniu (dodany w przeglądarce). */
export type TaskAttachment = {
  id: string
  fileName: string
  contentType: string
  sizeBytes: number
  createdAt: string
  createdByName: string | null
  canRemove: boolean
}

// Raporty sprzedaży (skopiowane z frontend/src/lib/types.ts, tylko używane pola).

export type SalesComparisonLevel = 'REGION' | 'FAMILY' | 'SUBFAMILY' | 'PRODUCT'

export type ArticleLeafletMark = {
  number: string | null
  title: string | null
  from: string
  to: string
  previous: boolean
  current: boolean
}

export type SalesComparisonRow = {
  /** Kod nomenklatury bez spacji albo ITM8; pusty = sprzedaż bez nomenklatury. */
  code: string
  name: string | null
  ean: string | null
  brand: string | null
  previous: SalesFigures
  current: SalesFigures
  leaflets: ArticleLeafletMark[]
}

export type SalesComparison = {
  previousFrom: string
  previousTo: string
  currentFrom: string
  currentTo: string
  parent: string | null
  level: SalesComparisonLevel
  rows: SalesComparisonRow[]
  previousTotal: SalesFigures
  currentTotal: SalesFigures
}

export type SalesPromotionShare = {
  previousSaleValueGross: number
  currentSaleValueGross: number
  previousMarginValue: number
  currentMarginValue: number
  previousArticles: number
  currentArticles: number
}

export type PeriodSummaryFigures = {
  saleValueNet: number
  saleValueGross: number
  costValue: number
  marginValue: number
  marginPercent: number | null
  lossValue: number
  lossPercent: number | null
  marginAfterLossesValue: number
  marginAfterLossesPercent: number | null
  sharePercent: number | null
}

export type PeriodSummaryLine = {
  kind: 'GROUP' | 'REGION' | 'SUBTOTAL'
  depth: number
  /** Kod rejonu; pusty = bez nomenklatury. */
  code: string | null
  name: string | null
  figures: PeriodSummaryFigures | null
  marginBudgetPercent: number | null
  maxLossPercent: number | null
}

export type PeriodSummary = {
  from: string
  to: string
  lastSaleDate: string | null
  documentsCoveredUntil: string | null
  budgetsSet: boolean
  lines: PeriodSummaryLine[]
  total: PeriodSummaryFigures
}

export type PeriodSummaryLevel = {
  level: SalesComparisonLevel
  rows: { code: string; name: string | null; ean: string | null; brand: string | null; figures: PeriodSummaryFigures }[]
}

export type ProfitTargetProgress = {
  year: number
  from: string
  to: string
  target: { year: number; marginValue: number | null; marginAfterLossesValue: number | null } | null
  marginValue: number | null
  marginAfterLossesValue: number | null
}

export type CounterFigures = {
  quantity: number
  costValue: number
  saleValueGross: number
  saleValueNet: number
  marginValue: number
  marginPercent: number | null
  sharePercent: number | null
  lossValue: number
}

/** Wartości pozycji w kolejności `CounterReport.stores`; `null` = brak sprzedaży w sklepie. */
export type CounterValues = (CounterFigures | null)[]

export type CounterArticle = { itm8: string; name: string | null; brand: string | null; values: CounterValues }

export type CounterReport = {
  from: string
  to: string
  stores: { id: string; number: string; name: string; lastSaleDate: string | null }[]
  total: CounterValues
  regions: {
    code: string
    name: string | null
    values: CounterValues
    families: {
      code: string
      name: string | null
      values: CounterValues
      subfamilies: { code: string; name: string | null; values: CounterValues; articles: CounterArticle[] }[]
    }[]
  }[]
}

export type CounterStoreOption = { id: string; number: string; name: string; partner: boolean }

export type StockGapKind = 'MISSING' | 'ONE_OFF' | 'RUNNING_OUT' | 'WRONG_STOCK'

export type StockGapItem = {
  kind: StockGapKind
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  cadencier: boolean
  stock: number
  typicalWeek: number
  lastSale: string
  silentDays: number
  missedQuantity: number
  coverDays: number | null
  weeklyMargin: number
  lastDeliveryDate: string | null
  supplierName: string | null
}

export type StockGapReport = {
  stockReportGeneratedAt: string | null
  lastSaleDate: string | null
  items: StockGapItem[]
}

// ---------------------------------------------------------------- czat (kopia z frontend/src/lib/types.ts)

export type ChatKind = 'STORE' | 'GROUP' | 'DIRECT'

export type ChatPerson = { id: string; name: string; avatarUrl: string | null }

export type ChatLastMessage = {
  authorId: string | null
  authorName: string | null
  /** Treść skrócona; `null` przy cofniętej albo samych plikach. */
  preview: string | null
  attachmentCount: number
  retracted: boolean
  createdAt: string
}

export type ChatConversation = {
  id: string
  kind: ChatKind
  storeId: string | null
  storeNumber: string | null
  storeName: string | null
  name: string | null
  /** Druga osoba rozmowy dwóch osób. */
  peer: ChatPerson | null
  memberCount: number
  lastMessage: ChatLastMessage | null
  lastMessageAt: string | null
  unread: number
  /** Nieprzeczytane wiadomości, które mnie oznaczają (@osoba, @dział, @wszyscy). */
  unreadMentions: number
  /** Czat bieżącego sklepu (albo rozmowa z osobą z tego sklepu): na górze listy. */
  local: boolean
  canManage: boolean
  /** Nazwę i logo czatu sklepu albo grupy zmienia zarządzający czatem. */
  canEdit: boolean
  /** Logo czatu sklepu albo grupy; `null` = ikona. */
  logoUrl: string | null
}

export type ChatAttachment = {
  id: string
  kind: 'FILE' | 'IMAGE' | 'VIDEO'
  fileName: string
  contentType: string
  sizeBytes: number
}

export type ChatMessage = {
  id: string
  authorId: string | null
  authorName: string | null
  authorAvatarUrl: string | null
  /** Przy cofniętej `null` (poza moderatorem, który widzi treść na szaro). */
  body: string | null
  createdAt: string
  retracted: boolean
  retractedAt: string | null
  retractedByName: string | null
  attachments: ChatAttachment[]
  /** Oznaczenia w treści, każde jako `@label`. */
  mentions: ChatMention[]
  /** Wiadomość oznacza mnie (osobiście, moim działem albo @wszyscy). */
  mentionsMe: boolean
  canRetract: boolean
  canRestore: boolean
}

export type ChatThread = {
  conversation: ChatConversation
  members: ChatPerson[]
  moderator: boolean
  messages: ChatMessage[]
  hasMore: boolean
  reads: { userId: string; lastReadAt: string }[]
  typing: ChatPerson[]
  /** Działy członków rozmowy do oznaczenia (@dział). */
  departments: ChatMentionDepartment[]
  /** `@wszyscy`: w czacie całego sklepu tylko zarządzający czatem. */
  canMentionAll: boolean
}

export type ChatMention = { kind: 'USER' | 'ALL' | 'DEPARTMENT'; id: string | null; label: string }

export type ChatMentionDepartment = { id: string; name: string; memberCount: number }

export type ChatWorking = {
  userId: string
  name: string
  avatarUrl: string | null
  position: string | null
  start: string | null
  end: string | null
  /** Odbite wejście; `null` = jeszcze się nie odbiła albo brak dostępu do Czasu pracy (`ChatPeople.hours`). */
  since: string | null
  /** Odbita karta (także bez dostępu do godzin). */
  punched: boolean
  lastSeenAt: string | null
  online: boolean
}

export type ChatOther = {
  userId: string
  name: string
  avatarUrl: string | null
  rankName: string | null
  lastSeenAt: string | null
  online: boolean
}

/** `hours`: godziny zmian i odbić tylko z dostępem do Czasu pracy. */
export type ChatPeople = { hours: boolean; working: ChatWorking[]; others: ChatOther[] }
