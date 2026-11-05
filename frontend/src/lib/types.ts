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
  createdAt: string
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
}

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

export type SalesComparisonRow = {
  /** Kod nomenklatury bez spacji ("01", "0101", "010101") albo ITM8; pusty = sprzedaż bez nomenklatury. */
  code: string
  /** Pusta, gdy kodu nie ma w nomenklaturze sklepu albo artykułu w kartotece. */
  name: string | null
  ean: string | null
  brand: string | null
  previous: SalesFigures
  current: SalesFigures
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
  /** STAN, może być ujemny. */
  stock: number | null
  rotation: number | null
  caseSize: number | null
  reportGeneratedAt: string | null
  /** Rejon, rodzina, podrodzina (te, które są w nomenklaturze sklepu). */
  nomenclaturePath: NomenclatureOption[]
}

/** Dostawa artykułu (przyjęcie PZ, z raportu bez korekt): ilość i cena zakupu netto sztuki. */
export type ArticleDelivery = {
  documentId: string
  documentNumber: string
  documentDate: string
  supplierCode: string | null
  supplierName: string | null
  quantity: number
  purchasePrice: number | null
  purchaseValue: number
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
  itemCount: number
  totalCost: number
  /** Pusty, gdy przy całym przepisie brakuje wydajności. */
  costPerKg: number | null
  /** Cena do eksportu: własna, a bez niej wyliczona. */
  salePrice: number | null
  /** Koszt + narzut + VAT, w górę do ,99 zł. */
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
  /** Narzut na koszt składników, z którym liczy się cena sprzedaży. */
  markupPercent: number
  items: PriceProduct[]
}

export type PriceCalculationRequest = {
  productId: string
  mode: CalculationMode
  batchYield: number | null
  vatRate: number
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
  /** Cena katalogowa brutto, od niej liczy się marża. */
  salePrice: number
  documentSalePrice: number
  promotion: LowMarginPromotion | null
  marginPercent: number
  requiredMargin: number
  /** Cena zakupu dająca marżę wymaganą. */
  targetPurchasePrice: number
  excessValue: number
  corrected: boolean
}

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
}

export type LowMarginReport = {
  from: string
  to: string
  periodDays: number
  marginGap: number
  marginsConfigured: boolean
  documentsCoveredUntil: string | null
  documentsReportGeneratedAt: string | null
  /** Dostawcy, których nie dotyczy marża wymagana. */
  excludedSuppliers: ReportSupplier[]
  /** Lista dostawców sklepu do wyboru. */
  suppliers: ReportSupplier[]
  deliveries: LowMarginDelivery[]
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
