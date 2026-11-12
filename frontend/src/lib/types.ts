/** Typy odpowiadające DTO z backendu (com.intermanage.backend). */

export type PermissionCategory =
  | 'SALES'
  | 'INVENTORY'
  | 'ORDERS'
  | 'STAFF'
  | 'IMPORTS'
  | 'DELI_ORDERS'
  | 'DOCUMENTS'
  | 'DELI_PRICES'
  | 'KSEF_INVOICES'
  | 'DATA_ENTRY'
  | 'SUPPLIERS'
  | 'MEAT_PURCHASES'
  | 'DELI_PRODUCTION'
  | 'STAFF_RECORDS'
  | 'STAFF_SCHEDULE'
  | 'STAFF_TIME'

export type PermissionLevel = 'VIEW' | 'EDIT'

export type Permissions = Partial<Record<PermissionCategory, PermissionLevel>>

export type Me = {
  id: string
  username: string
  displayName: string
  email: string | null
  roleName: string
  owner: boolean
  permissions: Permissions
  /** Nowe konto albo hasło tymczasowe: zanim zrobi cokolwiek innego, musi ustawić własne hasło. */
  passwordChangeRequired: boolean
  /** Sklepy do wyboru w przełączniku: właściciel wszystkie aktywne, pracownik przypisane aktywne. */
  stores: StoreSummary[]
  /** Kolejność kategorii menu bocznego sklepów, które ją zapisały (id sklepu → klucze grup). */
  sidebarOrders: Record<string, string[]>
  /** Własne zdjęcie w tle aplikacji tego konta (Moje konto), `null` = bez zdjęcia. */
  background: UserBackground | null
  /** Zdjęcie profilowe tego konta (Moje konto), `null` = inicjały. */
  avatar: UserAvatar | null
}

export type UserBackground = {
  id: string
  /** Adres pliku: publiczny z CDN (R2) albo backendu dla starszych zdjęć z bazy. */
  url: string
  /** Ile procent zdjęcia przesłania kolor tła pod treścią (0-95), żeby tekst był czytelny. */
  veilPercent: number
}

export type UserAvatar = {
  id: string
  /** Adres pliku: publiczny z CDN (R2) albo backendu dla zdjęć z bazy. */
  url: string
}

export type AvatarChange = {
  avatar: UserAvatar | null
  /** Zdjęcie, które przestało obowiązywać, do „Przywróć”. */
  previousId: string | null
}

/** Czego wymaga przejście na konto zalogowane w tle: nic (właściciel konta wyłączył PIN), PIN albo hasło. */
export type SwitchUnlock = 'NONE' | 'PIN' | 'PASSWORD'

/** Konto zalogowane w tle w tej przeglądarce (Zmień konto). */
export type SwitchAccount = {
  id: string
  displayName: string
  username: string
  avatar: UserAvatar | null
  unlock: SwitchUnlock
}

/** PIN do przełączania na to konto (Moje konto). */
export type PinSettings = {
  hasPin: boolean
  pinRequired: boolean
}

export type BackgroundChange = {
  background: UserBackground | null
  /** Zdjęcie, które przestało obowiązywać, do „Przywróć”. */
  previousId: string | null
}

export type StoreSummary = {
  id: string
  /** Numer punktu sprzedaży z systemu Intermarché, np. 10023. */
  number: string
  name: string
  active: boolean
}

export type Store = StoreSummary & {
  address: string | null
  /** NIP firmy, do której należy sklep; po nim KSeF łączy sklepy jednej firmy. */
  nip: string | null
  /** Pracownicy z przypisanym sklepem (bez właścicieli, którzy widzą wszystkie). */
  userCount: number
  createdAt: string
}

export type StoreRequest = {
  number: string
  name: string
  address: string
  nip: string
  active: boolean
}

export type PermissionCategoryInfo = {
  code: PermissionCategory
  label: string
  description: string
}

export type Role = {
  id: string
  name: string
  description: string | null
  owner: boolean
  permissions: Permissions
  userCount: number
  createdAt: string
}

export type RoleRequest = {
  name: string
  description: string
  permissions: Permissions
}

export type User = {
  id: string
  username: string
  displayName: string
  email: string | null
  roleId: string
  roleName: string
  owner: boolean
  enabled: boolean
  /** Zaproszony, ale jeszcze nie kliknął linku aktywacyjnego. */
  pendingActivation: boolean
  storeIds: string[]
  /** Stanowisko z panelu Pracownicy; to ono ustala rolę konta. */
  positionName: string | null
  createdAt: string
}

export type StaffDay = 'MONDAY' | 'TUESDAY' | 'WEDNESDAY' | 'THURSDAY' | 'FRIDAY' | 'SATURDAY' | 'SUNDAY'

/** Godziny pracy jako GG:MM; koniec przed początkiem to zmiana przez północ. */
export type StaffShift = {
  dayOfWeek: StaffDay
  start: string
  end: string
}

export type StaffPosition = {
  id: string
  departmentId: string
  name: string
  roleId: string
  roleName: string
  /** Godziny do wyboru przy pracowniku, w sklepie z adresu (każdy sklep ma swoje). */
  shifts: StaffShift[]
  employeeCount: number
}

export type StaffDepartment = {
  id: string
  name: string
  positions: StaffPosition[]
}

export type StaffEmployee = {
  id: string
  /** Puste = pracownik bez konta w aplikacji (samo imię i nazwisko). */
  userId: string | null
  hasAccount: boolean
  displayName: string
  username: string | null
  email: string | null
  enabled: boolean
  pendingActivation: boolean
  /** Konto właściciela: stanowisko nie zmienia jego roli. */
  ownerAccount: boolean
  roleName: string
  /** Sklep firmy, w którym pracuje teraz. */
  storeId: string
  storeNumber: string
  storeName: string
  departmentId: string
  departmentName: string
  positionId: string
  positionName: string
  /** Stałe godziny; mogą być spoza godzin stanowiska. */
  shifts: StaffShift[]
  createdAt: string
  /** Koniec pracy w sklepie; da się cofnąć. */
  endedAt: string | null
}

export type ContractKind = 'EMPLOYMENT' | 'MANDATE' | 'B2B'
export type EmploymentType = 'TRIAL' | 'FIXED' | 'INDEFINITE'
export type DisabilityDegree = 'LIGHT' | 'MODERATE' | 'SEVERE'
export type WorkPeriodKind = 'EMPLOYMENT' | 'MANDATE' | 'BUSINESS' | 'ABROAD'
export type EducationLevel =
  'NONE' | 'VOCATIONAL' | 'SECONDARY_VOCATIONAL' | 'GENERAL_SECONDARY' | 'POST_SECONDARY' | 'HIGHER'
export type LeaveKind =
  'ANNUAL' | 'ON_DEMAND' | 'CHILD_CARE' | 'FORCE_MAJEURE' | 'CARE' | 'REHABILITATION' | 'OCCASIONAL' | 'UNPAID'

export type StaffWarning = { severity: 'ERROR' | 'WARNING' | 'INFO'; message: string; basis: string | null }

/** Pracownik na liście Kadr; pola umowy puste, gdy nie ma obowiązującej. */
export type StaffRecordRow = {
  employeeId: string
  displayName: string
  storeId: string
  storeNumber: string
  storeName: string
  departmentId: string
  departmentName: string
  positionName: string
  endedAt: string | null
  contractKind: ContractKind | null
  employmentType: EmploymentType | null
  contractEnd: string | null
  fteNumerator: number | null
  fteDenominator: number | null
  /** Tylko przy umowie o pracę. */
  remainingLeave: number | null
  warningCount: number
  worstWarning: StaffWarning['severity'] | null
}

export type StaffProfile = {
  birthDate: string | null
  educationLevel: EducationLevel | null
  educationEndDate: string | null
  firstJob: boolean
  medicalExamUntil: string | null
  bhpTrainingUntil: string | null
  sanitaryUntil: string | null
}

export type StaffContract = {
  id: string
  kind: ContractKind
  employmentType: EmploymentType | null
  startDate: string
  endDate: string | null
  fteNumerator: number
  fteDenominator: number
  /** Wyjątek z art. 25¹ § 4 KP: nie liczy się do limitu 33 miesięcy. */
  fixedTermExempt: boolean
  /** Aneksy: etat od wskazanego dnia, od najwcześniejszego. {@code fteNumerator} to etat z chwili zawarcia. */
  changes: StaffContractChange[]
}

export type StaffContractChange = { id: string; validFrom: string; fteNumerator: number; fteDenominator: number }

export type StaffWorkPeriod = {
  id: string
  kind: WorkPeriodKind
  employer: string | null
  startDate: string
  endDate: string
  documented: boolean
}

export type StaffDisability = {
  id: string
  degree: DisabilityDegree
  startDate: string
  /** Puste = orzeczenie na stałe. */
  endDate: string | null
  doctorConsent: boolean
}

export type StaffLeave = { id: string; kind: LeaveKind; startDate: string; endDate: string; days: number }

export type SeniorityLength = { years: number; months: number; days: number }

export type StaffRecord = {
  person: {
    employeeId: string
    displayName: string
    username: string
    email: string | null
    storeNumber: string
    storeName: string
    departmentName: string
    positionName: string
    roleName: string
    endedAt: string | null
    /** Numer karty RCP nadany w aplikacji, do wpisania w Symfonii. */
    rcpNumber: number | null
  }
  profile: StaffProfile | null
  contracts: StaffContract[]
  workPeriods: StaffWorkPeriod[]
  disabilities: StaffDisability[]
  /** Z wybranego roku. */
  leaves: StaffLeave[]
  opening: { year: number; days: number } | null
  seniority: {
    work: SeniorityLength
    inCompany: SeniorityLength
    forLeave: SeniorityLength
    /** Dzień 10 lat stażu urlopowego; pusty bez trwającej umowy o pracę. */
    higherLeaveFrom: string | null
  }
  leaveYear: {
    year: number
    /** 20 albo 26 dni z art. 154 KP, przed etatem i częścią roku. */
    fullDays: number
    annualDays: number
    additionalDays: number
    carriedDays: number
    usedDays: number
    remainingDays: number
    usage: Partial<Record<LeaveKind, number>>
  }
  warnings: StaffWarning[]
  /** Uprawnienia z ustawy o rehabilitacji wynikające z orzeczenia. */
  rights: string[]
}

/** Konto bez stanowiska, które można podpiąć jako pracownika. */
export type StaffAccount = {
  id: string
  displayName: string
  username: string
  roleName: string
  owner: boolean
}

/** {@code grantable}: wybierający sam ma wszystkie uprawnienia tej roli. */
export type StaffRoleOption = {
  id: string
  name: string
  grantable: boolean
}

export type CreateUserRequest = {
  username: string
  displayName: string
  email: string
  roleId: string
  storeIds: string[]
}

export type UpdateUserRequest = {
  displayName: string
  email: string | null
  roleId: string
  enabled: boolean
  newPassword?: string
  storeIds: string[]
}

export type ProductImport = {
  id: string
  fileName: string
  /** Data z nagłówka raportu (czas lokalny sklepu, bez strefy); brak, gdy plik jej nie zawierał. */
  reportGeneratedAt: string | null
  importedBy: string | null
  importedAt: string
  totalRows: number
  createdCount: number
  updatedCount: number
  unchangedCount: number
  /** Produkty z bazy, których nie było w tym pliku. Zostają w bazie z danymi z wcześniejszego importu. */
  missingCount: number
  /** Produkty (ITM8), które dostały inny EAN niż w poprzednim imporcie. */
  eanChangedCount: number
  /** EAN-y, które wcześniej należały do innego ITM8. */
  eanMovedCount: number
  /** Szkice artykułów z potwierdzonych plików CADBEZP, których artykuł pojawił się w tym raporcie. */
  draftsMatchedCount: number
  /** Szkice artykułów usunięte, bo pierwszy raport po potwierdzeniu nie miał ich artykułu. */
  draftsRemovedCount: number
}

/** Zmiana przypisania EAN wykryta przy imporcie. */
export type EanChange = {
  itm8: string
  name: string | null
  ean: string
  /** Poprzedni EAN tego ITM8 (zmiana EAN). */
  previousEan: string | null
  /** ITM8, do którego ten EAN należał wcześniej (przeniesienie). */
  previousItm8: string | null
  previousName: string | null
}

export type ProductImportOverview = {
  productCount: number
  imports: ProductImport[]
}

/** Formularz zamówienia garmażu sklepu: działy (zakładki) → kategorie → produkty z kartoteki. */
export type OrderForm = {
  sections: OrderSection[]
}

export type OrderSection = {
  id: string
  name: string
  categories: OrderCategory[]
}

export type OrderCategory = {
  id: string
  name: string
  products: OrderProduct[]
}

export type OrderProduct = {
  /** Pozycja w kategorii (do usuwania). */
  id: string
  productId: string
  itm8: string
  ean: string
  name: string | null
  unit: string | null
  salePrice: number | null
  active: boolean | null
  brand: string | null
  /** W czym zamawia się ten produkt. */
  orderUnit: OrderUnit
}

export type OrderUnit = 'KG' | 'SZT'

export type ProductSearchResult = Omit<OrderProduct, 'productId' | 'orderUnit'>

export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'DECLINED'

/** Zaproszenie do formularza: `formStore` zaprasza, `store` jest zaproszony. */
export type DeliInvitation = {
  id: string
  formStore: StoreSummary
  store: StoreSummary
  status: InvitationStatus
  invitedAt: string
  respondedAt: string | null
}

export type DeliSupplier = {
  store: StoreSummary
  /** Własny formularz sklepu. */
  own: boolean
}

export type DeliOverview = {
  suppliers: DeliSupplier[]
  newIncomingCount: number
  pendingInvitations: DeliInvitation[]
}

/** NEW: dostawca jeszcze go nie otworzył, READ: odczytane, czeka na realizację. */
export type DeliOrderStatus = 'NEW' | 'READ' | 'FULFILLED'

export type DeliOrderSummary = {
  id: string
  formStore: StoreSummary
  store: StoreSummary
  status: DeliOrderStatus
  /** Data bez godziny, RRRR-MM-DD. */
  deliveryDate: string
  /** Data dostawy z chwili złożenia; różna od deliveryDate, gdy ktoś ją zmienił. */
  orderedDeliveryDate: string
  submittedAt: string
  submittedBy: string | null
  itemCount: number
  corrected: boolean
  /** Powstało z połączenia kilku zamówień. */
  merged: boolean
  /** Usunięte u zamawiającego (wycofane) albo u dostawcy. Dane zostają, stronę, która usunęła, można przywrócić. */
  storeDeletedAt: string | null
  formStoreDeletedAt: string | null
}

export type DeliOrderItem = {
  id: string
  productId: string
  itm8: string
  ean: string
  name: string | null
  brand: string | null
  sectionName: string
  categoryName: string
  unit: OrderUnit
  orderedQuantity: number
  quantity: number
  /** Dopisane po złożeniu zamówienia (kiedy i kto); przy złożeniu `null`. */
  addedAt: string | null
  addedBy: string | null
}

export type DeliOrder = Omit<DeliOrderSummary, 'itemCount' | 'corrected' | 'merged'> & {
  deliveryDateChangedAt: string | null
  deliveryDateChangedBy: string | null
  /** Pierwsze otwarcie u dostawcy. */
  readAt: string | null
  readBy: string | null
  /** Zamówienie powstało z połączenia zamówień mergedFrom. */
  mergedAt: string | null
  mergedBy: string | null
  mergedFrom: DeliOrderSummary[]
  /** Zamówienie weszło do połączonego; dopóki połączenie trwa, nie ma go na listach. */
  mergedIntoId: string | null
  /** Połączenie cofnięte: zamówienie nie obowiązuje, źródłowe wróciły na listy. */
  splitAt: string | null
  correctedAt: string | null
  correctedBy: string | null
  fulfilledAt: string | null
  fulfilledBy: string | null
  storeDeletedBy: string | null
  formStoreDeletedBy: string | null
  items: DeliOrderItem[]
  /** Dokumenty ruchu międzymagazynowego, na których jedzie zamówienie (przypina dostawca). */
  documents: DeliOrderDocument[]
}

