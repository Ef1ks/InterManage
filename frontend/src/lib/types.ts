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

export type DeliOrderStatus = 'NEW' | 'FULFILLED'

export type DeliOrderSummary = {
  id: string
  formStore: StoreSummary
  store: StoreSummary
  status: DeliOrderStatus
  /** Data bez godziny, RRRR-MM-DD. */
  deliveryDate: string
  submittedAt: string
  submittedBy: string | null
  itemCount: number
  corrected: boolean
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
}

export type DeliOrder = Omit<DeliOrderSummary, 'itemCount' | 'corrected'> & {
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
}

/**
 * Suma netto faktur przyjęcia (z fakturami jego korekt od dostawcy) i wartość zakupu netto po korektach;
 * {@code difference} = faktury - przyjęcie. {@code confirmedAt}: różnicę ktoś potwierdził (dla tych kwot).
 */
export type InvoiceComparisonStatus =
  | 'MATCH'
  | 'PRICE'
  | 'QUANTITY'
  | 'MISSING_ON_INVOICE'
  | 'MISSING_IN_DELIVERY'
  | 'NO_CODE'

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
  invoiceNet: number
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

/** Faktura do przypięcia; {@code difference}: różnica kwoty netto od wartości dokumentu (bez znaku). */
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

export type DocumentGratis = {
  supplierId: string
  /** Pusty u dostawcy dodanego ręcznie. */
  supplierCode: string | null
  supplierName: string
  registeredBy: string | null
  registeredAt: string
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

/** Sprzedaż za okres; % marży i zmiany liczy front z sum. */
export type SalesFigures = {
  quantity: number
  /** Koszt zakupu FIFO netto. */
  costValue: number
  saleValueGross: number
  saleValueNet: number
  marginValue: number
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
  /** Do przyjęcia PZ można przypinać korekty; wartości i wydruk bonu liczą się razem z nimi. */
  correctable: boolean
  /** Korekty przypięte do przyjęcia, od najstarszej. */
  corrections: DeliveryCorrection[]
  /** Przyjęcie po korektach; {@code null}, gdy nie ma korekt. */
  corrected: CorrectedDelivery | null
  /** Korektę (błędu sklepu, od dostawcy) można przypiąć do przyjęcia. */
  attachableToDelivery: boolean
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