/** Dokument ruchu międzymagazynowego przy zamówieniu garmażu (bez kwot - moduł nie pokazuje cen). */
export type DeliOrderDocument = {
  documentId: string
  number: string
  documentDate: string
  lineCount: number
  linkedBy: string | null
  linkedAt: string
}

/** Ruch międzymagazynowy dostawcy do przypięcia; {@code linked}, gdy już jest przy tym zamówieniu. */
export type DeliDocumentCandidate = {
  documentId: string
  number: string
  documentDate: string
  lineCount: number
  linked: boolean
}

/** Wgranie raportu Wejścia/Wyjścia (dokumenty magazynowe). */
export type DocumentImport = {
  id: string
  fileName: string
  reportGeneratedAt: string | null
  /** RRRR-MM-DD, zakres dat raportu. */
  periodFrom: string | null
  periodTo: string | null
  importedBy: string | null
  importedAt: string
  totalLines: number
  /** Linie zapisanych typów oraz dokumenty nowe i uzupełnione. */
  savedLines: number
  createdCount: number
  updatedCount: number
  /** Linie typów, których nie obsługujemy, i kody tych typów ("MM"). */
  skippedLines: number
  skippedTypes: string | null
}

/**
 * Typ dokumentu (przyjęcia, likwidacje, zwroty...). {@code code} to kod z systemu sklepu, używany tylko w ścieżkach;
 * w interfejsie pokazujemy wyłącznie pełne nazwy.
 */
export type DocumentTypeSummary = {
  code: string
  label: string
  singular: string
  description: string
  documentCount: number
  lastDocumentDate: string | null
}

export type DocumentsOverview = {
  lastReportGeneratedAt: string | null
  types: DocumentTypeSummary[]
}

/** Dokument na liście. Kwoty jako liczby (zł), data jako RRRR-MM-DDTgg:mm:ss bez strefy; dostawca pusty, gdy różni. */
export type DocumentSummary = {
  id: string
  number: string
  documentDate: string
  supplierCode: string | null
  supplierName: string | null
  supplierDocumentNumber: string | null
  lineCount: number
  purchaseValue: number
  saleValueGross: number
  /** Któraś linia to pozycja "GRATIS..." - dokument może być gratisem. */
  gratisCandidate: boolean
  /** Rejestracja jako gratis od dostawcy; wartością gratisu jest sprzedaż brutto dokumentu. */
  gratis: DocumentGratis | null
  /**
   * Faktury KSeF przyjęcia albo korekty od dostawcy: przypięte i dopasowane po numerze; przy przyjęciu także faktury
   * jego korekt od dostawcy ({@code viaCorrection}).
   */
  ksefInvoices: LinkedKsefInvoice[]
  /** Przyjęcie z przypiętymi korektami: ich liczba i wartości po korektach. */
  correctionTotals: CorrectionTotals | null
  /** Korekta: przyjęcie, do którego ją przypięto. */
  correctionOf: LinkedDelivery | null
  /** Przyjęcie z fakturą: porównanie kwot netto z fakturami ({@code null} bez faktury). */
  invoiceAmountCheck: InvoiceAmountCheck | null
  /** Faktury spoza KSeF wpisane ręcznie (przyjęcia i ich korekty). */
  manualInvoices: ManualInvoice[]
  /** Przyjęcie PZ w zamkniętym raporcie okresowym ({@code null}, gdy jeszcze w żadnym). */
  reportClosing: DocumentReportClosing | null
  /** Ruch międzymagazynowy: sklep zaprzyjaźniony, dla którego go wystawiono. */
  transferTarget: TransferTarget | null
  /** Przyjęcie oznaczone jako błąd (na liście tylko z filtrem „Oznaczone jako błąd”). */
  errorMarkedAt: string | null
  /** Weryfikacja przyjęcia PZ ({@code null} = bez statusu): status, kiedy i kto. */
  verificationStatus: DocumentVerificationStatus | null
  verificationAt: string | null
  verificationBy: string | null
}

/** Weryfikacja przyjęcia PZ: w trakcie (czeka na kierownika) albo zweryfikowane. */
export type DocumentVerificationStatus = 'IN_PROGRESS' | 'VERIFIED'

/** Zamknięty raport przyjęć, w którym przyjęcie poszło razem ze swoim okresem (daty RRRR-MM-DD). */
export type DocumentReportClosing = {
  id: string
  periodFrom: string
  periodTo: string
  closedAt: string
}

/** Faktura spoza KSeF: dostawca z listy dostawców sklepu i numer wpisany ręcznie. */
export type ManualInvoice = {
  id: string
  invoiceNumber: string
  issueDate: string | null
  supplierId: string
  supplierName: string
  createdAt: string
  createdBy: string | null
  /** Dołączony plik faktury (PDF albo zdjęcie); treść pod `…/manual-invoices/{id}/file`. */
  file: ManualInvoiceFile | null
}

export type ManualInvoiceFile = {
  fileName: string
  contentType: string
  sizeBytes: number
  uploadedAt: string
}

/**
 * Suma netto faktur przyjęcia (z fakturami jego korekt od dostawcy) i wartość zakupu netto po korektach;
 * {@code difference} = faktury - przyjęcie. {@code confirmedAt}: różnicę ktoś potwierdził (dla tych kwot).
 */
export type InvoiceComparisonStatus =
  'MATCH' | 'PRICE' | 'QUANTITY' | 'MISSING_ON_INVOICE' | 'MISSING_IN_DELIVERY' | 'NO_CODE' | 'DEPOSIT'

/** Artykuł przyjęcia i pozycje faktur z tym samym EAN; {@code difference} = faktura - przyjęcie. */
export type InvoiceComparisonRow = {
  status: InvoiceComparisonStatus
  ean: string | null
  itm8: string | null
  name: string | null
  invoiceName: string | null
  deliveryQuantity: number | null
  deliveryPrice: number | null
  deliveryValue: number | null
  invoiceQuantity: number | null
  invoicePrice: number | null
  invoiceValue: number | null
  difference: number | null
  /** Artykuły przyjęcia w wierszu (kilka, gdy połączone). */
  itm8s: string[]
  /** Pozycje faktury w wierszu: "ean:…" albo "name:…" (bez kodu). */
  invoiceKeys: string[]
  /** Ręczne połączenia, które złożyły wiersz (puste, gdy połączył go EAN). */
  matchIds: string[]
}

/** Przyjęcie porównane z XML faktur KSeF pozycja po pozycji; {@code unavailable}: faktury, których nie dało się pobrać. */
export type InvoiceComparison = {
  invoices: string[]
  unavailable: { invoiceNumber: string; message: string }[]
  /** Pozycje faktur bez kaucji; kaucje są osobno w `depositNet`, bo przyjęcie nigdy ich nie ma. */
  invoiceNet: number
  depositNet: number
  deliveryValue: number
  rows: InvoiceComparisonRow[]
}

export type InvoiceAmountCheck = {
  invoiceNet: number
  documentValue: number
  difference: number
  matches: boolean
  confirmedAt: string | null
  confirmedBy: string | null
}

export type CorrectionTotals = {
  count: number
  purchaseValue: number
  saleValueGross: number
}

export type LinkedDelivery = {
  id: string
  number: string
  documentDate: string
  supplierName: string | null
}

/** Korekta (błędu sklepu, od dostawcy) przypięta do przyjęcia. */
export type DeliveryCorrection = {
  id: string
  type: string
  typeSingular: string
  number: string
  documentDate: string
  lineCount: number
  purchaseValue: number
  saleValueGross: number
  linkedAt: string
  linkedBy: string | null
}

/**
 * Linia przyjęcia po korektach. {@code key}: id linii przyjęcia albo linii korekty (artykuł dopisany korektą);
 * {@code originalQuantity} / {@code originalPurchaseValue}: z przyjęcia, {@code null} przy artykule dopisanym.
 */
export type CorrectedLine = {
  key: string
  lineNumber: number
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  unit: string | null
  nomenclatureCode: string | null
  quantity: number
  purchasePrice: number | null
  salePriceDocument: number | null
  purchaseValue: number | null
  saleValueGross: number | null
  saleValueNet: number | null
  vatRate: number | null
  vatValue: number | null
  marginValue: number | null
  marginPercent: number | null
  originalQuantity: number | null
  originalPurchaseValue: number | null
  corrected: boolean
}

export type CorrectedDelivery = {
  purchaseValue: number
  saleValueGross: number
  lines: CorrectedLine[]
}

/** Dokument do przypięcia: korekta do przyjęcia albo przyjęcie do korekty; {@code commonItems}: wspólne artykuły. */
export type CorrectionCandidate = {
  id: string
  type: string
  typeSingular: string
  number: string
  documentDate: string
  supplierName: string | null
  supplierDocumentNumber: string | null
  lineCount: number
  purchaseValue: number
  commonItems: number
}

/**
 * Faktura KSeF dokumentu; {@code matchedByNumber}: dopasowana sama po numerze dokumentu dostawcy w przyjęciu (nie da
 * się jej odpiąć - zmienia się ją poprawką numeru).
 */
export type LinkedKsefInvoice = {
  id: string
  ksefNumber: string
  invoiceNumber: string
  issueDate: string | null
  sellerName: string | null
  netAmount: number | null
  grossAmount: number | null
  correction: boolean
  matchedByNumber: boolean
  /** Faktura korekty od dostawcy przypiętej do przyjęcia (numer korekty); odpina się ją w korekcie. */
  viaCorrection: string | null
}

/**
 * Korekty z KSeF faktury przyjęcia PZ (po fakturze korygowanej z XML korekty, który pobiera się w tle);
 * {@code pendingCount}: korekty dostawcy, których XML jeszcze nie odczytano.
 */
export type DeliveryKsefCorrections = {
  corrections: DeliveryKsefCorrection[]
  pendingCount: number
}

/**
 * Korekta z KSeF przyjęcia: {@code documents} to korekty od dostawcy (WZ) z tą fakturą ({@code attached}: przypięta do
 * tego przyjęcia), {@code candidates} - podpowiedzi WZ (ten sam dostawca, zbliżona kwota i data), gdy żadnej nie ma.
 */
export type DeliveryKsefCorrection = {
  id: string
  ksefNumber: string
  invoiceNumber: string
  issueDate: string
  sellerName: string | null
  netAmount: number
  grossAmount: number
  correctedInvoiceNumber: string
  documents: {
    id: string
    type: string
    typeSingular: string
    number: string
    documentDate: string
    purchaseValue: number
    attached: boolean
    otherDeliveryBon: string | null
  }[]
  candidates: {
    id: string
    number: string
    documentDate: string
    purchaseValue: number
    difference: number
    attached: boolean
  }[]
}

/** Faktura do przypięcia; {@code difference}: różnica kwoty netto od wartości dokumentu (bez znaku). */
/**
 * Propozycja automatycznego przypięcia faktury KSeF do przyjęcia bez faktury: ten sam dostawca, kwota netto różna
 * od wartości zakupu najwyżej o 10 zł ({@code difference} bez znaku).
 */
export type AutoInvoiceMatch = {
  documentId: string
  documentNumber: string
  documentDate: string
  supplierName: string | null
  supplierDocumentNumber: string | null
  purchaseValue: number
  invoiceId: string
  ksefNumber: string
  invoiceNumber: string
  issueDate: string
  sellerName: string | null
  netAmount: number
  difference: number
}

export type KsefInvoiceCandidate = {
  id: string
  ksefNumber: string
  invoiceNumber: string
  issueDate: string | null
  sellerName: string | null
  sellerNip: string
  netAmount: number | null
  grossAmount: number | null
  correction: boolean
  sameSupplier: boolean
  difference: number
  /** Numery dokumentów sklepu, do których faktura jest już przypięta. */
  documents: string[]
}

/** Przyjęcie albo korekta od dostawcy do przypięcia faktury. */
export type DocumentCandidate = {
  id: string
  type: string
  typeSingular: string
  number: string
  documentDate: string
  supplierName: string | null
  supplierDocumentNumber: string | null
  purchaseValue: number
  sameSupplier: boolean
  difference: number
}

/** Za co jest gratis: handel / ekspozycja albo pokrycie strat. */
export type GratisKind = 'TRADE' | 'LOSSES'

export type DocumentGratis = {
  supplierId: string
  /** Pusty u dostawcy dodanego ręcznie. */
  supplierCode: string | null
  supplierName: string
  kind: GratisKind
  /** Miesiąc wybrany ręcznie (RRRR-MM); pusty = miesiąc daty dokumentu. */
  reportMonth: string | null
  /** Miesiąc, do którego gratis liczy się w raporcie (RRRR-MM). */
  effectiveMonth: string
  registeredBy: string | null
  registeredAt: string
}

/**
 * Dostawca, który ostatnio przywoził artykuły z dokumentu (z przyjęć PZ). Przy bazie (538, 88110) {@code brand} to marka
 * artykułów. {@code supplierId} pusty, gdy dostawcy nie ma na liście sklepu.
 */
export type GratisSupplierSuggestion = {
  supplierId: string | null
  supplierCode: string
  supplierName: string | null
  brand: string | null
  lastDeliveryDate: string
  productCount: number
}

/** Dostawca sklepu: z listy z systemu sklepu albo dodany ręcznie ({@code manual}, bez kodu). */
/** Skopiowane makro zakładania dostawcy w Mercalys: kod zarezerwowany do wgrania nowej listy dostawców. */
export type MercalysSupplierCreation = {
  id: string
  nip: string
  code: string
  name: string
  createdAt: string
  createdBy: string | null
}

/** Dane do makra nowego dostawcy, przycięte do pól Mercalys (wielkie litery bez polskich znaków). */
export type MercalysSupplierDraft = {
  nip: string
  code: string
  keyword: string
  name1: string
  name2: string
  street: string
  postalCode: string
  city: string
  fromGus: boolean
  gusProblem: string | null
  previous: MercalysSupplierCreation | null
}

export type Supplier = {
  id: string
  code: string | null
  keyword: string | null
  name: string
  nip: string | null
  city: string | null
  manual: boolean
  website: string | null
  phone: string | null
  email: string | null
  contactCount: number
  /** Pierwszy przedstawiciel z kartoteki dostawców. */
  representative: SupplierContact | null
}

/** Kto to jest u dostawcy (SupplierContactKind.java). */
export type SupplierContactKind = 'REPRESENTATIVE' | 'ORDERS' | 'ACCOUNTING' | 'COMPLAINTS' | 'OTHER'

export type SupplierContact = {
  id: string
  kind: SupplierContactKind
  name: string | null
  jobTitle: string | null
  phone: string | null
  email: string | null
  notes: string | null
  createdAt: string
  updatedAt: string | null
  updatedBy: string | null
}

/** Karta dostawcy: dane z listy z systemu sklepu (u dodanego ręcznie z aplikacji) i kontakty. */
export type SupplierDetails = {
  id: string
  code: string | null
  keyword: string | null
  name: string
  nip: string | null
  address: string | null
  postalCode: string | null
  city: string | null
  origin: string | null
  manual: boolean
  listReportGeneratedAt: string | null
  createdAt: string
  createdBy: string | null
  website: string | null
  email: string | null
  phone: string | null
  notes: string | null
  detailsUpdatedAt: string | null
  detailsUpdatedBy: string | null
  contacts: SupplierContact[]
  /** Pozostałe sklepy firmy (ten sam NIP): strona, telefon, e-mail, notatki i kontakty są z nimi wspólne. */
  sharedWithStores: string[]
}

/** Wgranie raportu "Lista dostawców". */
export type SupplierImport = {
  id: string
  fileName: string
  reportGeneratedAt: string | null
  importedBy: string | null
  importedAt: string
  totalRows: number
  createdCount: number
  updatedCount: number
  missingCount: number
}

export type SupplierImportOverview = {
  supplierCount: number
  manualCount: number
  imports: SupplierImport[]
}

/** Raporty z Importu danych (nazwy jak w backendzie, `ImportReportKind`). */
export type ImportReportKind =
  'PRODUCTS' | 'PRODUCT_CODES' | 'PROMOTIONS' | 'SALES' | 'DOCUMENTS' | 'SUPPLIERS' | 'NOMENCLATURES'

/** Instrukcja właściciela do raportu: jak wygenerować plik i w jakim folderze go szukać. */
export type ImportGuide = {
  kind: ImportReportKind
  content: string
  folderPath: string | null
  createdBy: string | null
  createdAt: string
}

/** Wgranie listy bieżących i przyszłych promocji (Taryfy.pdf); {@code missingCount}: promocje z zakresu raportu, których w nim zabrakło. */
export type PromotionImport = {
  id: string
  fileName: string
  reportGeneratedAt: string | null
  /** RRRR-MM-DD: zakres dat końca promocji objęty raportem. */
  endFrom: string | null
  endTo: string | null
  importedBy: string | null
  importedAt: string
  totalRows: number
  createdCount: number
  updatedCount: number
  missingCount: number
}

export type PromotionImportOverview = {
  /** Promocje, które trwają albo dopiero się zaczną. */
  activeCount: number
  imports: PromotionImport[]
}

/** Wgranie multikodów: pary ITM8 + EAN; {@code missingCount}: pary sklepu, których nie było w pliku. */
export type ProductCodeImport = {
  id: string
  fileName: string
  reportGeneratedAt: string | null
  importedBy: string | null
  importedAt: string
  totalRows: number
  createdCount: number
  updatedCount: number
  missingCount: number
}

export type ProductCodeImportOverview = {
  /** Pary i artykuły z najnowszych multikodów. */
  codeCount: number
  articleCount: number
  imports: ProductCodeImport[]
}

/**
 * Wgranie raportu sprzedaży wg artykułu i dnia. Zapisana sprzedaż się nie zmienia: dni, które już były w bazie,
 * są pomijane, a {@code conflictCount} mówi, ile z nich ma w pliku inne wartości niż zapisane.
 */
export type SalesImport = {
  id: string
  fileName: string
  reportGeneratedAt: string | null
  /** RRRR-MM-DD */
  periodFrom: string
  periodTo: string
  importedBy: string | null
  importedAt: string
  totalRows: number
  createdCount: number
  existingCount: number
  conflictCount: number
  /** Wiersze z dnia, w którym wygenerowano raport: dzień niezakończony, nie został zapisany. */
  incompleteCount: number
}

export type SalesImportOverview = {
  rowCount: number
  /** RRRR-MM-DD, puste, gdy sklep nie ma jeszcze sprzedaży. */
  firstSaleDate: string | null
  lastSaleDate: string | null
  imports: SalesImport[]
}

export type NomenclatureImport = {
  id: string
  fileName: string
  reportGeneratedAt: string | null
  importedBy: string | null
  importedAt: string
  totalRows: number
  createdCount: number
  updatedCount: number
  /** Znane pozycje, których zabrakło w pliku; zostają w bazie. */
  missingCount: number
}

export type NomenclatureImportOverview = {
  regionCount: number
  familyCount: number
  subfamilyCount: number
  imports: NomenclatureImport[]
}

/** Kafelki raportów sprzedaży: za jakie dni jest sprzedaż i z kiedy jest najnowszy raport. */
export type SalesReportsOverview = {
  /** RRRR-MM-DD, puste, gdy sklep nie ma jeszcze sprzedaży. */
  firstSaleDate: string | null
  lastSaleDate: string | null
  lastReportGeneratedAt: string | null
}

/**
 * Sprzedaż za okres; % marży i zmiany liczy front z sum. W raporcie kosztów garmażu koszt to wartość protokołów
 * reprezentacji, a koszt i marża są puste poniżej całego garmażu (kosztu nie da się przypisać do rodzin i artykułów).
 */
export type SalesFigures = {
  quantity: number
  /** Koszt zakupu FIFO netto (w kosztach garmażu: protokoły reprezentacji). */
  costValue: number | null
  saleValueGross: number
  saleValueNet: number
  marginValue: number | null
}

/** Skąd są koszty garmażu: najnowszy raport dokumentów i do którego dnia sięgają wgrane raporty (RRRR-MM-DD). */
export type DeliCostsOverview = {
  documentsReportGeneratedAt: string | null
  costsCoveredUntil: string | null
}

export type SalesComparisonLevel = 'REGION' | 'FAMILY' | 'SUBFAMILY' | 'PRODUCT'

/** Raport lady: sumy pozycji w jednym sklepie; marża % od netto, udział w sprzedaży brutto wybranych rejonów. */
export type CounterFigures = {
  quantity: number
  costValue: number
  saleValueGross: number
  saleValueNet: number
  marginValue: number
  marginPercent: number | null
  sharePercent: number | null
}

/** Wartości pozycji w kolejności {@code CounterReport.stores}; {@code null} = brak sprzedaży w sklepie. */
export type CounterValues = (CounterFigures | null)[]

export type CounterReport = {
  from: string
  to: string
  regionCodes: string[]
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
      subfamilies: {
        code: string
        name: string | null
        values: CounterValues
        articles: { itm8: string; name: string | null; brand: string | null; values: CounterValues }[]
      }[]
    }[]
  }[]
}

export type CounterStoreOption = { id: string; number: string; name: string; partner: boolean }

export type CounterMetric = 'QUANTITY' | 'COST' | 'SALE' | 'MARGIN' | 'MARGIN_PERCENT' | 'SHARE'

export type SalesComparisonRow = {
  /** Kod nomenklatury bez spacji ("01", "0101", "010101") albo ITM8; pusty = sprzedaż bez nomenklatury. */
  code: string
  /** Pusta, gdy kodu nie ma w nomenklaturze sklepu albo artykułu w kartotece. */
  name: string | null
  ean: string | null
  brand: string | null
  previous: SalesFigures
  current: SalesFigures
  /** Sprzedaż drugiego sklepu w aktualnym okresie (porównanie ze sklepem); bez porównania null. */
  other: SalesFigures | null
}

export type SalesComparison = {
  previousFrom: string
  previousTo: string
  currentFrom: string
  currentTo: string
  /** Rozwinięta pozycja; pusty tekst = cały sklep, null = sprzedaż bez nomenklatury. */
  parent: string | null
  level: SalesComparisonLevel
  rows: SalesComparisonRow[]
  previousTotal: SalesFigures
  currentTotal: SalesFigures
  /** Suma drugiego sklepu w aktualnym okresie; bez porównania ze sklepem null. */
  otherTotal: SalesFigures | null
}

/** Pozycja nomenklatury sklepu (filtr raportów, ścieżka artykułu). */
export type NomenclatureOption = {
  /** Bez spacji: "01", "0101", "010101". */
  code: string
  name: string
  level: Exclude<SalesComparisonLevel, 'PRODUCT'>
}

export type TopSalesMetric = 'QUANTITY' | 'MARGIN' | 'MARGIN_PERCENT'
export type TopSalesLevel = 'STORE' | 'REGION' | 'FAMILY' | 'SUBFAMILY'

export type TopSalesPartner = { id: string; number: string; name: string }

export type TopSalesOtherStore = {
  storeId: string
  /** Miejsce w tej samej pozycji nomenklatury w tamtym sklepie. */
  rank: number
  quantity: number
  marginValue: number
  marginPercent: number | null
}

export type TopSalesRow = {
  rank: number
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  quantity: number
  saleValueGross: number
  saleValueNet: number
  marginValue: number
  marginPercent: number | null
  others: TopSalesOtherStore[]
}

export type TopSalesGroup = {
  /** Pusty = bez nomenklatury, null = cały sklep. */
  code: string | null
  name: string | null
  articleCount: number
  quantity: number
  saleValueNet: number
  marginValue: number
  marginPercent: number | null
  rows: TopSalesRow[]
}

export type TopSalesReport = {
  from: string
  to: string
  metric: TopSalesMetric
  level: TopSalesLevel
  limit: number
  minQuantity: number
  maxPromotionMarginPercent: number
  rankStoreId: string
  /** Sklep rankingu na początku, dalej porównywane. */
  stores: {
    id: string
    number: string
    name: string
    articleCount: number
    promotionSkipped: number
    noCostSkipped: number
  }[]
  groups: TopSalesGroup[]
}

/** Sprzedaż dzień po dniu do wykresu; każdy dzień okresu, bez sprzedaży z zerami. */
/** Co pokazuje wykres wyników sklepu na pulpicie: ilość, sprzedaż brutto albo marża zł (ustawienie konta). */
export type DashboardChartMetric = 'QUANTITY' | 'SALES' | 'MARGIN'

export type DashboardSettings = { salesChartMetric: DashboardChartMetric }

export type SalesHistory = {
  from: string
  to: string
  /** {@code lossValue}: straty dnia, likwidacje w wartości zakupu netto (0 = bez likwidacji). */
  days: { date: string; figures: SalesFigures; lossValue: number }[]
  total: SalesFigures
  /** Straty okresu (likwidacje, wartość zakupu netto). */
  lossValue: number
}

/** Artykuł z kartoteki: ceny, stan i rotacja z ostatniego raportu kartoteki, w którym był. */
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
  /** Z kodu stawki z kartoteki, bez niego z ostatniego dokumentu artykułu. */
  vatPercent: number | null
  /** STAN, może być ujemny. */
  stock: number | null
  rotation: number | null
  caseSize: number | null
  reportGeneratedAt: string | null
  /** Rejon, rodzina, podrodzina (te, które są w nomenklaturze sklepu). */
  nomenclaturePath: NomenclatureOption[]
  /** Artykuł na bieżącej liście cadencier sklepu. */
  cadencier: boolean
  /** CENA_CAD z ostatniego raportu kartoteki (cena narzucona przez centralę). */
  cadencierPrice: number | null
  /** Trwająca dziś promocja z kasy; `salePrice` jest wtedy ceną regularną. */
  promoPrice: number | null
  promoFrom: string | null
  promoTo: string | null
}

/**
 * Kody artykułu w sklepie: główny EAN z kartoteki (`mainEan`, pusty spoza kartoteki), EAN-y z multikodów (obowiązujące
 * i dawne) i pary ITM8 + EAN z raportów kartoteki, a przy każdym EAN-ie inne artykuły, do których jest przypisany teraz.
 */
export type ArticleCodes = {
  itm8: string
  name: string | null
  mainEan: string | null
  eans: ArticleCode[]
}

export type ArticleCode = {
  ean: string
  /** Bieżący EAN z kartoteki. */
  main: boolean
  /** Pierwszy i ostatni raport kartoteki z tą parą ITM8 + EAN. */
  catalogFirstSeen: string | null
  catalogLastSeen: string | null
  /** Wpis z multikodów; `current` = w najnowszym raporcie multikodów. */
  multicode: {
    prio: boolean
    current: boolean
    saleUnit: string | null
    salePack: number | null
    priceFactor: number | null
    salePrice: number | null
    removalDate: string | null
    firstSeen: string | null
    lastSeen: string | null
  } | null
  /** Inne artykuły z tym kodem: MAIN (ich główny EAN), MULTICODE, CATALOG (para z raportu kartoteki). */
  otherArticles: { itm8: string; name: string | null; source: 'MAIN' | 'MULTICODE' | 'CATALOG' }[]
}

/**
 * Dostawa artykułu (przyjęcie PZ): ilość i cena zakupu netto sztuki po korektach przypiętych do przyjęcia.
 * `original*` to wartości z bonu, gdy korekty je zmieniły, inaczej `null`.
 */
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
  /** Kody MEA artykułu z dostawy z bazy tego przyjęcia (Nr_Bl = nr dok. dostawcy), pusta bez dostawy z bazy. */
  meaCodes: string[]
}

/** Protokół artykułu: dokument inny niż przyjęcie i korekta; {@code label} to rodzaj do pokazania. */
export type ArticleProtocol = {
  documentId: string
  documentType: string
  documentNumber: string
  documentDate: string
  label: string
  gratis: boolean
  quantity: number
}

/**
 * Strona listy dokumentów (po 100). {@code type.documentCount} to wszystkie dokumenty typu, {@code matchingCount}
 * i sumy - pasujące do wyszukiwania (wiersz "Razem").
 */
export type DocumentPage = {
  type: DocumentTypeSummary
  lastReportGeneratedAt: string | null
  documents: DocumentSummary[]
  page: number
  hasMore: boolean
  matchingCount: number
  purchaseTotal: number
  saleTotal: number
}

/**
 * Zamknięty raport przyjęć za okres (daty RRRR-MM-DD). {@code pendingChanges}: przyjęcia z tego okresu zmienione po
 * zamknięciu, trafią do następnego raportu; {@code cancellable}: ostatnie zamknięcie, da się je cofnąć.
 */
export type DeliveryReportClosing = {
  id: string
  periodFrom: string
  periodTo: string
  closedAt: string
  closedBy: string | null
  cancelledAt: string | null
  pendingChanges: number
  cancellable: boolean
}

/** Dostawca pomijany w raporcie przyjęć (kod z linii dokumentów). */
export type DeliveryReportExcludedSupplier = {
  code: string
  name: string | null
}

export type DeliveryReportClosingsOverview = {
  closings: DeliveryReportClosing[]
  pendingChanges: number
  excludedSuppliers: DeliveryReportExcludedSupplier[]
}

export type DocumentLine = {
  id: string
  lineNumber: number
  supplierCode: string | null
  supplierName: string | null
  nomenclatureCode: string | null
  nomenclatureName: string | null
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  unit: string | null
  quantity: number
  purchasePrice: number | null
  salePriceCurrent: number | null
  salePriceDocument: number | null
  purchaseValue: number | null
  saleValueGross: number | null
  saleValueNet: number | null
  vatRate: number | null
  vatValue: number | null
  marginValue: number | null
  marginPercent: number | null
}

export type Document = DocumentSummary & {
  type: string
  typeLabel: string
  typeSingular: string
  /** Typ można rejestrować jako gratis (przeniesienia stanów). */
  gratisAllowed: boolean
  /** Do dokumentu można przypiąć fakturę z KSeF (przyjęcia i korekty od dostawcy). */
  ksefLinkable: boolean
  /** Do dokumentu można wpisać fakturę spoza KSeF (przyjęcia i ich korekty). */
  manualInvoiceAllowed: boolean
  /** Numer dokumentu dostawcy z raportu; inny niż obowiązujący, gdy ktoś go poprawił. */
  reportSupplierDocumentNumber: string | null
  /** Ręczna poprawka numeru dokumentu dostawcy ({@code null} = numer z raportu). */
  supplierDocumentNumberEditedAt: string | null
  supplierDocumentNumberEditedBy: string | null
  /** Numer dokumentu dostawcy można poprawić (przyjęcia PZ). */
  supplierDocumentNumberEditable: boolean
  /** Przyjęcie PZ można oznaczyć jako błąd: znika z listy dokumentów i z raportów. */
  errorMarkable: boolean
  /** Kto oznaczył dokument jako błąd ({@code errorMarkedAt} w podsumowaniu). */
  errorMarkedBy: string | null
  updatedAt: string
  lines: DocumentLine[]
  /** Zamówienia garmażu jadące na tym dokumencie (tylko ruchy międzymagazynowe). */
  deliOrders: LinkedDeliOrder[]
  /** Dokument można wystawić dla sklepu zaprzyjaźnionego (ruchy międzymagazynowe). */
  transferTargetAllowed: boolean
  /** Do przyjęcia PZ można przypinać korekty; wartości i wydruk bonu liczą się razem z nimi. */
  correctable: boolean
  /** Korekty przypięte do przyjęcia, od najstarszej. */
  corrections: DeliveryCorrection[]
  /** Przyjęcie po korektach; {@code null}, gdy nie ma korekt. */
  corrected: CorrectedDelivery | null
  /** Korektę (błędu sklepu, od dostawcy) można przypiąć do przyjęcia. */
  attachableToDelivery: boolean
  /** Ruch międzymagazynowy innego sklepu, który ten dokument potwierdza jako przyjęty. */
  transferReceiptOf: { id: string; number: string; fromStore: StoreSummary } | null
}

export type LinkedDeliOrder = {
  orderId: string
  storeNumber: string
  storeName: string
  deliveryDate: string
}

/** Skład na 1 kg wyrobu albo na cały przepis (wtedy z wydajnością w kg). */
export type CalculationMode = 'PER_KG' | 'PER_BATCH'

/** Gotowy wyrób z kartoteki (nomenklatury 24, 36, 46); {@code calculationId}, gdy ma już kalkulację. */
export type FinishedProduct = {
  productId: string
  itm8: string
  ean: string
  name: string | null
  brand: string | null
  nomenclature: string | null
  unit: string
  /** Obecna cena na półce. */
  salePrice: number | null
  active: boolean | null
  calculationId: string | null
}

/** Kalkulacja na liście: koszt 1 kg z zapamiętanych cen składników i cena sprzedaży (narzut + VAT, do ,99 zł). */
export type PriceCalculationSummary = {
  id: string
  /** Pusty tylko w kalkulacjach sprzed powiązania z kartoteką. */
  product: FinishedProduct | null
  name: string
  mode: CalculationMode
  batchYield: number | null
  vatRate: number
  /** Marża doliczana do kosztu: docelowa wyrobu, a bez niej standardowa. */
  markupPercent: number
  /** Marża ustawiona na wyrobie. */
  targetMarkup: number | null
  /** 0 = kalkulacja bez przepisu (z automatycznego importu). */
  itemCount: number
  totalCost: number
  /** Pusty, gdy przy całym przepisie brakuje wydajności. */
  costPerKg: number | null
  /** Cena do eksportu: własna, a bez niej wyliczona. */
  salePrice: number | null
  /** Koszt + marża + VAT, w górę do ,99 zł. */
  calculatedPrice: number | null
  /** Własna cena zamiast wyliczonej. */
  manualPrice: number | null
  manualPriceSetBy: string | null
  manualPriceSetAt: string | null
  /** Składniki bez ceny - koszt jest wtedy zaniżony. */
  missingPriceCount: number
  pricesRefreshedAt: string | null
  updatedBy: string | null
  updatedAt: string
}

export type PriceSource = 'DELIVERY' | 'CATALOG'

/**
 * Produkt z kartoteki jako składnik. {@code unit}: jednostka ilości (kg, L, szt.), {@code unitCost}: cena zakupu
 * tej jednostki z ostatniej dostawy PZ albo z kartoteki. W wynikach wyszukiwania {@code quantity}, {@code cost}
 * i {@code pricedAt} są puste.
 */
export type PriceProduct = {
  productId: string
  itm8: string
  ean: string
  name: string | null
  brand: string | null
  unit: string
  /** Średnia waga 1 sztuki w kg (składniki liczone na sztuki), zapamiętana dla produktu w sklepie. */
  pieceWeight: number | null
  unitCost: number | null
  priceSource: PriceSource | null
  priceDocumentId: string | null
  priceDocumentNumber: string | null
  priceDate: string | null
  active: boolean | null
  quantity: number | null
  cost: number | null
  pricedAt: string | null
}

export type PriceCalculation = Omit<PriceCalculationSummary, 'itemCount'> & {
  items: PriceProduct[]
}

/** Kalkulacja do pliku CADBEZP: ceny zakupu za sprzedawaną sztukę i dostawca z kartoteki (osobny plik na dostawcę). */
export type PurchaseFileLine = {
  calculationId: string
  name: string
  ean: string
  supplier: string | null
  currentPrice: number | null
  purchasePrice: number
  /** Składniki bez ceny: koszt jest zaniżony. */
  missingPriceCount: number
  /** Artykuł na sztuki, a koszt kalkulacji jest na 1 kg. */
  countedInPieces: boolean
}

/** Sklep zaprzyjaźniony: zakładka u góry kalkulatora. */
export type PartnerStore = {
  id: string
  number: string
  name: string
}

/** Kalkulacja sklepu zaprzyjaźnionego z jego ceną i artykułem, do którego sklep ją przypisał. */
export type PartnerCalculation = {
  calculationId: string
  sourceProductId: string
  name: string
  ean: string
  /** Cena sąsiada; przy `withoutRecipe` jego cena z półki. */
  salePrice: number | null
  vatRate: number
  withoutRecipe: boolean
  link: {
    id: string
    /** Z automatycznego przypisania: do sprawdzenia. */
    auto: boolean
    itm8: string
    ean: string
    name: string | null
    brand: string | null
    /** Nasza cena na półce. */
    salePrice: number | null
  } | null
}

/** Automatyczne przypisanie: nowe przypisania ({@code ids}, do cofnięcia) i ile wyrobów zostało bez artykułu. */
export type AutoLink = {
  ids: string[]
  left: number
}

/** Kalkulacje bez przepisu założone importem ({@code ids}, do cofnięcia) i wyroby pominięte, bo już mają kalkulację. */
export type CalculationImport = {
  ids: string[]
  skipped: number
}

export type PriceCalculationRequest = {
  productId: string
  mode: CalculationMode
  batchYield: number | null
  vatRate: number
  /** Marża docelowa w procentach; {@code null} - standardowa. */
  targetMarkup: number | null
  /** Własna cena sprzedaży 1 kg; {@code null} - wyliczona. */
  manualPrice: number | null
  /** {@code pieceWeight}: waga 1 sztuki w kg, tylko przy składnikach na sztuki; {@code null} ją czyści. */
  items: { productId: string; quantity: number; pieceWeight: number | null }[]
}

/**
 * Podgląd "Odśwież ceny" dla kalkulacji: cena teraz (własna albo wyliczona), wyliczona przed i po odświeżeniu.
 * {@code needsDecision}: ma własną cenę, a wyliczona się zmienia, albo wyliczona zmienia się o 10% i więcej.
 */
export type PriceChange = {
  calculationId: string
  name: string
  ean: string | null
  currentPrice: number | null
  manualPrice: number | null
  previousCalculatedPrice: number | null
  newCalculatedPrice: number | null
  changePercent: number | null
  needsDecision: boolean
}

/** KEEP: zostaje cena sprzed odświeżenia; ACCEPT: nowa wyliczona. */
export type RefreshChoice = 'KEEP' | 'ACCEPT'

/** Połączenie sklepu z KSeF bez sekretów; stan pobierania faktur (wspólny dla firmy) jest w {@link KsefOverview}. */
export type KsefConnection = {
  nip: string
  certificateSubject: string
  certificateValidTo: string
  updatedBy: string | null
  updatedAt: string
  testedAt: string | null
  testOk: boolean | null
  testMessage: string | null
}

/** Firma (NIP) połączona z KSeF w innych sklepach użytkownika - może do niej podłączyć sklep bez certyfikatu. */
export type KsefCompany = {
  nip: string
  stores: string[]
}

/** IDWew sklepu: faktury z nim w Podmiocie 3 trafiają do listy tego sklepu. */
export type KsefInternalId = {
  id: string
  internalId: string
  name: string | null
  createdBy: string | null
  createdAt: string
}

export type KsefSkippedSupplier = {
  id: string
  nip: string
  name: string | null
  createdBy: string | null
  createdAt: string
}

/** IDWew z pobranych faktur; {@code assigned} = sklep ma go już na liście. */
export type KsefSeenInternalId = {
  internalId: string
  name: string | null
  invoiceCount: number
  assigned: boolean
}

export type KsefSettings = {
  /** NIP firmy z ustawień sklepu ({@code null} = nie wpisany). */
  storeNip: string | null
  connection: KsefConnection | null
  internalIds: KsefInternalId[]
  seenInternalIds: KsefSeenInternalId[]
  skippedSuppliers: KsefSkippedSupplier[]
  companies: KsefCompany[]
  /** Inne aktywne sklepy użytkownika z NIP-em tej firmy (przy połączonym sklepie). */
  otherStores: KsefCompanyStore[]
  /** Ile innych sklepów użytkownika nie ma NIP-u - nie wiadomo, czy należą do tej firmy. */
  otherStoresWithoutNip: number
}

/** Inny sklep użytkownika z NIP-em tej samej firmy; {@code connected} = ma już jej połączenie z KSeF. */
export type KsefCompanyStore = {
  id: string
  number: string
  name: string
  connected: boolean
}

export type KsefOverview = {
  configured: boolean
  nip: string | null
  syncedAt: string | null
  syncOk: boolean | null
  syncMessage: string | null
  /** Pobieranie trwa w tle (przycisk albo harmonogram); {@code syncNewInvoices}: ile nowych już zapisano. */
  syncRunning: boolean
  syncStartedAt: string | null
  syncNewInvoices: number
  invoiceCount: number
  internalIds: KsefInternalId[]
}

/**
 * Wiersz faktury; {@code entered}: faktura ma dokument sklepu ({@code deliveries}: przyjęcie z jej numerem albo
 * przypięty dokument) albo oznaczono ją ręcznie.
 */
export type KsefInvoice = {
  id: string
  ksefNumber: string
  invoiceNumber: string
  issueDate: string
  invoicingDate: string
  sellerNip: string
  sellerName: string | null
  netAmount: number
  grossAmount: number
  vatAmount: number
  currency: string
  invoiceType: string
  correction: boolean
  hasAttachment: boolean
  internalIds: string[]
  entered: boolean
  enteredBy: string | null
  enteredAt: string | null
  /** Sklep firmy, w którym oznaczono ją ręcznie (faktura wprowadzona w jednym sklepie jest wprowadzona we wszystkich). */
  enteredStoreId: string | null
  enteredStore: string | null
  deliveries: KsefInvoiceDocument[]
  supplierSkipped: boolean
  /** Przy korekcie: numery faktur, które koryguje (z XML); `null`, dopóki XML korekty nie został odczytany. */
  correctedInvoices: string[] | null
}

/**
 * Dokument sklepu firmy z fakturą; {@code linked} = przypięty ręcznie (da się odpiąć), inaczej przyjęcie z jej
 * numerem. {@code storeId}/{@code store}: sklep dokumentu - może to być inny sklep tej samej firmy.
 */
export type KsefInvoiceDocument = {
  id: string
  type: string
  typeSingular: string
  number: string
  linked: boolean
  storeId: string
  store: string
}

export type KsefInvoicePage = {
  invoices: KsefInvoice[]
  page: number
  hasMore: boolean
  matchingCount: number
  netTotal: number
  grossTotal: number
}

/** Sklep, dla którego wystawiono ruch międzymagazynowy; {@code enteredAt}, gdy sklep wprowadził go u siebie. */
/** Dokument sklepu docelowego potwierdzający wprowadzenie ruchu międzymagazynowego. */
export type TransferReceipt = {
  documentId: string
  number: string
  documentDate: string
  purchaseValue: number
  linkedBy: string | null
  linkedAt: string
}

export type TransferTarget = {
  store: StoreSummary
  createdBy: string | null
  createdAt: string
  enteredBy: string | null
  enteredAt: string | null
  /** Pusty = dokument czeka, aż sklep przypnie swój dokument przyjęcia. */
  receipt: TransferReceipt | null
}

/** Ruch międzymagazynowy innego sklepu wystawiony dla tego sklepu; {@code id} to id wpisu, nie dokumentu. */
export type IncomingTransfer = {
  id: string
  fromStore: StoreSummary
  number: string
  documentDate: string
  lineCount: number
  purchaseValue: number
  saleValueGross: number
  createdBy: string | null
  createdAt: string
  enteredBy: string | null
  enteredAt: string | null
  /** Kto i kiedy ostatnio wpisał ilości, które naprawdę przyjechały. */
  receivedBy: string | null
  receivedAt: string | null
  /** Dokument tego sklepu potwierdzający wprowadzenie; pusty = ruch czeka. */
  receipt: TransferReceipt | null
  /** Numery poprawek sklepu wysyłającego: linie i wartości są już po nich. */
  corrections: string[]
  /** Pusta na liście, z liniami w szczegółach. */
  lines: DocumentLine[]
  /** Przyjęte ilości; linia bez wpisu przyjechała w ilości z dokumentu. */
  received: { lineId: string; quantity: number }[]
}

export type IncomingTransfers = {
  pending: IncomingTransfer[]
  /** Ostatnio potwierdzone dokumentem przyjęcia, od najnowszego. */
  entered: IncomingTransfer[]
}

/** Znajomość albo zaproszenie od strony sklepu: {@code store} to drugi sklep, {@code sent} - ten sklep zapraszał. */
export type StorePartner = {
  id: string
  store: StoreSummary
  sent: boolean
  invitedBy: string | null
  invitedAt: string
  acceptedBy: string | null
  /** Pusty = zaproszenie czeka na odpowiedź. */
  acceptedAt: string | null
}

export type StorePartners = {
  partners: StorePartner[]
  invitableStores: StoreSummary[]
}

/**
 * Liczby wiersza podsumowania sprzedaży i strat (zł i %). Marża i marża po stratach w % od sprzedaży netto, straty
 * w % od sprzedaży brutto, udział = sprzedaż brutto w sprzedaży brutto sklepu. Procent pusty, gdy nie ma od czego liczyć.
 */
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

/** Wiersz podsumowania: nagłówek grupy (bez liczb), rejon albo suma grupy; {@code depth} 0 = grupa najwyższa. */
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

/** Rozwinięty wiersz podsumowania: rodziny, podrodziny albo artykuły (udział pusty, liczy go przeglądarka). */
export type PeriodSummaryLevel = {
  level: SalesComparisonLevel
  rows: {
    /** Kod nomenklatury albo ITM8 artykułu. */
    code: string
    name: string | null
    ean: string | null
    brand: string | null
    figures: PeriodSummaryFigures
  }[]
}

export type PeriodSummary = {
  storeNumber: string
  storeName: string
  from: string
  to: string
  lastSaleDate: string | null
  documentsCoveredUntil: string | null
  salesReportGeneratedAt: string | null
  documentsReportGeneratedAt: string | null
  budgetsSet: boolean
  lines: PeriodSummaryLine[]
  total: PeriodSummaryFigures
}

/** Gratisy za handel / ekspozycję i za straty, sprzedaż brutto i udział gratisów w niej (pusty bez sprzedaży). */
export type GratisFigures = {
  tradeValue: number
  lossValue: number
  gratisValue: number
  saleValueGross: number
  gratisPercent: number | null
}

/** Kafelek raportu gratisów: miesiąc albo podsumowanie okresu (miesiące RRRR-MM), tylko rejony z gratisem. */
export type GratisReportTile = {
  from: string
  to: string
  label: string
  documentCount: number
  /** Pusty {@code code} = linie bez nomenklatury. */
  regions: { code: string; name: string | null; figures: GratisFigures }[]
  /** {@code saleValueGross} to sprzedaż całego sklepu. */
  total: GratisFigures
}

/** Artykuł aktywny w cadencier ze stanem 0. */
export type ZeroStockItem = {
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  stock: number | null
}

/** Rodziny w kolejności kodów; pusty {@code cadencierGeneratedAt} = sklep nie ma listy cadencier. */
export type ZeroStockReport = {
  storeNumber: string
  storeName: string
  stockReportGeneratedAt: string | null
  cadencierGeneratedAt: string | null
  selection: NomenclatureOption[]
  itemCount: number
  groups: { code: string; name: string | null; items: ZeroStockItem[] }[]
}

export type StockGapKind = 'MISSING' | 'ONE_OFF' | 'RUNNING_OUT' | 'WRONG_STOCK'

export type StockGapItem = {
  kind: StockGapKind
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  subfamilyCode: string | null
  cadencier: boolean
  stock: number
  typicalWeek: number
  weeksSold: number
  lastSale: string
  silentDays: number
  missedQuantity: number
  coverDays: number | null
  weeklyMargin: number
  lastDeliveryDate: string | null
  recentDeliveries: number
  supplierCode: string | null
  supplierName: string | null
}

export type StockGapReport = {
  storeNumber: string
  storeName: string
  stockReportGeneratedAt: string | null
  lastSaleDate: string | null
  minWeekly: number
  maxStock: number
  noDeliveryDays: number
  items: StockGapItem[]
}

export type NegativeStockDelivery = 'NO_DELIVERY_SINCE' | 'NEVER' | 'DELIVERED_IN' | 'ANY'

export type NegativeStockItem = {
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  subfamilyCode: string | null
  cadencier: boolean
  active: boolean
  stock: number
  stockValue: number
  soldQuantity: number
  soldGross: number
  lastSale: string | null
  lastDeliveryDate: string | null
  daysSinceDelivery: number | null
  deliveryDays: number
  periodDeliveries: number
  supplierCode: string | null
  supplierName: string | null
  /** Podobne artykuły na plusie ze stanem zbliżonym do brakującej ilości, najlepiej pasujący pierwszy. */
  counterparts: NegativeStockCounterpart[]
}

export type NegativeStockCounterpart = {
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  subfamilyCode: string | null
  stock: number
  stockValue: number
  /** Podobieństwo nazw 0-1. */
  similarity: number
  sameSubfamily: boolean
  sameBrand: boolean
  soldQuantity: number
}

export type NegativeStockReport = {
  storeNumber: string
  storeName: string
  stockReportGeneratedAt: string | null
  lastSaleDate: string | null
  salesFrom: string
  today: string
  delivery: NegativeStockDelivery
  days: number
  from: string | null
  to: string | null
  pairTolerance: number
  items: NegativeStockItem[]
}

export type SlowMovingItem = {
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  subfamilyCode: string | null
  cadencier: boolean
  stock: number
  stockValue: number | null
  soldQuantity: number
  weeklyRotation: number
  lastDeliveryDate: string | null
  supplierCode: string | null
  supplierName: string | null
}

/** Artykuł z listy pomijanych w raporcie niesprzedających się. */
export type SlowMovingExcludedItem = {
  id: string
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  excludedBy: string | null
  excludedAt: string
}

export type SlowMovingDelivery = {
  documentId: string
  documentNumber: string
  documentDate: string
  supplierCode: string | null
  supplierName: string | null
  purchaseValue: number
  items: { itm8: string; quantity: number; purchaseValue: number }[]
}

/** Sumy i {@code items} po wyborze kategorii; liczby przy zakładkach i lista dostawców przed nim. */
export type SlowMovingReport = {
  storeNumber: string
  storeName: string
  stockReportGeneratedAt: string | null
  from: string
  to: string
  maxRotation: number
  itemCount: number
  unsoldCount: number
  stockValue: number
  allCount: number
  otherCount: number
  categoryCounts: { id: string; count: number }[]
  suppliers: { code: string; name: string | null; count: number }[]
  selection: NomenclatureOption[]
  items: SlowMovingItem[]
  deliveries: SlowMovingDelivery[]
}

export type GratisReport = {
  storeNumber: string
  storeName: string
  from: string
  to: string
  lastSaleDate: string | null
  documentsCoveredUntil: string | null
  months: GratisReportTile[]
  /** Przy kilku miesiącach: ten sam kafelek za cały okres. */
  summary: GratisReportTile | null
}

/** Budżet rejonu w ustawieniach sklepu; puste wartości = bez budżetu. */
export type RegionBudget = {
  regionCode: string
  name: string | null
  /** Grupa z podsumowania, np. "Świeże tradycyjne". */
  group: string
  marginPercent: number | null
  maxLossPercent: number | null
  updatedBy: string | null
  updatedAt: string | null
}

/**
 * Marża wymagana na pozycji nomenklatury (Ustawienia sklepu): {@code marginPercent} ustawiona na niej,
 * {@code effectivePercent} obowiązująca (własna albo z rodziny / rejonu).
 */
export type NomenclatureMargin = {
  code: string
  name: string
  level: 'REGION' | 'FAMILY' | 'SUBFAMILY'
  marginPercent: number | null
  effectivePercent: number | null
  updatedBy: string | null
  updatedAt: string | null
}

/** Przedział cen [od, do) z końcówką zaokrąglenia ceny sugerowanej; {@code priceTo} null = bez górnej granicy. */
export type PriceRoundingRule = {
  priceFrom: number
  priceTo: number | null
  ending: number
  createdBy: string | null
  createdAt: string | null
}

/** Stała cena sprzedaży brutto wszystkich artykułów dostawcy (Ustawienia sklepu, cena sugerowana we wprowadzaniu danych). */
export type SupplierSalePrice = {
  supplierId: string
  supplierName: string
  supplierCode: string | null
  salePrice: number
  createdBy: string | null
  createdAt: string | null
}

export type NomenclatureMarginImport = { changedCount: number; withMarginCount: number }

/** Status wyjaśniania przyjęcia z ceną wyższą niż baza; brak wpisu = do wyjaśnienia. */
export type PriceCheckStatus = 'REPORTED' | 'RESOLVED'

export type PriceCheckReview = {
  id: string
  status: PriceCheckStatus
  note: string | null
  createdBy: string | null
  createdAt: string
}

/**
 * Artykuł z cadencier kupiony u dostawcy innego niż baza. Ceny za sztukę, po korektach. {@code belowMinimum}: marża
 * na bonie poniżej minimum z ustawień; wtedy {@code excessValue} mówi, o ile za drogo.
 */
export type PriceCheckLine = {
  itm8: string
  /** ITM8 z cadencier, gdy dostawca ma artykuł pod własnym ITM8 (połączony po EAN-ie). */
  cadencierItm8: string | null
  ean: string | null
  name: string | null
  brand: string | null
  /** Rejon nomenklatury linii (dwie cyfry), do własnych kategorii raportu. */
  regionCode: string | null
  supplierCode: string | null
  quantity: number
  purchasePrice: number
  purchaseValue: number
  salePriceGross: number | null
  salePriceNet: number | null
  marginPercent: number | null
  belowMinimum: boolean
  targetPurchasePrice: number | null
  excessValue: number
  cadencierPrice: number | null
  basePrice: number | null
  baseDate: string | null
  baseDocumentId: string | null
  baseDocumentNumber: string | null
  /** Dzień incydentu zamówienia do bazy (do 10 dni przed przyjęciem), który usprawiedliwia zakup u innego dostawcy. */
  incidentDate: string | null
  incidentReason: string | null
  incidentComment: string | null
  corrected: boolean
}

/** Przyjęcie z artykułami z cadencier od innego dostawcy; puste {@code lines} = po korektach już ich nie ma. */
export type PriceCheckDelivery = {
  id: string
  number: string
  documentDate: string
  supplierCode: string | null
  supplierName: string | null
  supplierDocumentNumber: string | null
  correctionCount: number
  /** Każdy artykuł z cadencier w przyjęciu był wcześniej w incydencie (osobna kategoria raportu). */
  afterIncident: boolean
  purchaseValue: number
  linesPurchaseValue: number
  belowMinimumCount: number
  excessValue: number
  baseDifferenceValue: number
  review: PriceCheckReview | null
  history: PriceCheckReview[]
  lines: PriceCheckLine[]
}

export type PriceCheckReport = {
  from: string
  to: string
  periodDays: number
  minMarginPercent: number | null
  baseSupplierCodes: string[]
  cadencierCount: number
  cadencierGeneratedAt: string | null
  cadencierImportedAt: string | null
  documentsCoveredUntil: string | null
  documentsReportGeneratedAt: string | null
  incidentDays: number
  lastIncidentDate: string | null
  deliveries: PriceCheckDelivery[]
}

/** Poprzednia dostawa artykułu z ceną z bonu, sprzed korekt. */
export type OverpaymentReference = {
  documentId: string
  documentNumber: string
  documentDate: string
  supplierCode: string | null
  supplierName: string | null
  quantity: number
  purchasePrice: number
}

export type OverpaymentLine = {
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  regionCode: string | null
  supplierCode: string | null
  quantity: number
  purchasePrice: number
  purchaseValue: number
  /** Najniższa z cen poprzednich dostaw. */
  referencePrice: number
  overpaidPercent: number
  excessValue: number
  corrected: boolean
  references: OverpaymentReference[]
}

export type OverpaymentDelivery = {
  id: string
  number: string
  documentDate: string
  supplierCode: string | null
  supplierName: string | null
  supplierDocumentNumber: string | null
  correctionCount: number
  linesPurchaseValue: number
  excessValue: number
  review: PriceCheckReview | null
  history: PriceCheckReview[]
  lines: OverpaymentLine[]
}

export type OverpaymentReport = {
  from: string
  to: string
  periodDays: number
  thresholdPercent: number
  referenceCount: number
  largeQuantityFactor: number
  documentsCoveredUntil: string | null
  documentsReportGeneratedAt: string | null
  /** Dostawcy pomijani w raporcie. */
  excludedSuppliers: ReportSupplier[]
  /** Lista dostawców sklepu do wyboru. */
  suppliers: ReportSupplier[]
  deliveries: OverpaymentDelivery[]
}

/** Skąd wiadomo o promocji w dniu przyjęcia: lista promocji, pliki kasy, gazetka, spadek ceny w sprzedaży. */
export type LowMarginPromotion = 'LIST' | 'TILL' | 'LEAFLET' | 'SALES'

export type ReportSupplier = { code: string; name: string }

export type LowMarginLine = {
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  regionCode: string | null
  supplierCode: string | null
  quantity: number
  purchasePrice: number
  purchaseValue: number
  /** Aktualna cena zwykła brutto na kasie, od niej liczy się marża. */
  salePrice: number
  documentSalePrice: number
  promotion: LowMarginPromotion | null
  marginPercent: number
  requiredMargin: number
  /** Cena zakupu dająca marżę wymaganą. */
  targetPurchasePrice: number
  /** Brakująca marża (za niska) albo nadwyżka marży (za wysoka), zawsze na plus. */
  excessValue: number
  corrected: boolean
  deviation: LowMarginDeviation
  /** Artykuł jest dziś w promocji: cena promocyjna i koniec (RRRR-MM-DD); marża i tak z ceny zwykłej. */
  currentPromoPrice: number | null
  currentPromoTo: string | null
  /** Marża % od sprzedaży netto na cenie promocyjnej (z tej samej ceny zakupu); pusta bez promocji. */
  currentPromoMargin: number | null
}

/** Marża za niska albo za wysoka wobec wymaganej. */
export type LowMarginDeviation = 'LOW' | 'HIGH'

export type LowMarginDelivery = {
  id: string
  number: string
  documentDate: string
  supplierCode: string | null
  supplierName: string | null
  supplierDocumentNumber: string | null
  correctionCount: number
  linesPurchaseValue: number
  excessValue: number
  review: PriceCheckReview | null
  history: PriceCheckReview[]
  lines: LowMarginLine[]
  /** Weryfikacja przyjęcia, ta sama co w Dokumentach ({@code null} = bez statusu). */
  verificationStatus: DocumentVerificationStatus | null
  verificationAt: string | null
  verificationBy: string | null
  /** Faktury KSeF przyjęcia i jego korekt od dostawcy. */
  ksefInvoices: LinkedKsefInvoice[]
}

/** Ocena artykułu przyjęcia na bonie: jak w raporcie marż (odchylenie puste = w normie). */
export type DeliveryLineHint = {
  line: Omit<LowMarginLine, 'deviation' | 'requiredMargin' | 'targetPurchasePrice' | 'excessValue'> & {
    deviation: LowMarginDeviation | null
    requiredMargin: number | null
    targetPurchasePrice: number | null
    excessValue: number | null
  }
  /** Aktywny w cadencier: marża ustalona z góry. */
  cadencier: boolean
  /** Dostawca pomijany w raporcie marż. */
  excludedSupplier: boolean
}

export type DeliveryLineHints = {
  lowGap: number
  highGap: number
  marginsConfigured: boolean
  lines: DeliveryLineHint[]
}

export type LowMarginReport = {
  from: string
  to: string
  periodDays: number
  /** Marża niższa o tyle punktów procentowych od wymaganej jest za niska. */
  lowGap: number
  /** Marża wyższa o tyle punktów procentowych od wymaganej jest za wysoka. */
  highGap: number
  marginsConfigured: boolean
  documentsCoveredUntil: string | null
  documentsReportGeneratedAt: string | null
  /** Dostawcy, których nie dotyczy marża wymagana. */
  excludedSuppliers: ReportSupplier[]
  /** Lista dostawców sklepu do wyboru. */
  suppliers: ReportSupplier[]
  deliveries: LowMarginDelivery[]
}

/** Artykuł w raporcie marż kartoteki albo aktualnych promocji (marże % od sprzedaży netto). */
export type ArticleMarginItem = {
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  nomenclatureCode: string | null
  regionCode: string | null
  /** Dostawca ostatniego przyjęcia PZ. */
  supplierCode: string | null
  supplierName: string | null
  vatRate: number
  /** Cena zakupu netto sztuki: z ostatniego przyjęcia PZ (z ostatniego roku) albo z kartoteki. */
  purchasePrice: number
  purchaseSource: 'DELIVERY' | 'CATALOG'
  lastPurchaseDate: string | null
  /** Aktualna cena zwykła brutto na kasie. */
  salePrice: number
  marginPercent: number
  requiredMargin: number | null
  targetPurchasePrice: number | null
  /** Cena sprzedaży brutto dająca marżę wymaganą przy tej cenie zakupu. */
  targetSalePrice: number | null
  promoPrice: number | null
  promoFrom: string | null
  promoTo: string | null
  promoSource: 'LIST' | 'TILL' | null
  promoMargin: number | null
  /** Sprzedana ilość w ostatnich 3 miesiącach; pusta bez sprzedaży. */
  soldQuantity: number | null
  /** Aktywny w cadencier z CENA_CAD: marża ustalona przez centralę. */
  cadencier: boolean
  cadencierPrice: number | null
  /** Obowiązująca weryfikacja (ceny bez zmian od sprawdzenia); pusta = do kontroli. */
  verification: ArticleMarginVerification | null
}

/** Artykuł sprawdzony pod kątem za niskiej marży: kto, kiedy, notatka i ceny z chwili sprawdzenia. */
export type ArticleMarginVerification = {
  id: string
  verifiedAt: string
  verifiedBy: string | null
  note: string | null
  salePrice: number
  purchasePrice: number
  marginPercent: number
}

export type ArticleMarginReport = {
  today: string
  /** Raport artykułów: marża poniżej tylu procent; pusty w raporcie promocji. */
  maxMargin: number | null
  marginsConfigured: boolean
  /** Promocje z gazetki pominięte w raporcie aktualnych promocji. */
  leafletSkipped: number
  excludedSuppliers: ReportSupplier[]
  items: ArticleMarginItem[]
}

/** Własna kategoria raportu zakupu bazowego poza bazą: nazwa i rejony nomenklatury. */
export type PriceCheckCategory = {
  id: string
  name: string
  regionCodes: string[]
}

/** Wgranie pliku incydentów zamówień do bazy; dzień incydentu z nazwy pliku. */
export type IncidentImport = {
  id: string
  fileName: string
  incidentDate: string
  importedBy: string | null
  importedAt: string
  totalRows: number
  addedCount: number
}

export type IncidentOverview = {
  lastIncidentDate: string | null
  imports: IncidentImport[]
}

export type PriceCheckSettings = {
  minMarginPercent: number | null
  updatedBy: string | null
  updatedAt: string | null
}

/** Wgranie listy „OBECNE W CADENCIER”. */
export type CadencierImport = {
  id: string
  fileName: string
  reportGeneratedAt: string | null
  importedBy: string | null
  importedAt: string
  totalRows: number
  addedCount: number
  removedCount: number
}

export type CadencierOverview = {
  itemCount: number
  imports: CadencierImport[]
}

/** Wprowadzanie danych: rodzaj sprzedaży artykułu (JED z kartoteki). */
export type SaleUnit = 'kg' | 'L' | 'szt.'

/** Artykuł z kartoteki sklepu dla EAN-u ({@code ean} bez zer wiodących); {@code salePrice} brutto sztuki. */
/** Artykuł z kartoteki w oknie „Lista towarów” we wprowadzaniu danych. */
export type DataEntryCatalogListItem = {
  /** {@code null} dla artykułu ze szkicu CADBEZP ({@code draft}), którego nie ma jeszcze w kartotece. */
  itm8: string | null
  ean: string
  name: string | null
  brand: string | null
  supplier: string | null
  /** Podrodzina nomenklatury (6 cyfr) i jej nazwa. */
  subfamilyCode: string | null
  subfamilyName: string | null
  /** Cena zakupu netto sztuki (przy wagowych 1 kg) z kartoteki. */
  purchasePrice: number | null
  salePrice: number | null
  /** Stan i rotacja z ostatniego raportu kartoteki. */
  stock: number | null
  rotation: number | null
  /** Ostatnie przyjęcie PZ (dostawca z linii dokumentu, nie z kartoteki); tylko w liście towarów. */
  lastDelivery: { supplierCode: string | null; supplierName: string | null; date: string } | null
  draft: boolean
}

/** Artykuł z kartoteki, nie szkic: tylko taki da się przypiąć, bo przypięcie idzie po ITM8. */
export type DataEntryCatalogArticle = DataEntryCatalogListItem & { itm8: string }

export type DataEntryCatalogListPage = {
  items: DataEntryCatalogListItem[]
  hasMore: boolean
}

export type DataEntryCatalogProduct = {
  /** EAN, którego szukano (bez zer wiodących). */
  ean: string
  /** {@code null} dla artykułu ze szkicu CADBEZP ({@code draft}), którego nie ma jeszcze w kartotece. */
  itm8: string | null
  /** Bieżący EAN artykułu w kartotece (inny niż {@code ean} np. dla kodu z etykiety wagi). */
  catalogEan: string | null
  name: string | null
  brand: string | null
  /** Cena zakupu netto sztuki (przy wagowych 1 kg) z kartoteki. */
  purchasePrice: number | null
  /** Stawka VAT w % do pustego wiersza; może być tylko najczęstszą stawką podrodziny. */
  vatPercent: number | null
  /** Stawka tego artykułu (kod z kartoteki albo jego ostatni dokument); tylko z nią porównujemy stawkę z faktury. */
  catalogVat: number | null
  salePrice: number | null
  /** Artykuł ma CENA_CAD z ostatniego raportu kartoteki. */
  cadencier: boolean
  /** CENA_CAD (brutto sztuki narzucona przez centralę); {@code null}, gdy jej nie ma. */
  cadencierPrice: number | null
  /** Trwająca promocja z pliku TRACEART: cena brutto na kasie ({@code salePrice} jest wtedy regularna) i ostatni dzień. */
  promoPrice: number | null
  promoTo: string | null
  unit: SaleUnit
  netContent: number
  subfamilyCode: string | null
  /** Stan magazynowy (może być ujemny) i rotacja z ostatniego raportu kartoteki. */
  stock: number | null
  rotation: number | null
  /** Szukany kod to nie EAN, tylko ITM8 artykułu (albo ITM8 z multikodów). */
  byItm8: boolean
  /**
   * Artykuł utworzony plikiem CADBEZP, którego nie ma jeszcze w kartotece (czeka na kolejny raport): dane z pliku,
   * jest już w programie sklepu, więc nie jest nowym artykułem.
   */
  draft: boolean
}

/** Artykuł w ustawianiu promocji. */
/** Ceny do okna przeceny na karcie artykułu (jak w ustawianiu promocji). */
export type MarkdownPrices = {
  /** Cena sprzedaży brutto bez trwającej promocji. */
  regularPrice: number | null
  /** Cena netto sztuki z ostatniego przyjęcia PZ; pusta bez przyjęcia. */
  lastPurchasePrice: number | null
  lastPurchaseDate: string | null
}

export type PromotionSetupArticle = {
  /** Kod, którego szukano (bez zer wiodących). */
  code: string
  /** {@code null} dla artykułu ze szkicu CADBEZP ({@code draft}): bez statystyk. */
  itm8: string | null
  /** Bieżący EAN z kartoteki, idzie do pliku .PRO. */
  ean: string | null
  name: string | null
  brand: string | null
  vatPercent: number | null
  /** Cena sprzedaży brutto bez trwającej promocji. */
  regularPrice: number | null
  /** Cena netto sztuki z ostatniego przyjęcia PZ ({@code lastPurchaseDate}), bez przyjęcia z kartoteki (data pusta). */
  lastPurchasePrice: number | null
  lastPurchaseDate: string | null
  draft: boolean
}

/** Pozycja faktury z KSeF; bez {@code product} to nowy artykuł, a miara jest z nazwy z faktury. */
export type DataEntryLine = {
  number: string | null
  ean: string | null
  /** Skąd EAN: GTIN, INDEX, EXTRA:<klucz>, NAME (przy automatycznym wyborze różne dla pozycji). */
  eanSource: string | null
  /** Bez EAN-u: inny kod z faktury (Indeks, dodatkowy opis) albo nazwa, i opis pola, z którego pochodzi. */
  fallbackCode: string | null
  fallbackSource: string | null
  quantity: number | null
  unitNet: number | null
  invoiceName: string | null
  invoiceUnit: string | null
  vatPercent: number | null
  product: DataEntryCatalogProduct | null
  unit: SaleUnit
  netContent: number
  /** Pozycja wczytana z przypięcia kodu dostawcy do artykułu ({@code eanSource} = LINK). */
  linkId: string | null
  linkedCode: string | null
  /** Indeks dostawcy i kod CN z pozycji faktury (do pliku CADBEZP). */
  supplierCode: string | null
  cn: string | null
  /** Przy korekcie stan z wiersza „przed korektą” tej pozycji; `null`: pozycja bez takiego wiersza. */
  beforeQuantity: number | null
  beforeUnitNet: number | null
}

/** Co koryguje faktura korygująca. `hasBeforeRows`: pozycje podają stan po korekcie, bez tego różnice do bonu. */
export type DataEntryCorrection = {
  reason: string | null
  hasBeforeRows: boolean
  corrected: { number: string | null; issueDate: string | null; ksefNumber: string | null }[]
}

/** Przyjęcie PZ do faktury korygującej; `matched`: przyjęcie faktury, którą korekta koryguje. */
export type CorrectionDelivery = {
  id: string
  number: string
  documentDate: string
  supplierName: string | null
  supplierDocumentNumber: string | null
  purchaseValue: number
  matched: boolean
}

/** Linia przyjęcia po wcześniejszych korektach, z artykułem z kartoteki (`null`: nie ma go w kartotece). */
export type CorrectionDeliveryLine = {
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  quantity: number | null
  purchasePrice: number | null
  articleItm8: string | null
  articleEan: string | null
}

export type DataEntryInvoice = {
  invoiceId: string
  invoiceNumber: string
  sellerName: string | null
  sellerNip: string
  issueDate: string
  /** Użyte źródło EAN-u (AUTO albo jedno z {@code sources}). */
  source: string
  sources: DataEntryCodeSource[]
  lines: DataEntryLine[]
  /** Sumy netto i VAT z dokumentu (podsumowanie stawek albo pozycje); {@code null}, gdy faktura ich nie podaje. */
  netTotal: number | null
  vatTotal: number | null
  /** Tylko faktura korygująca z KSeF. */
  correction: DataEntryCorrection | null
}

/**
 * Dokument do tabeli: faktura z KSeF albo plik XML faktury (z dysku albo z historii). Plik bez faktury z KSeF nie ma
 * `invoiceId`, a numer, NIP i data są z pliku (mogą być puste).
 */
export type DataEntryDocument = Omit<DataEntryInvoice, 'invoiceId' | 'invoiceNumber' | 'sellerNip' | 'issueDate'> & {
  invoiceId: string | null
  invoiceNumber: string | null
  sellerNip: string | null
  issueDate: string | null
}

/** Plik XML faktury z historii „Zapisz XML” (100 ostatnich w sklepie). */
export type DataEntryXmlExport = {
  id: string
  fileName: string
  documentNumber: string | null
  documentDate: string
  supplierName: string | null
  supplierNip: string | null
  lineCount: number
  netValue: number
  createdAt: string
  createdBy: string | null
}

/** Faktura w kolejce do zamknięcia w programie sklepu; `generatedAt`: poszła już do skryptu. */
export type InvoiceClosing = {
  id: string
  supplierCode: string
  documentNumber: string
  supplierName: string | null
  documentDate: string
  netValue: number
  createdAt: string
  generatedAt: string | null
}

/**
 * Plik XML faktury odczytany do wczytania; `otherStore`: odbiorca w pliku to inny sklep niż wybrany. Zapis tymczasowy
 * ma też `draft`: całą tabelę z przeglądarki (JSON) z chwili zapisu.
 */
export type DataEntryXmlDocument = {
  fileName: string | null
  recipientCode: string | null
  otherStore: boolean
  document: DataEntryDocument
  draft: string | null
}

/** Co sklep zrobił z fakturą w oknie wczytywania: wczytana do tabeli, pobrana / wydrukowana, nie moja. */
export type InvoiceMarkKind = 'LOADED' | 'DOWNLOADED' | 'NOT_MINE'

/**
 * Faktura w oknie wczytywania: status w sklepie ({@code null} = do wczytania, LOADED wczytana, NOT_MINE nie moja),
 * kiedy sklep ją pobrał albo wydrukował (tylko informacja, pobrana dalej czeka na wczytanie) i znaczniki innych
 * sklepów firmy (wczytana albo pobrana na innym stanowisku).
 */
export type DataEntryInvoiceItem = {
  invoice: KsefInvoice
  status: 'LOADED' | 'NOT_MINE' | null
  statusAt: string | null
  downloadedAt: string | null
  /** Reguła dostawcy w tym oknie: pomijany albo odkładany do wczytanych. */
  supplierRule: SupplierRuleMode | null
  otherStores: {
    storeId: string
    store: string
    kind: InvoiceMarkKind
    at: string
  }[]
}

export type DataEntryInvoicePage = {
  invoices: DataEntryInvoiceItem[]
  page: number
  hasMore: boolean
  matchingCount: number
}

/** Reguła dostawcy w oknie wczytywania: HIDDEN pomijany (faktur nie ma w oknie), DONE odkładany do „Wczytane”. */
export type SupplierRuleMode = 'HIDDEN' | 'DONE'

/** Dostawca z regułą okna wczytywania faktur (niezależnie od pomijanych w Fakturach KSeF). */
export type DataEntrySkippedSupplier = {
  id: string
  sellerNip: string
  sellerName: string | null
  mode: SupplierRuleMode
  createdAt: string
}

/** Wynik działania zbiorczego na fakturach: znaczniki do „Cofnij”. */
export type InvoiceBulkResult = {
  count: number
  createdMarkIds: string[]
  deletedMarkIds: string[]
}

/** Źródło EAN-u na fakturze: w ilu pozycjach jest kod ({@code filled}) i ile z nich jest w kartotece. */
export type DataEntryCodeSource = { key: string; label: string; filled: number; matched: number }

/** Kod 2, 4 albo 6 cyfr: rejon, rodzina, podrodzina. */
/** Pozycja nomenklatury do wprowadzania danych; {@code requiredMargin} = marża wymagana, która na niej obowiązuje. */
export type NomenclatureItem = { code: string; name: string; requiredMargin: number | null }

export type SuggestionStatus = 'CERTAIN' | 'AGREED' | 'TO_CHECK'
export type SuggestionSource = 'MODEL' | 'NAMES' | 'PRODUCER'

export type SuggestionProposal = { code: string; sources: SuggestionSource[] }

/** Propozycja AI; przy TO_CHECK {@code subfamilyCode} jest puste, wybiera się z {@code proposals}. */
export type Suggestion = {
  key: string
  id: string
  name: string | null
  brand: string | null
  unit: SaleUnit
  netContent: number
  unitMismatch: boolean
  subfamilyCode: string | null
  status: SuggestionStatus
  confidence: number | null
  proposals: SuggestionProposal[]
  /** Artykuł z kaucją: kaucji nie ma w nazwie, trzeba ją ustawić przy zakładaniu w Mercalys. */
  deposit: boolean
}

export type SuggestionResponse = { modelUsed: boolean; message: string | null; items: Suggestion[] }

/** Protokół z wprowadzania danych jako plik PROTO1-PROTO6 (nazwa stałej z backendu). */
export type ProtocolKind = 'LIQUIDATION' | 'STORE_NEEDS' | 'DONATION' | 'ADVERTISING' | 'REPRESENTATION' | 'TRANSFER'

/** Po czym raport protokołów grupuje artykuły (ProtocolReportGrouping.java). */
export type ProtocolReportGrouping = 'ARTICLE' | 'BRAND' | 'SUPPLIER' | 'NOMENCLATURE'

/** Protokoły do raportu (bez wyłączonych w Ustawieniach sklepu); {@code documentType} pusty = system sklepu jeszcze go nie miał. */
export type ProtocolReportsOverview = {
  protocols: { kind: ProtocolKind; number: number; label: string; documentType: string | null }[]
  documentsCoveredUntil: string | null
  documentsReportGeneratedAt: string | null
}

/** Wartość zakupu netto bez minusa wydania i wartość w cenach sprzedaży brutto. */
export type ProtocolFigures = { quantity: number; purchaseValue: number; saleValueGross: number }

export type ProtocolReportArticle = {
  itm8: string
  name: string | null
  ean: string | null
  brand: string | null
  current: ProtocolFigures
  previous: ProtocolFigures | null
}

export type ProtocolReportGroup = {
  key: string
  code: string | null
  name: string | null
  current: ProtocolFigures
  previous: ProtocolFigures | null
  articles: ProtocolReportArticle[]
}

/** Bez porównania {@code previousFrom}, {@code previousTo}, {@code previousTotal} i {@code previous} są puste. */
export type ProtocolReport = {
  protocol: ProtocolKind
  grouping: ProtocolReportGrouping
  level: 'REGION' | 'FAMILY' | 'SUBFAMILY' | null
  from: string
  to: string
  previousFrom: string | null
  previousTo: string | null
  documentsCoveredUntil: string | null
  total: ProtocolFigures
  previousTotal: ProtocolFigures | null
  groups: ProtocolReportGroup[]
}

/** Folder plików PROTO sklepu (ustawiony albo domyślny) i protokoły ukryte w oknie generowania. */
export type ProtoSettings = {
  folderPath: string
  defaultFolder: boolean
  hiddenProtocols: ProtocolKind[]
  /** Folder pliku nowych i zmienionych artykułów (CADBEZP); {@code null} = bez podpowiedzi. */
  productFileFolderPath: string | null
  updatedBy: string | null
  updatedAt: string | null
}

/** Stan próby kopii zapasowej (BackupStatus z backendu). */
export type BackupStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'

/** Próba kopii w historii. `startedBy` puste = automatyczna. */
export type BackupRun = {
  id: string
  manual: boolean
  status: BackupStatus
  startedAt: string
  startedBy: string | null
  finishedAt: string | null
  fileName: string | null
  sizeBytes: number | null
  googleEmail: string | null
  error: string | null
  trashedAt: string | null
}

/** Ustawienia kopii zapasowej na Dysk Google z historią (tylko właściciel). */
export type BackupSettings = {
  googleConfigured: boolean
  connected: boolean
  googleEmail: string | null
  googleConnectedAt: string | null
  googleConnectedBy: string | null
  enabled: boolean
  runHour: number
  keepCount: number
  passwordSet: boolean
  passwordSetAt: string | null
  passwordSetBy: string | null
  running: boolean
  nextRunAt: string | null
  runs: BackupRun[]
}

/** `password` puste = hasło bez zmian. */
export type BackupSettingsRequest = {
  enabled: boolean
  runHour: number
  keepCount: number
  password: string | null
}

/** Mięso z kartoteki sklepu do wyboru przy pozycji WZ (rejony 24 i 46). */
export type MeatArticle = {
  id: string
  itm8: string
  ean: string
  name: string | null
  brand: string | null
  subfamilyCode: string | null
  salePrice: number | null
}

export type MeatSupplier = { id: string; code: string | null; name: string; nip: string | null }

/** Skąd przypisanie pozycji: zapamiętane u dostawcy, propozycja AI albo brak. */
export type MeatLineMatch = 'LINK' | 'MODEL' | 'NONE'

export type MeatScanLine = {
  documentName: string
  documentCode: string | null
  unitPrice: number
  handwrittenCorrection: boolean
  note: string | null
  article: MeatArticle | null
  match: MeatLineMatch
  /** Zapamiętane jako pozycja bez artykułu (sam zakup). */
  noArticle: boolean
}

/** Odczyt skanu WZ do sprawdzenia przed zapisem. */
export type MeatScanResult = {
  scanId: string
  contentType: string
  supplier: MeatSupplier | null
  /** MARK: zapamiętany numer weterynaryjny / BDO / NIP, NIP: NIP z dokumentu, MODEL: wybór AI. */
  supplierMatch: 'MARK' | 'NIP' | 'MODEL' | null
  readSupplierName: string | null
  readNip: string | null
  readBdo: string | null
  readVetNumber: string | null
  documentNumber: string | null
  deliveryDate: string
  duplicateId: string | null
  lines: MeatScanLine[]
}

export type MeatDeliveryLine = {
  id: string
  documentName: string
  documentCode: string | null
  unitPrice: number
  article: MeatArticle | null
  handwrittenCorrection: boolean
  note: string | null
}

export type MeatDelivery = {
  id: string
  deliveryDate: string
  supplier: MeatSupplier
  documentNumber: string
  scanId: string | null
  scanContentType: string | null
  lines: MeatDeliveryLine[]
  createdBy: string | null
  createdAt: string
  updatedBy: string | null
  updatedAt: string
  deletedAt: string | null
}

export type MeatDeliverySummary = {
  id: string
  deliveryDate: string
  supplier: MeatSupplier
  documentNumber: string
  lineCount: number
  hasScan: boolean
  createdBy: string | null
  createdAt: string
}

export type MeatReportMode = 'SPLIT' | 'AVERAGE'

export type MeatReportCell = {
  storeId: string
  /** Średnia cena zakupu netto za kg; null = sklep nie kupił. */
  purchasePrice: number | null
  purchaseCount: number
  /** Cena sprzedaży brutto za kg z dnia dostawy, a przy saleFallback ostatnia znana (z dnia saleDate). */
  salePrice: number | null
  saleDate: string | null
  saleFallback: boolean
}

export type MeatReportRow = {
  key: string
  /** null = pozycja bez artykułu (sam zakup). */
  itm8: string | null
  name: string | null
  brand: string | null
  date: string | null
  vatPercent: number
  cells: MeatReportCell[]
}

export type MeatReport = {
  from: string
  to: string
  mode: MeatReportMode
  stores: { id: string; number: string; name: string }[]
  rows: MeatReportRow[]
}

/** Wczytany plik TRACEART (zmiany cen na kasie). {@code fileTime}: czas z nazwy pliku, lokalny. */
export type PriceFileImport = {
  id: string
  fileName: string
  fileTime: string
  importedBy: string | null
  importedAt: string
  totalLines: number
  articleCount: number
  /** Artykuły ze zmienioną ceną albo promocją. */
  updatedCount: number
  /** Artykuły z nowszą ceną (z raportu albo późniejszego pliku), których plik nie ruszył. */
  olderCount: number
  /** ITM8 spoza kartoteki sklepu. */
  unknownCount: number
}

/** Plik CTRLPRIX z wprowadzania danych (zmienione ceny sprzedaży) w historii. */
export type PriceControlFileItem = {
  id: string
  fileName: string
  supplierCode: string | null
  supplierName: string | null
  invoiceNumber: string | null
  lineCount: number
  createdAt: string
  createdBy: string | null
}

export type PriceFileOverview = {
  /** Ostatnie pliki, od najnowszego. */
  latest: PriceFileImport[]
  activePromotions: number
}

export type PriceFileUploadResult = {
  imported: PriceFileImport[]
  skipped: string[]
}

/** Schemat parametrów procesu na karcie produkcji; null = na karcie "-". */
export type ProductionSchema = {
  id: string
  name: string
  bakingTemperature: number | null
  internalTemperature: number | null
  coolingTime: number | null
  coolingTemperature: number | null
  shelfLifeDays: number | null
  articleCount: number
}

export type ProductionArticle = {
  id: string
  code: string | null
  name: string
  nomenclature: string | null
  schemaId: string
}

export type ProductionBase = {
  schemas: ProductionSchema[]
  articles: ProductionArticle[]
  stamp: string
  customStamp: boolean
}

export type ProductionImportResult = { added: number; updated: number; unchanged: number; schemasCreated: number }

export type ProductionCardSummary = {
  id: string
  storeId: string
  storeNumber: string
  storeName: string
  productionDate: string
  lineCount: number
  totalQuantity: number
  createdBy: string | null
  createdAt: string
}

export type ProductionCardLine = {
  id: string
  articleId: string
  code: string | null
  name: string
  quantity: number
  startHour: number
  endHour: number
  bakingTemperature: number | null
  internalTemperature: number | null
  coolingTime: number | null
  coolingTemperature: number | null
  shelfLifeDays: number | null
  expiryDate: string | null
}

export type ProductionCard = {
  id: string
  storeId: string
  storeNumber: string
  storeName: string
  productionDate: string
  lines: ProductionCardLine[]
  createdBy: string | null
  createdAt: string
  updatedBy: string | null
  updatedAt: string
  deletedAt: string | null
  /** Karta bieżącego sklepu; karty sklepów zaprzyjaźnionych są tylko do podglądu i wydruku. */
  editable: boolean
}

/** Przecena na liście cenówek; {@code labelsGeneratedAt}: artykuł był już w pliku ETIQUETTE. */
/** Rodzaj wpisu na liście cenówek: przecena z okresem albo zmiana ceny z pliku CTRLPRIX (bez końca, biała cenówka). */
export type PriceLabelKind = 'MARKDOWN' | 'PRICE_CHANGE'

export type PriceMarkdown = {
  id: string
  kind: PriceLabelKind
  itm8: string
  ean: string
  name: string | null
  regularPrice: number | null
  newPrice: number
  validFrom: string
  /** Pusty przy zmianie ceny. */
  validTo: string | null
  createdAt: string
  labelsGeneratedAt: string | null
}

/** Artykuł na liście Magazynu i produktów; {@code promoPrice}: cena trwającej promocji z kasy. */
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

/** Promocja z listy promocji; {@code cancelled}: zabrakło jej w nowszym raporcie. */
export type ProductPromotion = {
  startDate: string
  endDate: string
  regularSalePrice: number | null
  promoPrice: number | null
  catalogPurchasePrice: number | null
  marginPercent: number | null
  cancelled: boolean
}

/**
 * Zmiana ceny sprzedaży: TILL plik z kasy, REPORT import kartoteki, DATA_ENTRY plik zmiany cen z wprowadzania danych
 * (bez ceny poprzedniej, z dostawcą i fakturą w {@code description}).
 */
export type ProductPriceChange = {
  /** Tylko zmiany z kasy; import kartoteki i pliki zmiany cen dotyczą całego artykułu. */
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

/** Ocena promocji w raporcie skuteczności promocji (PromotionVerdict). */
export type PromotionVerdict = 'PROFITABLE' | 'NO_REACTION' | 'TOO_DEEP' | 'BELOW_COST' | 'NO_BASELINE'

/**
 * Promocja w raporcie skuteczności: z listy promocji ({@code listed}) albo rozpoznana ze spadku ceny w sprzedaży.
 * Baza to sprzedaż dzienna z dni bez promocji w 28 dniach przed nią; {@code breakEvenUplift} to próg opłacalności.
 */
export type PromotionReportItem = {
  itm8: string
  ean: string | null
  name: string | null
  brand: string | null
  regionCode: string | null
  regionName: string | null
  listed: boolean
  startDate: string
  endDate: string
  days: number
  regularPrice: number | null
  promoPrice: number | null
  discountPercent: number | null
  listMarginPercent: number | null
  quantity: number
  saleValueGross: number
  marginValue: number
  marginPercent: number | null
  baseDays: number
  baseQuantityPerDay: number | null
  baseMarginPerDay: number | null
  baseMarginPercent: number | null
  quantityPerDay: number
  uplift: number | null
  breakEvenUplift: number | null
  extraMargin: number | null
  afterDays: number
  afterRatio: number | null
  lossValue: number
  usualLossValue: number | null
  verdict: PromotionVerdict
}

export type PromotionReport = {
  from: string
  to: string
  lastSaleDate: string | null
  listSince: string | null
  /** Pierwszy dzień promocji z wgranych gazetek; artykuły w gazetce wypadają z raportu. */
  leafletsSince: string | null
  noReactionUplift: number
  baseDays: number
  afterDays: number
  promotions: PromotionReportItem[]
}

/** Wgrany dokument dostawy z bazy (bon z pliku .xls) na liście do otwarcia. */
export type BaseDeliverySummary = {
  id: string
  number: string
  deliveryDate: string
  fileName: string
  lineCount: number
  uploadedAt: string
  uploadedBy: string | null
}

/**
 * „Tymczasowy bon dostawy” z Mercalys: dostawa z bazy wprowadzona do programu sklepu (osobno 538 i 88110), do
 * „Znajdź różnice” z tabelą dostawy z bazy. Nie jest zapisywany.
 */
export type TemporaryNote = {
  fileName: string
  /** Kod dostawcy z „Dostawca 539”. */
  supplierCode: string | null
  supplierName: string | null
  number: string | null
  createdAt: string | null
  lines: TemporaryNoteLine[]
}

export type TemporaryNoteLine = {
  /** Kod ITM8 z kartoteki sklepu (13 cyfr). */
  itm8: string
  ean: string | null
  /** Referencja dostawcy: ITM8 z pliku bazy. */
  reference: string | null
  name: string
  vatPercent: number | null
  quantity: number
  purchasePrice: number | null
  salePrice: number | null
  netValue: number | null
  /** Wszystkie EAN-y artykułu z kartoteki sklepu (bieżący, multikody, dawne pary). */
  catalogEans: string[]
}

/** Rodzaj opakowania z kaucją w pliku z bazy: NIE, PLA (PET), PUS (puszka), SZK (szkło). */
export type BaseDeposit = 'NIE' | 'PLA' | 'PUS' | 'SZK'

/**
 * Linia dostawy z bazy z artykułem z kartoteki (szukanym po ITM8 z pliku, a bez trafienia po EAN-ie; `null` = nowy).
 * `quantity` = przygotowane kartony × `pack`; `shortDated`: termin przed `requiredBestBefore` (dzień dostawy + dni dla
 * sklepu). `catalogDeposit`: kod kaucji z kartoteki (60 szkło, 70 puszka, 80 PET, 1 butelka zwrotna, 0 bez).
 */
export type BaseDeliveryLine = {
  id: string
  lineNo: number
  carrier: string
  orderNumber: string | null
  itm8: string
  ean: string | null
  name: string
  brand: string | null
  pack: number
  deposit: string | null
  bestBefore: string | null
  daysForStore: number | null
  requiredBestBefore: string | null
  shortDated: boolean
  supplierCode: string
  meaCode: string | null
  coliOrdered: number
  coliPrepared: number
  quantity: number
  purchasePrice: number
  retailPrice: number | null
  vatRate: number
  netValue: number
  regionCode: string | null
  familyCode: string | null
  subfamilyCode: string | null
  saleContent: number | null
  saleUnit: string | null
  cn: string | null
  product: DataEntryCatalogProduct | null
  catalogDeposit: number | null
}

export type BaseDelivery = {
  id: string
  number: string
  deliveryDate: string
  fileName: string
  uploadedAt: string
  uploadedBy: string | null
  carriers: { number: string; lineCount: number; netValue: number }[]
  lines: BaseDeliveryLine[]
}

/**
 * Kody MEA: do wpisania w Mercalys (z najnowszym dokumentem: bonem dostawy z bazy albo gazetką, {@code leaflet},
 * wtedy {@code deliveryNumber} to numer gazetki „44/2026”, a data to początek promocji) i ostatnie potwierdzenia.
 */
export type MeaOverview = {
  pending: { code: string; deliveryNumber: string; deliveryDate: string; deliveryCount: number; leaflet: boolean }[]
  confirmations: {
    id: string
    confirmedAt: string
    confirmedBy: string | null
    deletedAt: string | null
    deletedBy: string | null
    codes: string[]
  }[]
}

/** Wgrana gazetka na liście do otwarcia. */
export type LeafletSummary = {
  id: string
  year: number
  /** Tydzień = numer gazetki. */
  week: number
  fileName: string
  lineCount: number
  uploadedAt: string
  uploadedBy: string | null
}

/**
 * Pozycja gazetki: kolumny z pliku centrali i artykuł z kartoteki dla EAN-u z gazetki ({@code null} = nie znaleziono).
 * {@code linkId}: artykuł wybrany ręcznie (zapamiętany w sklepie). {@code price}: cena gazetkowa brutto, od -1 do 0
 * rabat od ceny z kartoteki (-0,2 = -20%). {@code regularPrice}: cena sprzedaży z kartoteki bez promocji z kasy.
 */
export type LeafletLine = {
  id: string
  lineNo: number
  section: string | null
  dateFrom: string
  dateTo: string
  /** Puste albo DDD = pozycja do opracowania w sklepie. */
  meaCode: string | null
  itm8: string | null
  promoItm8: string | null
  ean: string
  name: string
  brand: string | null
  promoForm: string | null
  megaKind: string | null
  clientPrice: string | null
  price: number | null
  /** OZNACZENIE PRZY PÓŁCE: rodzaj cenówki. */
  shelfLabel: string | null
  product: DataEntryCatalogProduct | null
  regularPrice: number | null
  linkId: string | null
}

export type Leaflet = Omit<LeafletSummary, 'lineCount'> & {
  lines: LeafletLine[]
  /** Kody MEA z gazetki, których jeszcze nie potwierdzono jako wpisane w Mercalys. */
  pendingMeaCodes: string[]
}

/** CURRENT: promocja trwa dziś, UPCOMING: dopiero się zacznie, NONE: artykułu nie było w gazetkach. */
export type LeafletLabelStatus = 'CURRENT' | 'UPCOMING' | 'PAST' | 'NONE'

/** Promocja artykułu z gazetki {@code week}/{@code year}; {@code shelfLabel} to OZNACZENIE PRZY PÓŁCE z pliku. */
export type LeafletLabelLine = {
  year: number
  week: number
  status: LeafletLabelStatus
  dateFrom: string
  dateTo: string
  shelfLabel: string | null
}

/** Trwające i przyszłe promocje artykułu, a gdy żadna nie trwa, także ostatnia zakończona. */
export type LeafletLabelArticle = {
  /** Pusty przy pozycji gazetki, której EAN-u kartoteka nie zna. */
  itm8: string | null
  ean: string | null
  name: string
  brand: string | null
  status: LeafletLabelStatus
  lines: LeafletLabelLine[]
}

export type LeafletLabelCheck = {
  /** Wszystkie trafienia; {@code articles} to najwyżej pierwsze 30. */
  total: number
  articles: LeafletLabelArticle[]
}

/** Artykuł dla EAN-u z gazetki (bez zer wiodących) po zmianie przypięcia. */
export type LeafletMatch = {
  ean: string
  linkId: string | null
  product: DataEntryCatalogProduct | null
  regularPrice: number | null
}

/** Artykuł z kartoteki w tabeli WZ (weryfikacja piekarni). */
export type BakeryArticle = {
  productId: string
  itm8: string
  ean: string
  name: string | null
  brand: string | null
}

/** Artykuły dostawcy; {@code hiddenProductIds}: odznaczone, domyślnie poza listą WZ. */
export type BakerySupplierArticles = {
  articles: BakeryArticle[]
  hiddenProductIds: string[]
}

/** Przyjęcie PZ dostawcy; {@code checkId}: weryfikacja, w której już jest. */
export type BakeryBon = {
  id: string
  number: string
  bonNumber: string
  documentDate: string
  supplierDocumentNumber: string | null
  purchaseValue: number
  correctedValue: number | null
  correctionCount: number
  invoices: string[]
  checkId: string | null
}

export type BakeryInvoice = {
  id: string
  invoiceNumber: string
  issueDate: string
  netAmount: number
  correction: boolean
  pinned: boolean
}

export type BakeryInvoiceCandidate = {
  id: string
  invoiceNumber: string
  issueDate: string
  netAmount: number
  correction: boolean
  correctedInvoices: string[] | null
}

export type BakeryWzLine = {
  article: BakeryArticle
  quantity: number | null
  scannedQuantity: number | null
  manual: boolean
}

export type BakeryWz = {
  id: string
  number: string | null
  deliveryDate: string | null
  dateEstimated: boolean
  sourceInvoice: string | null
  scanId: string | null
  scanFileName: string | null
  scanContentType: string | null
  lines: BakeryWzLine[]
}

export type BakeryCheck = {
  id: string
  supplierId: string
  supplierName: string
  supplierNip: string | null
  bons: BakeryBon[]
  invoices: BakeryInvoice[]
  wz: BakeryWz[]
  articles: BakeryArticle[]
  scanReading: boolean
  notices: string[]
}

export type BakeryCheckSummary = {
  id: string
  createdAt: string
  createdBy: string | null
  bons: string[]
  invoices: string[]
  wzCount: number
  filledWz: number
  from: string | null
  to: string | null
}

export type BakeryRowStatus = 'OK' | 'WZ' | 'BON' | 'NO_ARTICLE'

export type BakeryComparisonRow = {
  key: string
  itm8: string | null
  ean: string | null
  name: string | null
  wzQuantity: number
  invoiceQuantity: number | null
  correctionQuantity: number | null
  finalQuantity: number | null
  price: number | null
  invoiceValue: number | null
  bonQuantity: number | null
  bonValue: number | null
  difference: number | null
  differenceValue: number | null
  status: BakeryRowStatus
  linkCode: string | null
  invoiceUnit: string | null
}

export type BakeryComparison = {
  rows: BakeryComparisonRow[]
  totals: {
    wzValue: number
    invoiceValue: number
    correctionValue: number
    finalValue: number
    bonValue: number
    differenceValue: number
    differences: number
  }
  unavailable: { invoiceNumber: string; reason: string }[]
  pendingCorrections: number
  hasBon: boolean
  sellerNip: string | null
  sellerName: string | null
}

/** Odczyt skanu WZ przez AI: propozycja ilości do tabeli. */
export type BakeryScanReading = {
  number: string | null
  deliveryDate: string | null
  lines: { name: string | null; quantity: number | null; note: string | null; productId: string | null }[]
}

export type StocktakeBucket = 'RECOUNT' | 'SYSTEM_STOCK' | 'PRODUCTION' | 'REST'

export type StocktakeReason =
  | 'SOLD_WHEN_ZERO'
  | 'SOLD_MORE_THAN_COUNTED'
  | 'SWAP_STRONG'
  | 'SWAP_POSSIBLE'
  | 'DIGITS_SWAPPED'
  | 'WRONG_SCALE'
  | 'NOT_COUNTED'
  | 'LARGE_DIFFERENCE'

export type StocktakeSummary = {
  id: string
  number: string
  name: string
  stocktakeDate: string
  lineCount: number
  netValue: number
  lastImportAt: string | null
}

export type StocktakeLine = {
  itm8: string
  ean: string | null
  name: string
  brand: string | null
  regionCode: string | null
  regionName: string | null
  realQuantity: number
  theoreticalQuantity: number
  differenceQuantity: number
  value: number
  firstValue: number
  articleKind: string | null
  blockType: string | null
  soldAfter: number
  deliveredAfter: number
  bucket: StocktakeBucket
  reasons: StocktakeReason[]
}

export type StocktakePair = {
  plusItm8: string
  minusItm8: string
  strength: 'SWAP_STRONG' | 'SWAP_POSSIBLE'
}

export type StocktakeImport = {
  id: string
  stocktakeId: string
  fileName: string
  selection: string | null
  wholeStore: boolean
  reportGeneratedAt: string
  lineCount: number
  differenceValue: number
  createdCount: number
  updatedCount: number
  resolvedCount: number
  importedBy: string | null
  importedAt: string
}

export type Stocktake = {
  id: string
  number: string
  name: string
  stocktakeDate: string
  salesThrough: string | null
  lineCount: number
  resolvedCount: number
  netValue: number
  absoluteValue: number
  firstAbsoluteValue: number
  buckets: { bucket: StocktakeBucket; lineCount: number; absoluteValue: number }[]
  lines: StocktakeLine[]
  pairs: StocktakePair[]
  imports: StocktakeImport[]
}

export type ScheduleDayOffKind = 'WN' | 'W5' | 'W'

export type ScheduleDepartment = { id: string; name: string; canEdit: boolean }

/** Godziny jako GG:MM. {@code store}: numer innego sklepu, gdy ten dzień jest poza tym grafikiem. */
export type ScheduleCell = {
  date: string
  start: string | null
  end: string | null
  storeId: string | null
  store: string | null
  absent: boolean
  /** Rodzaj nieobecności; bez dostępu do Kadr urlop wypoczynkowy i na żądanie, a resztę jako {@code OTHER}. */
  absence: LeaveKind | 'OTHER' | null
  /** Oznaczony dzień wolny; przy WN {@code forDate} to niedziela albo święto, za które przysługuje. */
  dayOff: ScheduleDayOffKind | null
  forDate: string | null
  /** Wpis nieobecności z Kadr; tylko z dostępem do Kadr, żeby dało się go poprawić albo usunąć z grafiku. */
  leave: StaffLeave | null
  /** Czas wolny za nadgodziny tego dnia w minutach (art. 151² KP); tylko dla układającego. */
  timeOffMinutes: number | null
  timeOffInitiative: TimeOffInitiative | null
}

/** Na wniosek pracownika godzina za godzinę, z inicjatywy pracodawcy półtorej godziny za godzinę (art. 151² KP). */
export type TimeOffInitiative = 'EMPLOYEE' | 'EMPLOYER'

/** Saldo okresu pracownika do okna decyzji o nadgodzinach, liczone jak w grafiku. */
export type TimeBalance = {
  periodStart: string
  periodEnd: string
  workedMinutes: number
  normMinutes: number | null
  dailyOvertimeMinutes: number
  toCompensateMinutes: number
  actualUntil: string | null
  dailyLimitMinutes: number
}

export type ScheduleWarning = { date: string | null; message: string; basis: string }

/** {@code guest}: spoza działu albo sklepu, ma tu dni. Liczby w minutach; bez prawa układania albo umowy o pracę puste. */
export type ScheduleRow = {
  employeeId: string
  name: string
  positionId: string
  positionName: string
  groupId: string | null
  homeStore: string
  guest: boolean
  contract: string | null
  monthMinutes: number | null
  monthNormMinutes: number | null
  periodMinutes: number | null
  periodNormMinutes: number | null
  fixedShifts: StaffShift[]
  cells: ScheduleCell[]
  warnings: ScheduleWarning[]
  /** Przepracowane niedziele i święta bez WN. */
  owedDays: string[]
  /** Dni miesiąca bez żadnej umowy; pusta, gdy Kadry nie mają umów tej osoby. */
  outOfContractDays: string[]
  /**
   * Ile zaplanować w tym miesiącu, żeby okres zamknął się w wymiarze: wymiar okresu minus to, co przepracowano
   * i zaplanowano poza tym miesiącem. Godziny miesiąca i okresu liczą się za dni, które minęły, z kart czasu pracy.
   */
  monthTargetMinutes: number | null
  /** Saldo wcześniejszych miesięcy okresu (na plusie: ponad wymiar); pusty w pierwszym miesiącu okresu. */
  carriedMinutes: number | null
  /** Nadgodziny dobowe z kart w okresie (ponad 8 h albo 7 h), poza saldem: do odebrania czasem wolnym albo zapłaty. */
  dailyOvertimeMinutes: number | null
  /** Ostatni dzień, za który godziny są z kart czasu pracy; pusty bez odbić. */
  actualUntil: string | null
  /** Czas wolny za nadgodziny ułożony w okresie; liczy się do wymiaru jak praca. */
  timeOffMinutes: number | null
  /** Nadgodziny dobowe bez czasu wolnego (na minusie: czasu wolnego za dużo); pusty bez umowy o pracę. */
  toCompensateMinutes: number | null
  /** Wiersz godzin na umowie zlecenia, pod wierszem umowy o pracę tej samej osoby. */
  civil: boolean
  /** Stawka za godzinę od tego miesiąca; pusta bez stawki albo gdy dział nie liczy wynagrodzeń. */
  hourlyRate: number | null
  /**
   * Szacowane wynagrodzenie miesiąca: godziny (z kart za dni, które minęły) razy stawka, z dodatkiem 50% za
   * nieodebrane nadgodziny dobowe. Tylko dla układającego grafik działu, który liczy wynagrodzenia.
   */
  pay: number | null
  overtimeSupplement: number | null
}

export type ScheduleDay = { date: string; holiday: string | null; closed: boolean }

/** {@code counts}: obsada kolejnych dni miesiąca. {@code minCount} i {@code targetCount}: najmniejsza i docelowa. */
export type SchedulePosition = {
  id: string
  name: string
  minCount: number | null
  targetCount: number | null
  shifts: StaffShift[]
  counts: number[]
}

/** {@code late}: pierwsza wersja przekazana później niż tydzień przed miesiącem (art. 129 § 3 KP). */
export type ScheduleVersion = { number: number; publishedAt: string; publishedBy: string | null; late: boolean }

export type Schedule = {
  departmentId: string
  month: string
  status: 'DRAFT' | 'PUBLISHED' | 'CHANGED'
  canEdit: boolean
  canConfigure: boolean
  viewedVersion: number | null
  periodStart: string
  periodEnd: string
  versions: ScheduleVersion[]
  days: ScheduleDay[]
  positions: SchedulePosition[]
  /** Własne grupy; bez nich tabela dzieli ludzi według stanowisk. */
  groups: { id: string; name: string }[]
  rows: ScheduleRow[]
  stores: StoreSummary[]
  /** Dział liczy wynagrodzenia, a oglądający układa jego grafik. */
  showsPay: boolean
}

export type ScheduleSettings = {
  managerIds: string[]
  people: { userId: string; name: string; positionName: string }[]
  minimums: { positionId: string; positionName: string; minCount: number | null; targetCount: number | null }[]
  /** Przerwy działu w tym sklepie (GG:MM). Grafik ich nie pokazuje, posłużą rejestracji czasu pracy. */
  breaks: ScheduleBreak[]
  /** Tolerancja kart czasu pracy: odcinki do tylu minut nie czekają na decyzję. */
  toleranceMinutes: number
  /** Grafik liczy wynagrodzenia (widzą je tylko układający grafik działu). */
  payEnabled: boolean
}

export type ScheduleBreak = { start: string; end: string; paid: boolean }

/** Odcinek karty czasu pracy: praca w grafiku, przerwa niepłatna, czas poza grafikiem, brak czasu z grafiku. */
export type TimeSegmentPart = 'WORK' | 'BREAK' | 'OVERTIME' | 'SHORTFALL'

export type TimeSegmentStatus =
  | 'OK'
  | 'BREAK'
  | 'TOLERATED_OVERTIME'
  | 'TOLERATED_SHORTFALL'
  | 'PENDING'
  | 'EMPLOYER_ORDER'
  | 'EMPLOYEE_REQUEST'
  | 'REJECTED'
  | 'UNEXCUSED'
  | 'JUSTIFIED'
  | 'OFFSET_SHORTFALL'
  | 'OFFSET_OVERTIME'

export type TimeDecisionKind = 'EMPLOYER_ORDER' | 'EMPLOYEE_REQUEST' | 'REJECTED' | 'JUSTIFIED' | 'OFFSET'

/** Dni do reakcji kierownika (czeka na decyzję, brak, brak wyjścia) z ostatnich 31 dni, po działach. */
export type TimeAttention = { total: number; departments: { departmentId: string; count: number }[] }

/** Czas lokalny sklepu jako RRRR-MM-DDTGG:MM(:SS). */
export type TimeSegment = {
  part: TimeSegmentPart
  start: string
  end: string
  minutes: number
  status: TimeSegmentStatus
  decisionId: string | null
  decidedBy: string | null
  note: string | null
  /** Podpisane polecenie albo wniosek o nadgodziny dołączony do decyzji. */
  signedFile: { id: string; fileName: string } | null
}

export type TimeDayCard = {
  date: string
  plannedStart: string | null
  plannedEnd: string | null
  absence: LeaveKind | 'OTHER' | null
  punches: { id: string; at: string; source: 'READER' | 'SIMULATED' }[]
  /** Wejście bez wyjścia; {@code missingExit}: ponad 16 h temu, czyli zapomniane wyjście. */
  openSince: string | null
  missingExit: boolean
  segments: TimeSegment[]
  planned: number
  worked: number
  overtime: number
  pending: number
  shortfall: number
  /** Godziny zlecenia z grafiku: odbicia w nich nie wchodzą do karty umowy o pracę. */
  civilStart: string | null
  civilEnd: string | null
}

export type TimeTotals = { planned: number; worked: number; overtime: number; pending: number; shortfall: number }

export type TimeCardRow = {
  employeeId: string
  name: string
  positionName: string
  cardUid: string | null
  /** Numer karty RCP do Symfonii (nadany w aplikacji), inny niż karta NFC. */
  rcpNumber: number | null
  /** Ewidencja godzin zlecenia: same odbicia, bez decyzji; osoba z obiema umowami ma dwa wiersze. */
  civil: boolean
  days: TimeDayCard[]
  totals: TimeTotals
}

export type TimeCards = {
  departmentId: string
  from: string
  to: string
  canDecide: boolean
  canConfigure: boolean
  canSimulate: boolean
  toleranceMinutes: number
  days: { date: string; holiday: string | null }[]
  rows: TimeCardRow[]
  /** Osoby z działu bez karty i bez ewidencji zlecenia w okresie (B2B, bez umowy) z powodem. */
  withoutCard: { name: string; positionName: string; reason: string }[]
}

export type TimeSettings = {
  employer: {
    name: string | null
    address: string | null
    nip: string | null
    regon: string | null
    representative: string | null
    saved: boolean
    /** Bez zapisanych danych podpowiedź z GUS po NIP-ie sklepu. */
    fromGus: boolean
  }
  /** {@code token}: klucz czytnika, tylko zaraz po dodaniu. */
  readers: { id: string; name: string; createdAt: string; token: string | null }[]
  cards: {
    employeeId: string
    name: string
    departmentName: string
    cardUid: string | null
    rcpNumber: number | null
  }[]
}

/** Dostawca z przyjęć PZ sklepu do wyboru w obrotach z dostawcami. */
export type TurnoverSupplier = { code: string; name: string; nip: string | null; lastDelivery: string }

/** Zakup z bonów, korekty przypięte do przyjęć i zakup po korektach (netto). */
export type SupplierTurnoverAmount = { delivered: number; corrections: number; net: number }

export type SupplierTurnoverRow = {
  /** Pusty: „Bez nomenklatury”. */
  code: string
  level: 'REGION' | 'FAMILY' | 'SUBFAMILY' | null
  name: string
  /** Po miesiącach, w kolejności `months` raportu. */
  values: SupplierTurnoverAmount[]
  total: SupplierTurnoverAmount
}

export type SupplierTurnoverReport = {
  supplierCode: string
  supplierName: string
  from: string
  to: string
  documentsCoveredUntil: string | null
  /** Pierwsze dni miesięcy okresu. */
  months: string[]
  deliveries: number[]
  totals: SupplierTurnoverAmount[]
  total: SupplierTurnoverAmount
  rows: SupplierTurnoverRow[]
  unattachedCorrections: { count: number; purchaseValue: number }
}

/** Dostawca do kontroli strat w raporcie protokołów: własna grupa artykułów z kolorem z palety. */
export type LossControlGroup = {
  id: string
  name: string
  color: string
  itm8s: string[]
}
