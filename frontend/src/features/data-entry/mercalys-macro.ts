import { mercalysOpenLine } from '@/lib/mercalys-login'

/** Okna i ekrany Mercalys: identyfikatory z kodu stron, poprawiamy je tylko tutaj. */
const WINDOW = 'Mercalys'
/** Adobe Acrobat Reader z wydrukami bonów; po zamknięciach dostaw makro zamyka wszystkie jego okna. */
const PDF_WINDOW = 'ahk_class AcrobatSDIWindow'
/** Tyle makro czeka na PDF po ostatnim zamknięciu. */
const PDF_TIMEOUT_MS = 20000
/** Okno programu na pasku zadań; sam tytuł pasowałby też do innych programów z tym słowem. */
const PROGRAM_WINDOW = 'Mercalys ahk_exe Mercalys.exe'
const DIALOG = 'Les Mousquetaires'
const GRID = 'dgDetail'
/** Integracja może trwać, gdy plików jest dużo. */
const INTEGRATION_TIMEOUT_MS = 120000
const PAGE_TIMEOUT_MS = 30000

const MENU = {
  purchasesTab: 'mnTp_ACHAT',
  directDeliveries: 'mnTv_ACHAT_BlFournisseursDirects',
  deliveries: 'mnTv_ACHAT_Livraison',
  integration: 'mnTv_ACHAT_na-e014_IntegrationFichiersBl',
  blScreen: 'mnTv_ACHAT_ac-e082_GestionDesBl',
  orders: 'mnTv_ACHAT_Commande',
  orderEntry: 'mnTv_ACHAT_ac-e011_SaisieCommandeCompleteCmd',
} as const

const INTEGRATION_SCREEN = {
  confirmText: 'interacj',
  /** Strona z tabelą podsumowania integracji. */
  resultPage: 'na-e015',
  /** „Powrót” na wyniku integracji; dopiero po nim idziemy do Obsługi BL. */
  result: 'btnB1_Retour_LnkBouton',
  /** Bez plików do integracji zamiast tabeli przychodzi okienko (BoiteAlerte.aspx) bez tytułu, z przyciskiem Zamknij. */
  emptyWindow: 'ahk_class Internet Explorer_TridentDlgFrame',
  emptyText: 'brak PZ',
  emptyButton: 'Fermer',
} as const

/** Po Powrót z wyniku integracji strona się przeładowuje: klik w menu w tym czasie by przepadł. */
const AFTER_RESULT_BACK_MS = 1500

const BL_SCREEN = {
  /** Kolumna Nr Zamów, od zera: pusta = bon bez kodu. */
  orderColumn: 10,
  createCodeButton: 'btnB4_CreationCommande_LnkBouton',
  nextPageButton: 'pgrDetail_ibtSuivant',
  /** Po kodzie Mercalys pokazuje okno z wynikiem; odtwarzacz zamyka je tylko przy tym tekście, inny go zatrzymuje. */
  confirmText: 'wygenerowane',
  confirmButton: 'Fermer',
} as const

const ORDER_SCREEN = {
  criterion: 'lstZ1_Critere_TxtBox',
  /** 3 = oczekuje na dostawę. Lista przeładowuje się tylko po prawdziwych klawiszach, nie po zmianie wartości. */
  criterionKeys: '3{Enter}',
  numberColumn: 1,
  /** Kolumna Ident. zamów.: po zapisie ma tam być Nr BL. */
  identificationColumn: 12,
  headerButton: 'btnB11_Entete_LnkBouton',
  identificationField: 'txtZ16_IdentificationCde_TxtBox',
  saveHeaderButton: 'btnB4_Valider_LnkBouton',
  saveHeaderText: 'zachowa',
  closeButton: 'btnB3_Cloturer_LnkBouton',
  closeText: 'zamkn',
  printCheckbox: 'chkP1_BonValo_CheckBox1',
  approveCloseButton: 'btnP1_Valider_LnkBouton',
  firstPageButton: 'pgrDetail_ibtPremier',
  nextPageButton: 'pgrDetail_ibtSuivant',
} as const

/** Bon do makra: numer faktury i kod dostawcy; pusty kod = makro nie sprawdza dostawcy. */
export type MacroDocument = {
  number: string
  supplierCode: string
}

/**
 * Makro dla odtwarzacza: przywraca Mercalys z paska zadań i otwiera zakładkę Zakup, potem integracja plików, kody na bonach z listy bez Nr Zamów, Nr BL
 * w nagłówkach, a po jednym potwierdzeniu zamknięcia z wydrukiem bonu i powrót do przeglądarki. Bon bez kodu zatrzymuje makro przed nagłówkami,
 * a podsumowanie mówi, czego nie znalazło. Z `print` bony otwarte w Acrobacie idą przed zamknięciem do drukarki domyślnej
 * (PDFPRINTALL, odtwarzacz od wersji 51), bez niego są tylko zamykane. CRLF jak w Windows. To samo makro na komputer
 * sklepu składa serwer (`InvoiceCloseMacro`): zmiana ekranów = zmiana w obu.
 */
export function buildDeliveryMacro(documents: MacroDocument[], print = false) {
  const bl = BL_SCREEN
  const order = ORDER_SCREEN
  return (
    [
      `# Zamknięcie faktur: ${documents.length}`,
      ...documents.map((document) => `WEBLIST|${document.supplierCode}|${document.number}`),
      mercalysOpenLine(PROGRAM_WINDOW),
      `WEBENSURE|${WINDOW}|${MENU.directDeliveries}|${MENU.purchasesTab}`,
      `WEBOPEN|${WINDOW}|${MENU.directDeliveries}|${MENU.integration}`,
      `WEBWAIT|${DIALOG}|Oui|${INTEGRATION_SCREEN.confirmText}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${DIALOG}|Oui`,
      // Tabela z podsumowaniem (na-e015). Powrót ma też ekran sprzed integracji, więc czekamy na tę stronę, a nie na sam
      // przycisk: inaczej Powrót padał w trakcie integracji. Potem Powrót i Obsługa BL (klik w menu ponawiany, gdyby przepadł).
      // Gdy nie ma czego integrować, zamiast tabeli jest „brak PZ bezpośrednich do integracji”: Zamknij i od razu Obsługa BL
      // (odtwarzacz od wersji 0.53).
      `WEBPAGE|${WINDOW}|${INTEGRATION_SCREEN.resultPage}|${INTEGRATION_SCREEN.result}|${INTEGRATION_TIMEOUT_MS}|${INTEGRATION_SCREEN.emptyWindow}|${INTEGRATION_SCREEN.emptyText}|${INTEGRATION_SCREEN.emptyButton}|bez-integracji`,
      `WEBCLICK|${WINDOW}|${INTEGRATION_SCREEN.result}`,
      `LABEL|bez-integracji`,
      `SLEEP|${AFTER_RESULT_BACK_MS}`,
      `WEBOPEN|${WINDOW}|${MENU.directDeliveries}|${MENU.blScreen}|${bl.createCodeButton}`,
      `WEBWAIT|${WINDOW}|${bl.createCodeButton}||${PAGE_TIMEOUT_MS}`,
      `WEBROWS|${WINDOW}|${GRID}|${bl.orderColumn}|${bl.createCodeButton}|${bl.nextPageButton}|${documents.length}|${DIALOG}|${bl.confirmText}|${bl.confirmButton}`,
      `WEBOPEN|${WINDOW}|${MENU.orders}|${MENU.orderEntry}|${order.criterion}`,
      `WEBWAIT|${WINDOW}|${order.criterion}`,
      `WEBKEY|${WINDOW}|${order.criterion}|${order.criterionKeys}`,
      `WEBWAIT|${WINDOW}|${order.headerButton}`,
      `WEBHEADERS|${WINDOW}|${GRID}|${order.numberColumn}|${order.identificationColumn}|${order.headerButton}|${order.identificationField}|${order.saveHeaderButton}|${DIALOG}|${order.saveHeaderText}|Oui|${order.firstPageButton}|${order.nextPageButton}`,
      `WEBCLOSE|${WINDOW}|${GRID}|${order.numberColumn}|${order.identificationColumn}|${order.closeButton}|${DIALOG}|${order.closeText}|Oui|${order.printCheckbox}|${order.approveCloseButton}|${order.firstPageButton}|${order.nextPageButton}`,
      `${print ? 'PDFPRINTALL' : 'WINCLOSEALL'}|${PDF_WINDOW}|${PDF_TIMEOUT_MS}`,
      `WINMIN|${PROGRAM_WINDOW}`,
    ].join('\r\n') + '\r\n'
  )
}

/** Obsługa BL bazy (ac-e034): lista plików bonów, a po Wybór linie bonu (ac-e080) z błędami. */
const BASE_BL_SCREEN = {
  menuItem: 'mnTv_ACHAT_ac-e034_BlEuropa',
  /**
   * Nagłówek listy plików. Kliknięcie menu z linii bonu też pokazuje listę pod starym adresem (postback), więc od drugiego
   * pliku listę rozpoznajemy po treści strony, nie po adresie (WEBPAGE czekało na ac-e034 do końca limitu).
   */
  filesHeading: 'Lista PZ do przetwarzania',
  selectionButton: 'btnSelection_LnkBouton',
  /** Przycisk ekranu linii bonu: mówi, że Wybór otworzył bon. */
  detailScreen: 'btnB3_Integration_LnkBouton',
  /** Lista plików: nazwa `095_<nr bonu>_<dostawca>` (00538, 88110) w kolumnie 0, data w 1. */
  filesGrid: 'dgDetail',
  /** Linie bonu: ITM8 (13 cyfr z zerami) w kolumnie 0, nr bonu w 4, błąd w kolumnie 7. */
  linesGrid: 'dgArticles',
  bonColumn: '4',
  missingItm8Text: 'ITM nie istnieje',
  /** Kolumna z kodem ITM8 od zera albo `auto`: pierwsza komórka z samym kodem do 8 cyfr. */
  itm8Column: '0',
  nextPageButton: 'pgrDetail_ibtSuivant',
  pageList: 'pgrDetail_ddlGoTo',
  /** Wiersze z błędami są na początku listy: strona bez „ITM nie istnieje” kończy przegląd. */
  stopOnEmptyPage: '1',
  /** Powrót z linii bonu na listę plików. */
  backButton: 'btnB1_Retour_LnkBouton',
} as const

/** Otwarcie dużego bonu potrafi trwać. */
const BASE_BL_TIMEOUT_MS = 60000

/** Przerwa po kliknięciu menu Obsługa BL bazy, zanim makro zacznie czekać na nową listę. */
const BASE_BL_REOPEN_MS = 1500

/** Dostawcy bazy: bon ma w Obsłudze BL bazy osobny plik od każdego (towar 538, alkohol 88110). */
const BASE_FILE_SUPPLIERS = ['538', '88110']

/**
 * „Zbierz brakujące ITM8”: dla każdego dostawcy z linii bonu (tylko 538, gdy nie ma alkoholu) Zakup → Obsługa BL bazy, wiersz z plikiem tego bonu i tego dostawcy
 * (nazwa `…_<nr bonu>_<dostawca>`, zera wiodące dowolne; brak pliku od dostawcy to nie błąd), Wybór, sprawdzenie, że linie
 * są z tego bonu, i kody ITM8 z błędem „ITM nie istnieje” ze wszystkich stron. Kody z wszystkich plików trafiają razem do
 * Schowka. Po każdym pliku Powrót i ponowne wejście w Obsługę BL bazy z menu (bez niego Mercalys zgłaszał błędy przy
 * kolejnym pliku), a listę rozpoznajemy po nagłówku, nie po adresie. Bez żadnego pliku makro staje.
 * Na końcu powrót na Zakup i Mercalys na pasek zadań. Wymaga odtwarzacza od
 * wersji 45 (etykieta w WEBPICKRE, także przy pustej liście plików, numer bonu w WEBCOLLECT, WEBCOLLECTED).
 */
export function buildMissingItm8Macro(bonNumber: string, supplierCodes: string[] = []) {
  const screen = BASE_BL_SCREEN
  const digits = bonNumber.replace(/\D/g, '').replace(/^0+/, '')
  // Plik bazy jest tylko od dostawcy, który ma linie w dokumencie: bon bez alkoholu nie ma pliku 88110, więc krok dla niego
  // tylko czekał. Bez linii (stary szkic) oba pliki bazy.
  const normalize = (code: string) => code.replace(/\D/g, '').replace(/^0+/, '')
  const fromDocument = [...new Set(supplierCodes.map(normalize).filter(Boolean))]
  const suppliers = fromDocument.length
    ? [
        ...BASE_FILE_SUPPLIERS.filter((code) => fromDocument.includes(code)),
        ...fromDocument.filter((code) => !BASE_FILE_SUPPLIERS.includes(code)),
      ]
    : BASE_FILE_SUPPLIERS
  // Lista plików: przycisk Wybór na stronie z nagłówkiem listy. Adresu nie sprawdzamy, bo po Powrót i po kliknięciu menu
  // z linii bonu zostaje adres linii (ac-e080), a sam przycisk nie wystarcza: bez nagłówka kolejny plik zaczynał się jeszcze
  // na liniach poprzedniego bonu.
  const waitForFiles = `WEBWAIT|${WINDOW}|${screen.selectionButton}|${screen.filesHeading}|${PAGE_TIMEOUT_MS}`
  const openFiles = [
    `WEBENSURE|${WINDOW}|${MENU.deliveries}|${MENU.purchasesTab}`,
    `WEBOPEN|${WINDOW}|${MENU.deliveries}|${screen.menuItem}`,
    // Klik w menu przeładowuje stronę, a stara lista ma ten sam nagłówek: bez przerwy czekanie przeszłoby od razu.
    `SLEEP|${BASE_BL_REOPEN_MS}`,
    waitForFiles,
  ]
  const fileSteps = suppliers.flatMap((supplier, index) => {
    const label = `bon-${digits}-${supplier}`
    return [
      `# Plik bonu ${bonNumber} od dostawcy ${supplier}`,
      // Pierwszy plik otwiera listę z menu, kolejne zaczynają na liście, na którą wróciło makro po poprzednim pliku.
      ...(index === 0 ? openFiles : [waitForFiles]),
      `WEBPICKRE|${WINDOW}|${screen.filesGrid}|(?<!\\d)0*${digits}\\D+0*${supplier}(?!\\d)|${PAGE_TIMEOUT_MS}|${label}`,
      `WEBCLICK|${WINDOW}|${screen.selectionButton}`,
      `WEBWAIT|${WINDOW}|${screen.detailScreen}||${BASE_BL_TIMEOUT_MS}`,
      `WEBCOLLECT|${WINDOW}|${screen.linesGrid}|${screen.missingItm8Text}|${screen.itm8Column}|${screen.nextPageButton}|${screen.pageList}|${screen.stopOnEmptyPage}|${screen.bonColumn}|${bonNumber}`,
      // Po liniach bonu Powrót, a potem jeszcze raz wejście w Obsługę BL bazy: sam Powrót zostawia listę, na której
      // Mercalys przy kolejnym Wybór zgłasza błędy.
      `WEBCLICK|${WINDOW}|${screen.backButton}`,
      waitForFiles,
      ...openFiles,
      `LABEL|${label}`,
    ]
  })
  return (
    [
      `# Zbierz brakujące ITM8: bon ${bonNumber}, dostawcy ${suppliers.join(', ')}`,
      mercalysOpenLine(PROGRAM_WINDOW),
      ...fileSteps,
      `WEBCOLLECTED|W Obsłudze BL bazy nie ma pliku bonu ${bonNumber} od dostawców ${suppliers.join(', ')}.`,
      `WEBCLICK|${WINDOW}|${MENU.purchasesTab}`,
      `WINMIN|${PROGRAM_WINDOW}`,
    ].join('\r\n') + '\r\n'
  )
}

/** Nowy ITM8 artykułu: karta artykułu (ar-e001) i „Kod ITM8 :” (ar-e006, ITM8 dostawcy przy artykule). */
const ITM8_SCREEN = {
  articleTab: 'mnTp_ARTICLE',
  articleMenuItem: 'mnTv_ARTICLE_ar-e001_FicheArticle',
  eanField: 'txtZ4_CodeEAN_TxtBox',
  itm8Field: 'txtZ1_CodeITM_TxtBox',
  /** „Kod ITM8 :” na karcie: link pojawia się dopiero po wczytaniu artykułu. */
  itm8Button: 'lnkZ1_CodeITM_LnkAviso',
  newItm8Field: 'txtZ02_Itm_TxtBox',
  supplierField: 'lstZ04_Fournisseur_TxtBox',
  supplierRefField: 'txtZ06_RefFrs_TxtBox',
  minOrderField: 'txnZ08_MinCde_TxtBox',
  packField: 'txnZ07_Conditionnement_TxtBox',
  deleteDateField: 'txdZ09_DateSuppr_TxtBox',
  activeCheckbox: 'chkZ10_Actif_CheckBox1',
  /** „C.Z. po rab.” */
  netPriceField: 'txtZ14_PANet_TxtBox',
  approveButton: 'btnB4_Valider_LnkBouton',
  createText: 'Utworzy',
  backButton: 'btnB1_Retour_LnkBouton',
  /** „ZER.” jest tylko na karcie artykułu, więc mówi też, że powrót z ar-e006 się udał. */
  resetButton: 'btnB2_RAZ_LnkBouton',
} as const

export const ITM8_MACRO_CONFIG = {
  /** Po „Tak” strona się przeładowuje: klik w starą stronę by przepadł. */
  afterConfirm: 1500,
  /** Po kliknięciu pola wyboru „Aktywny” strona może się przeładować. */
  afterUncheck: 500,
} as const

export type Itm8MacroItem = {
  itm8: string
  ean: string
  /** Kod dostawcy z dokumentu bez zer wiodących (538 baza, 88110 alkohol). */
  supplierCode: string
  /** Sztuk w kartonie (PAK). */
  pack: number
  /** Data usunięcia, RRRR-MM-DD. */
  deleteDate: string
  /** Cena zakupu netto sztuki po rabacie (C_Cesja). */
  purchasePrice: number
}

/** Liczba z przecinkiem, jak pokazuje ją Mercalys (12 → „12”, 1.5 → „1,50”). */
const commaNumber = (value: number, digits: number) => value.toFixed(digits).replace('.', ',')

/**
 * „Uzupełnij ITM8”: dla każdego artykułu karta artykułu po EAN-ie, „Kod ITM8 :”, nowy ITM8 z dostawcą, ref. dostawcy
 * = ITM8, najpierw opakowanie, potem zamówienie min. (oba = PAK), data usunięcia, odznaczony „Aktywny”, C.Z. po rab., sprawdzenie wszystkich
 * pól, Zatwierdź, Tak, Powrót i ZER.; na końcu Mercalys na pasek zadań. Wszystko po identyfikatorach pól, bez myszy:
 * pole, które nie przyjęło wartości, albo inne pytanie niż „Utworzyć zapis ?” zatrzymują makro przed zapisem.
 * Wymaga odtwarzacza od wersji 39 (WEBIFVALUE).
 */
export function buildItm8Macro(items: Itm8MacroItem[]) {
  const screen = ITM8_SCREEN
  const c = ITM8_MACRO_CONFIG
  const out = [
    `# Uzupełnij ITM8: ${items.length} poz.`,
    mercalysOpenLine(PROGRAM_WINDOW),
    `WEBENSURE|${WINDOW}|${screen.articleMenuItem}|${screen.articleTab}`,
    `WEBCLICK|${WINDOW}|${screen.articleMenuItem}`,
  ]
  items.forEach((item, index) => {
    const itm8 = item.itm8.replace(/\D/g, '')
    const itm8Value = `^0*${withoutLeadingZeros(itm8)}$`
    const supplierValue = `^0*${withoutLeadingZeros(item.supplierCode)}$`
    const pack = commaNumber(item.pack, 0)
    const packValue = `^${pack}(,0+)?$`
    const price = commaNumber(item.purchasePrice, 2)
    const priceValue = `^${price}0*$`
    const date = mercalysDate(item.deleteDate)
    const inactive = `nieaktywny-${index + 1}`
    out.push(
      `# ${index + 1}/${items.length}: ITM8 ${itm8} · EAN ${item.ean}`,
      `WEBWAIT|${WINDOW}|${screen.eanField}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.eanField}|^a${item.ean}{Enter}`,
      // Wczytany artykuł ma ITM8; po ZER. pole jest puste, więc stara karta nie uda wczytanej. Pola EAN nie
      // sprawdzamy: karta pokazuje w nim EAN priorytetowy artykułu, który bywa innym kodem niż wpisany.
      `WEBVALUE|${WINDOW}|${screen.itm8Field}|^0*[1-9]\\d*$|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${screen.itm8Button}`,
      `WEBWAIT|${WINDOW}|${screen.newItm8Field}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.newItm8Field}|^a${itm8}{Tab}`,
      `WEBVALUE|${WINDOW}|${screen.newItm8Field}|${itm8Value}|${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.supplierField}|^a${item.supplierCode}{Tab}`,
      `WEBVALUE|${WINDOW}|${screen.supplierField}|${supplierValue}|${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.supplierRefField}|^a${itm8}{Tab}`,
      // Najpierw opakowanie, potem zamówienie min.: Mercalys sprawdza zamówienie min. względem opakowania.
      `WEBKEY|${WINDOW}|${screen.packField}|^a${pack}{Tab}`,
      `WEBKEY|${WINDOW}|${screen.minOrderField}|^a${pack}{Tab}`,
      `WEBKEY|${WINDOW}|${screen.deleteDateField}|^a${date}{Tab}`,
      // Odznaczenie „Aktywny”: klik tylko, gdy jest zaznaczony (WEBCHECK umie tylko zaznaczyć).
      `WEBIFVALUE|${WINDOW}|${screen.activeCheckbox}|^tak$|${inactive}|1000`,
      `WEBCLICK|${WINDOW}|${screen.activeCheckbox}`,
      `SLEEP|${c.afterUncheck}`,
      `LABEL|${inactive}`,
      `WEBKEY|${WINDOW}|${screen.netPriceField}|^a${price}{Tab}`,
      // Przed zapisem wszystko jeszcze raz: przeładowanie po polu dostawcy albo pola wyboru mogło coś wyczyścić.
      `WEBVALUE|${WINDOW}|${screen.newItm8Field}|${itm8Value}`,
      `WEBVALUE|${WINDOW}|${screen.supplierField}|${supplierValue}`,
      `WEBVALUE|${WINDOW}|${screen.supplierRefField}|${itm8Value}`,
      `WEBVALUE|${WINDOW}|${screen.packField}|${packValue}`,
      `WEBVALUE|${WINDOW}|${screen.minOrderField}|${packValue}`,
      `WEBVALUE|${WINDOW}|${screen.deleteDateField}|${datePattern(item.deleteDate)}`,
      `WEBVALUE|${WINDOW}|${screen.activeCheckbox}|^nie$`,
      `WEBVALUE|${WINDOW}|${screen.netPriceField}|${priceValue}`,
      `WEBCLICK|${WINDOW}|${screen.approveButton}`,
      `WEBWAIT|${DIALOG}|Oui|${screen.createText}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${DIALOG}|Oui`,
      `SLEEP|${c.afterConfirm}`,
      `WEBCLICK|${WINDOW}|${screen.backButton}`,
      `WEBWAIT|${WINDOW}|${screen.resetButton}||${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${screen.resetButton}`,
      `WEBVALUE|${WINDOW}|${screen.eanField}|^$|${PAGE_TIMEOUT_MS}`,
      `WEBVALUE|${WINDOW}|${screen.itm8Field}|^0*$|${PAGE_TIMEOUT_MS}`,
    )
  })
  out.push(`WINMIN|${PROGRAM_WINDOW}`)
  return out.join('\r\n') + '\r\n'
}

/** Przecena: karta artykułu (ar-e001) i taryfy sprzedaży (ar-e003). */
const MARKDOWN_SCREEN = {
  articleTab: 'mnTp_ARTICLE',
  articleMenuItem: 'mnTv_ARTICLE_ar-e001_FicheArticle',
  eanField: 'txtZ4_CodeEAN_TxtBox',
  itm8Field: 'txtZ1_CodeITM_TxtBox',
  /** „Taryfy Sprzed.”: link pojawia się dopiero po wczytaniu artykułu. */
  salesTariffsButton: 'BanniereTarifVente_LnkAviso',
  dateFromField: 'txdZ3_DateDebut_TxtBox',
  promoCheckbox: 'chkZ4_Promo_CheckBox1',
  dateToField: 'txdZ11_DateFin_TxtBox',
  priceField: 'txmZ20_PdvPrixVente_TxtBox',
  /** Kod stawki VAT ({@link mercalysVatCode}: 1 = 23%, 2 = 8%, 6 = 5%). */
  vatField: 'lstZ5_TVA_TxtBox',
  approveButton: 'btnB4_Valider_LnkBouton',
  /** Tak przy „Utworzyć zapis ?” i przy pytaniu o zachowanie wpisu, gdy taryfa z tą datą już jest; inne pytanie zatrzymuje makro. */
  confirmText: 'Utworzy;zachowa',
  backButton: 'btnB1_Retour_LnkBouton',
  /** „ZER.” na karcie artykułu; na taryfach tego przycisku nie ma, więc mówi też, że powrót się udał. */
  resetButton: 'btnB2_RAZ_LnkBouton',
  /**
   * „Sprzedaż ze stratą! Minimalna cena sprzedaży wynosi … Czy potwierdzasz sprzedaż ze stratą?”: Mercalys pyta po wpisaniu
   * ceny poniżej minimalnej. Przecenę ze stratą pracownik wybrał świadomie (okno pokazuje marżę po przecenie), więc Tak.
   */
  lossText: 'Minimalna cena;ze strat',
} as const

/** Pytanie o stratę pojawia się po wyjściu z pola ceny; przy cenie powyżej minimalnej go nie ma. */
const LOSS_DIALOG_TIMEOUT_MS = 3000

/** Po potwierdzeniu strona się przeładowuje, a klik w starą stronę by przepadł. */
const AFTER_CONFIRM_SLEEP_MS = 1500
/** Zaznaczenie promocji może przeładować stronę; to, co zniknie, wyłapie sprawdzenie przed Zatwierdź. */
const AFTER_PROMO_SLEEP_MS = 500

export type MarkdownMacro = {
  ean: string
  itm8: string
  price: number
  /** RRRR-MM-DD */
  from: string
  to: string
}

const dateParts = (iso: string) => {
  const [year, month, day] = iso.split('-')
  return { day, month, year: year.slice(2) }
}

/** Mercalys przyjmuje daty jako DDMMRR. */
export const mercalysDate = (iso: string) => {
  const { day, month, year } = dateParts(iso)
  return `${day}${month}${year}`
}

/** Po wyjściu z pola Mercalys może dopisać ukośniki i pełny rok. */
const datePattern = (iso: string) => {
  const { day, month, year } = dateParts(iso)
  return `^${day}/?${month}/?(20)?${year}$`
}

const withoutLeadingZeros = (code: string) => code.replace(/^0+/, '')

/**
 * Taryfa sprzedaży jednego artykułu, od otwartej karty artykułu (ar-e001) z powrotem na pustą kartę: EAN, sprawdzenie
 * ITM8, Taryfy Sprzed., data początku, przy przecenie Promo i data końca, przy podanym `vatCode` stawka VAT, cena,
 * sprawdzenie pól, Zatwierdź, Tak, Powrót, ZER. Wspólne dla przeceny i ceny zwykłej artykułu w promocji.
 */
function salesTariffLines({
  ean,
  itm8,
  price,
  from,
  to,
  vatCode,
}: {
  ean: string
  itm8: string
  price: number
  from: string
  /** Koniec promocji; bez niego zwykła taryfa (bez Promo). */
  to?: string
  vatCode?: string
}) {
  const screen = MARKDOWN_SCREEN
  const priceText = price.toFixed(2).replace('.', ',')
  return [
    `WEBWAIT|${WINDOW}|${screen.eanField}||${PAGE_TIMEOUT_MS}`,
    `WEBKEY|${WINDOW}|${screen.eanField}|^a${ean}{Enter}`,
    `WEBVALUE|${WINDOW}|${screen.itm8Field}|^0*${withoutLeadingZeros(itm8)}$|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${WINDOW}|${screen.salesTariffsButton}`,
    `WEBWAIT|${WINDOW}|${screen.dateFromField}||${PAGE_TIMEOUT_MS}`,
    `WEBKEY|${WINDOW}|${screen.dateFromField}|^a${mercalysDate(from)}{Tab}`,
    ...(to
      ? [
          `WEBCHECK|${WINDOW}|${screen.promoCheckbox}`,
          `SLEEP|${AFTER_PROMO_SLEEP_MS}`,
          `WEBKEY|${WINDOW}|${screen.dateToField}|^a${mercalysDate(to)}{Tab}`,
        ]
      : []),
    ...(vatCode ? [`WEBKEY|${WINDOW}|${screen.vatField}|^a${vatCode}{Tab}`] : []),
    `WEBKEY|${WINDOW}|${screen.priceField}|^a${priceText}{Tab}`,
    `WEBDIALOG|${DIALOG}|${screen.lossText}|Oui|${LOSS_DIALOG_TIMEOUT_MS}`,
    `WEBVALUE|${WINDOW}|${screen.dateFromField}|${datePattern(from)}`,
    ...(to
      ? [
          `WEBVALUE|${WINDOW}|${screen.promoCheckbox}|^tak$`,
          `WEBVALUE|${WINDOW}|${screen.dateToField}|${datePattern(to)}`,
        ]
      : [`WEBVALUE|${WINDOW}|${screen.promoCheckbox}|^nie$`]),
    ...(vatCode ? [`WEBVALUE|${WINDOW}|${screen.vatField}|^${vatCode}$`] : []),
    `WEBVALUE|${WINDOW}|${screen.priceField}|^${priceText}$`,
    `WEBCLICK|${WINDOW}|${screen.approveButton}`,
    // Gdyby Mercalys zapytał o stratę dopiero przy zapisie; inne pytanie (Utworzyć zapis) WEBDIALOG zostawia od razu.
    `WEBDIALOG|${DIALOG}|${screen.lossText}|Oui|${LOSS_DIALOG_TIMEOUT_MS}`,
    `WEBWAIT|${DIALOG}|Oui|${screen.confirmText}|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `SLEEP|${AFTER_CONFIRM_SLEEP_MS}`,
    `WEBCLICK|${WINDOW}|${screen.backButton}`,
    `WEBWAIT|${WINDOW}|${screen.resetButton}||${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${WINDOW}|${screen.resetButton}`,
    `WEBVALUE|${WINDOW}|${screen.eanField}|^$|${PAGE_TIMEOUT_MS}`,
  ]
}

/** Otwarcie Mercalys na karcie artykułu. */
const articleCardOpenLines = () => [
  mercalysOpenLine(PROGRAM_WINDOW),
  `WEBENSURE|${WINDOW}|${MARKDOWN_SCREEN.articleMenuItem}|${MARKDOWN_SCREEN.articleTab}`,
  `WEBCLICK|${WINDOW}|${MARKDOWN_SCREEN.articleMenuItem}`,
]

/**
 * Makro przeceny: przywraca Mercalys z paska zadań, otwiera kartę artykułu po EAN-ie i taryfy sprzedaży, wpisuje okres
 * i cenę, zatwierdza, wraca, zeruje kartę i chowa program, więc pracownik wraca do przeglądarki. Inny artykuł na karcie
 * albo wartości, których formularz nie przyjął, zatrzymują makro przed zapisem.
 */
export function buildMarkdownMacro({ ean, itm8, price, from, to }: MarkdownMacro) {
  return (
    [
      `# Przecena ${ean}`,
      ...articleCardOpenLines(),
      ...salesTariffLines({ ean, itm8, price, from, to }),
      `WINMIN|${PROGRAM_WINDOW}`,
    ].join('\r\n') + '\r\n'
  )
}

/** Artykuł w trwającej promocji, któremu ustawiamy cenę zwykłą od dziś. */
export type PromoPriceMacroItem = { ean: string; itm8: string; price: number; vatCode: string }

/**
 * Cena zwykła artykułów w trwającej promocji (zmiana cen z pliku ich nie obejmuje): dla każdego artykułu taryfa jak przy
 * przecenie, ale bez Promo, od dziś, ze stawką VAT i ceną sugerowaną z tabeli; po zapisie Powrót, ZER. i kolejny EAN.
 */
export function buildPromoPriceMacro(items: PromoPriceMacroItem[], today: string) {
  const out = [`# Ceny zwykłe artykułów w promocji: ${items.length}`, ...articleCardOpenLines()]
  items.forEach((item, index) => {
    out.push(`# ${index + 1}/${items.length}: EAN ${item.ean}`, ...salesTariffLines({ ...item, from: today }))
  })
  out.push(`WINMIN|${PROGRAM_WINDOW}`)
  return out.join('\r\n') + '\r\n'
}

/** Wprowadzanie promocji: nagłówek operacji (ar-e072) i artykuły operacji (ar-e074). */
const PROMO_SCREEN = {
  tariffTab: 'mnTp_TARIF',
  menuItem: 'mnTv_TARIF_ar-e072_SaisieDesPromos',
  codeField: 'lstZ1_CodeOp_TxtBox',
  nameField: 'txtZ2_LibelleOp_TxtBox',
  typeField: 'lstZ3_TypeOp_TxtBox',
  type: '1',
  startField: 'txdZ6_DebutOp_TxtBox',
  endField: 'txdZ7_FinOp_TxtBox',
  approveButton: 'btnB6_Valider_LnkBouton',
  enterButton: 'btnB2_Saisir_LnkBouton',
  articleField: 'txtZ8_CodeArticle_TxtBox',
  saleEndField: 'txdZ12_FinVentePDV_TxtBox',
  priceField: 'txpZ25_PrixPromoPDV_TxtBox',
  createText: 'Utworzy',
  /** Kod istniejącej operacji wypełnia panel: wtedy Usuń, Tak i kod od nowa. */
  deleteButton: 'btnB3_Supprimer_LnkBouton',
  /** „Operacja … posiada N zakończonych promocji. Chcesz nadal ją usunąć ?” */
  deleteText: 'usun',
  /** Etykieta przeskoku, gdy kod jest wolny. */
  freeCodeLabel: 'KOD_WOLNY',
  /** „Sprzedaż ze stratą. Cena sprzedaży minimum jest … Potwierdzasz Sprzedaż ze stratą ?”: tylko czasem, Tak. */
  lossText: 'ze strat',
  backButton: 'btnB1_Retour_LnkBouton',
  /**
   * „Wprowadzona stawka podatkowa nie jest dozwolona dla wprowadzonego kodu CN.”: tylko czasem, po EAN-ie. Okno ma
   * sam odnośnik „Fermer” (bez id), a pole VAT zostaje z 0, więc makro wpisuje kod stawki artykułu.
   */
  // Też „Przed kontynuacją popraw kartę artykułu. Aby zobaczyć dozwoloną stawkę VAT wprowadź kod CN” (skan
  // 26.11.2026) i „Kody CN, GTU i Split Payment są wymagane”.
  cnText: 'kod CN;kodu CN;Kody CN;dozwolon;stawk;GTU',
  cnCloseButton: 'Fermer',
  /** Kod stawki jak w kartotece Mercalys: 1 = 23%, 2 = 8%, 6 = 5%. */
  vatField: 'lstZ22_TVA_TxtBox',
} as const

/** Kod stawki VAT w Mercalys z procentu ({@code products.vat_code}: 1 = 23%, 2 = 8%, 6 = 5%); inny procent = brak. */
export function mercalysVatCode(percent: number | null): string | null {
  if (percent === 23) return '1'
  if (percent === 8) return '2'
  if (percent === 5) return '6'
  return null
}

/** Czasy makra promocji w ms, poprawiamy je tylko tutaj. */
export const PROMO_MACRO_CONFIG = {
  /** Po EAN-ie program wczytuje artykuł. */
  afterEan: 800,
  /** Po „Utworzyć zapis ?” strona się przeładowuje przed kolejnym artykułem. */
  afterSave: AFTER_CONFIRM_SLEEP_MS,
  /** Tyle makro czeka po kodzie, czy Mercalys wypełni panel istniejącej operacji (nazwa w polu). */
  existingCheck: 3000,
  /** Po przeładowaniu strony artykułów, przed Powrót. */
  beforeBack: 500,
  /** Tyle makro czeka po EAN-ie na okno o stawce VAT niezgodnej z kodem CN. */
  cnDialog: 1000,
  /** Tyle makro czeka po EAN-ie na kod stawki w polu VAT; zwykle jest od razu, a puste albo 0 = okno o kodzie CN. */
  vatLoad: 2000,
} as const

/** {@code vatCode}: kod stawki w Mercalys ({@link mercalysVatCode}), wpisywany, gdy Mercalys wyzeruje VAT. */
export type PromoMacroItem = { ean: string; price: number; dateTo: string; vatCode: string | null }

/**
 * Wprowadzanie promocji: Taryfa → Wprowadzanie promocji, kod i nazwa operacji, typ 1, koniec operacji = najpóźniejszy
 * koniec artykułów, Zatwierdź, Tak, Wpisz; potem dla każdego artykułu EAN, koniec sprzedaży, cena promocji z Enterem,
 * Tak przy sprzedaży ze stratą (gdy Mercalys zapyta) i Tak przy „Utworzyć zapis ?”. Na końcu Powrót i Mercalys na
 * pasek zadań. Początek operacji makro wpisuje, gdy jest podany ({@code start}); bez niego zostaje dzisiejszy
 * podpowiedziany przez Mercalys. Kod istniejącej operacji wypełnia
 * panel: makro ją usuwa (Usuń, Tak) i wpisuje kod od nowa. Gdy po EAN-ie Mercalys zgłosi stawkę VAT niezgodną z kodem
 * CN (pole VAT z 0), makro zamyka okno i wpisuje kod stawki artykułu. Wymaga odtwarzacza od wersji 50 (WEBDIALOG po
 * tekście przycisku).
 */
export function buildPromoEntryMacro({
  code,
  name,
  start,
  items,
}: {
  code: string
  name: string
  start?: string
  items: PromoMacroItem[]
}) {
  const screen = PROMO_SCREEN
  const end = items.reduce((latest, item) => (item.dateTo > latest ? item.dateTo : latest), '')
  const out = [
    `# Wprowadzanie promocji ${code}: ${items.length} art.${start ? `, od ${start}` : ''}`,
    mercalysOpenLine(PROGRAM_WINDOW),
    ...promoHeaderLines({ code, name, start, end, label: screen.freeCodeLabel }),
    ...promoArticleLines(items, 0),
    ...promoEndLines(),
  ]
  return out.join('\r\n') + '\r\n'
}

/**
 * Nagłówek operacji promocji (ar-e072) aż do ekranu artykułów (ar-e074): Taryfa → Wprowadzanie promocji, kod (istniejąca
 * operacja z tym kodem jest usuwana), nazwa, typ 1, początek (gdy podany; bez niego zostaje podpowiedziany dzisiejszy),
 * koniec, Zatwierdź, Tak, Wpisz. {@code label}: etykieta przeskoku, inna dla każdej operacji w jednym makrze.
 */
function promoHeaderLines({
  code,
  name,
  start,
  end,
  label,
}: {
  code: string
  name: string
  start?: string
  end: string
  label: string
}) {
  const screen = PROMO_SCREEN
  const c = PROMO_MACRO_CONFIG
  return [
    `WEBENSURE|${WINDOW}|${screen.menuItem}|${screen.tariffTab}`,
    `WEBCLICK|${WINDOW}|${screen.menuItem}`,
    `WEBWAIT|${WINDOW}|${screen.codeField}||${PAGE_TIMEOUT_MS}`,
    `WEBKEY|${WINDOW}|${screen.codeField}|^a${code}{Tab}`,
    // Operacja z tym kodem już jest (panel wypełniony, np. PROMO GNIEZNO): Usuń, Tak, kod od nowa.
    `WEBIFVALUE|${WINDOW}|${screen.nameField}|\\S|${label}|${c.existingCheck}`,
    `WEBCLICK|${WINDOW}|${screen.deleteButton}`,
    `WEBWAIT|${DIALOG}|Oui|${screen.deleteText}|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `SLEEP|${c.afterSave}`,
    `WEBWAIT|${WINDOW}|${screen.codeField}||${PAGE_TIMEOUT_MS}`,
    `WEBKEY|${WINDOW}|${screen.codeField}|^a${code}{Tab}`,
    `WEBVALUE|${WINDOW}|${screen.nameField}|^$|${c.existingCheck}`,
    `LABEL|${label}`,
    `WEBKEY|${WINDOW}|${screen.nameField}|^a${name}{Tab}`,
    `WEBKEY|${WINDOW}|${screen.typeField}|^a${screen.type}{Tab}`,
    ...(start ? [`WEBKEY|${WINDOW}|${screen.startField}|^a${mercalysDate(start)}{Tab}`] : []),
    `WEBKEY|${WINDOW}|${screen.endField}|^a${mercalysDate(end)}{Tab}`,
    `WEBVALUE|${WINDOW}|${screen.codeField}|^${code}$`,
    `WEBVALUE|${WINDOW}|${screen.typeField}|^${screen.type}$`,
    ...(start ? [`WEBVALUE|${WINDOW}|${screen.startField}|${datePattern(start)}`] : []),
    `WEBVALUE|${WINDOW}|${screen.endField}|${datePattern(end)}`,
    `WEBCLICK|${WINDOW}|${screen.approveButton}`,
    `WEBWAIT|${DIALOG}|Oui|${screen.createText}|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `SLEEP|${c.afterSave}`,
    `WEBWAIT|${WINDOW}|${screen.enterButton}||${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${WINDOW}|${screen.enterButton}`,
  ]
}

/** Import pliku cen promocji na ekranie artykułów operacji (ar-e074) i poprawa błędów importu (ar-e078). */
const PRO_IMPORT_SCREEN = {
  importButton: 'btnB5_Import_LnkBouton',
  /** Okno komunikatu bez tytułu strony: „… — okno dialogowe strony sieci Web”. */
  alertWindow: 'okno dialogowe strony',
  /** „Niektóre przesłane artykuły sa w stanie błędu. Pamiętaj przeprowadzic ich kontrolę.” */
  errorsText: 'w stanie b',
  alertClose: 'Fermer',
  /** „Błąd” na dolnym pasku ekranu artykułów: lista błędów importu. */
  errorsButton: 'btnB8_Anomalie_LnkBouton',
  /** „Popraw błąd” na liście błędów: po nim wiemy, że lista jest na ekranie. */
  errorsScreen: 'btnB2_TraiterAnomalie_LnkBouton',
  /** Rodzaj błędu, który makro poprawia: „Promocja z marżą ujemną”. */
  negativeMarginText: 'ujemn',
  /** Panel poprawy błędu (ar-e078). */
  fixScreen: 'txnZ11_MargeAAtteindre_TxtBox',
  /** Wiersz panelu, który poprawia od razu wszystkie ujemne marże operacji. */
  integrateAllText: 'wszystkie ujemne',
  fixApprove: 'btnB2_Valider_LnkBouton',
  /** „Poprawić błąd ?” */
  fixText: 'Poprawi',
} as const

/** Czasy makra importu cen promocji w ms, poprawiamy je tylko tutaj. */
export const PRO_IMPORT_MACRO_CONFIG = {
  /** Na okno wyboru pliku po Import. */
  fileDialog: 30000,
  /** Na raport PDF po wczytaniu pliku: duży plik wczytuje się długo. */
  pdf: 180000,
  /** Komunikat o artykułach z błędem pojawia się chwilę po PDF-ie. */
  errorsAlert: 3000,
  /** Tyle makro szuka na liście kolejnego błędu „marża ujemna”, zanim uzna, że go nie ma. */
  errorRow: 3000,
  /** Ile razy makro wraca do listy błędów; jedna poprawa obejmuje wszystkie ujemne marże, reszta to zapas. */
  fixRounds: 3,
  /** Po zaznaczeniu wiersza, zanim Zatwierdź. */
  afterPick: 500,
  /** Przed Powrót na świeżo przeładowanej stronie. */
  beforeBack: 500,
} as const

/** Plik cen promocji do wczytania: kod operacji (kod taryfy), nazwa, okres i pełna ścieżka pliku na dysku sklepu. */
export type ProImportFile = { code: string; name: string; dateFrom: string; dateTo: string; path: string }

/**
 * Ceny promocji z plików do Mercalys: dla każdego pliku nagłówek operacji jak przy wprowadzaniu promocji (kod taryfy,
 * nazwa, typ 1, początek i koniec z okresu pliku; początek przed dziś = dziś), Wpisz, Import i wybór pliku w oknie Windows,
 * zamknięcie raportu PDF. Gdy Mercalys zgłosi artykuły z błędem: Zamknij, Błąd, podwójny klik w błąd „marża ujemna”,
 * na panelu poprawy wiersz „wszystkie ujemne marże”, Zatwierdź, Tak; z pustą listą Powrót do artykułów. Na końcu
 * Powrót do nagłówka i kolejny plik, a po ostatnim Mercalys na pasek zadań. Inne rodzaje błędów zostają na liście
 * błędów operacji. Wymaga odtwarzacza od wersji 0.52 (FILEOPEN, PDFCLOSE, IFDIALOG, WEBIFDBLROW, WEBTEXTCLICK).
 */
export function buildProImportMacro(files: ProImportFile[], today: string) {
  const screen = PROMO_SCREEN
  const pro = PRO_IMPORT_SCREEN
  const c = PRO_IMPORT_MACRO_CONFIG
  const out = [`# Ceny promocji z plików: ${files.length}`, mercalysOpenLine(PROGRAM_WINDOW)]
  files.forEach((file, index) => {
    const n = index + 1
    const fixedLabel = `PRO_FIXED_${n}`
    const doneLabel = `PRO_DONE_${n}`
    out.push(
      `# Plik ${n}/${files.length}: ${file.path}, kod ${file.code}, ${file.dateFrom} - ${file.dateTo}`,
      ...promoHeaderLines({
        code: file.code,
        name: file.name.slice(0, 30),
        start: file.dateFrom < today ? today : file.dateFrom,
        end: file.dateTo,
        label: `PRO_FREE_${n}`,
      }),
      `WEBWAIT|${WINDOW}|${pro.importButton}||${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${pro.importButton}`,
      `FILEOPEN|${file.path}|${c.fileDialog}`,
      `PDFCLOSE|${c.pdf}`,
      // Bez komunikatu o błędach od razu Powrót do nagłówka.
      `IFDIALOG|${pro.alertWindow}|${pro.errorsText}|${pro.alertClose}|${doneLabel}|${c.errorsAlert}`,
      `SLEEP|${AFTER_CONFIRM_SLEEP_MS}`,
      `WEBWAIT|${WINDOW}|${pro.errorsButton}||${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${pro.errorsButton}`,
      `WEBWAIT|${WINDOW}|${pro.errorsScreen}||${PAGE_TIMEOUT_MS}`,
    )
    for (let round = 1; round <= c.fixRounds; round += 1) {
      out.push(
        `# Błąd „marża ujemna”, podejście ${round}`,
        `WEBIFDBLROW|${WINDOW}|*|${pro.negativeMarginText}|${fixedLabel}|${c.errorRow}`,
        `WEBWAIT|${WINDOW}|${pro.fixScreen}||${PAGE_TIMEOUT_MS}`,
        `WEBTEXTCLICK|${WINDOW}|${pro.integrateAllText}|${PAGE_TIMEOUT_MS}`,
        `SLEEP|${c.afterPick}`,
        `WEBCLICK|${WINDOW}|${pro.fixApprove}`,
        `WEBWAIT|${DIALOG}|Oui|${pro.fixText}|${PAGE_TIMEOUT_MS}`,
        `WEBCLICK|${DIALOG}|Oui`,
        `SLEEP|${AFTER_CONFIRM_SLEEP_MS}`,
        `WEBWAIT|${WINDOW}|${pro.errorsScreen}||${PAGE_TIMEOUT_MS}`,
      )
    }
    out.push(
      `LABEL|${fixedLabel}`,
      // Lista błędów → artykuły operacji.
      `SLEEP|${c.beforeBack}`,
      `WEBCLICK|${WINDOW}|${screen.backButton}`,
      `LABEL|${doneLabel}`,
      // Artykuły operacji → nagłówek.
      `WEBWAIT|${WINDOW}|${pro.importButton}||${PAGE_TIMEOUT_MS}`,
      `SLEEP|${c.beforeBack}`,
      `WEBCLICK|${WINDOW}|${screen.backButton}`,
      `WEBWAIT|${WINDOW}|${screen.codeField}||${PAGE_TIMEOUT_MS}`,
    )
  })
  out.push(`WINMIN|${PROGRAM_WINDOW}`)
  return out.join('\r\n') + '\r\n'
}

/**
 * Wznowienie makra promocji po błędzie: Mercalys stoi na wpisywaniu artykułów tej operacji, więc makro nie zakłada jej
 * od nowa, tylko wraca do programu i wpisuje artykuły od {@code from} (numer od 0, jak w pełnym makrze).
 */
export function buildPromoResumeMacro({ code, items, from }: { code: string; items: PromoMacroItem[]; from: number }) {
  const out = [
    `# Wznowienie promocji ${code}: od artykułu ${from + 1} z ${items.length}`,
    mercalysOpenLine(PROGRAM_WINDOW),
    ...promoArticleLines(items, from),
    ...promoEndLines(),
  ]
  return out.join('\r\n') + '\r\n'
}

function promoArticleLines(items: PromoMacroItem[], from: number) {
  const screen = PROMO_SCREEN
  const c = PROMO_MACRO_CONFIG
  const out: string[] = []
  items.forEach((item, index) => {
    if (index < from) return
    const price = item.price.toFixed(2).replace('.', ',')
    const vatLabel = `VAT_OK_${index + 1}`
    const fixLabel = `VAT_FIX_${index + 1}`
    out.push(
      `# Artykuł ${index + 1}/${items.length}, EAN ${item.ean}: ${price} do ${item.dateTo}`,
      `WEBWAIT|${WINDOW}|${screen.articleField}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.articleField}|^a${item.ean}{Enter}`,
      `SLEEP|${c.afterEan}`,
      // Wczytany artykuł ma kod stawki w polu VAT: od razu dalej, bez czekania na okno, które przychodzi rzadko. Pole
      // puste albo z 0 = komunikat o kodzie CN: Fermer i kod stawki artykułu; bez znanej stawki makro staje na
      // sprawdzeniu pola, zamiast zapisać promocję z VAT 0. Skok bez warunku: WEBIFVALUE na polu, którego nie ma.
      `WEBIFVALUE|${WINDOW}|${screen.vatField}|^[1-9]$|${fixLabel}|${c.vatLoad}`,
      `WEBIFVALUE|${WINDOW}|brak-pola|^$|${vatLabel}|0`,
      `LABEL|${fixLabel}`,
      `WEBDIALOG|${DIALOG}|${screen.cnText}|${screen.cnCloseButton}|${c.cnDialog}`,
      ...(item.vatCode
        ? [`WEBKEY|${WINDOW}|${screen.vatField}|^a${item.vatCode}{Tab}`]
        : [`# Brak stawki VAT artykułu w kartotece`]),
      `WEBVALUE|${WINDOW}|${screen.vatField}|^[1-9]$`,
      `LABEL|${vatLabel}`,
      // Komunikat przy stawce w polu: sprawdzenie bez czekania.
      `WEBDIALOG|${DIALOG}|${screen.cnText}|${screen.cnCloseButton}|0`,
      `WEBKEY|${WINDOW}|${screen.saleEndField}|^a${mercalysDate(item.dateTo)}{Tab}`,
      `WEBVALUE|${WINDOW}|${screen.saleEndField}|${datePattern(item.dateTo)}`,
      `WEBKEY|${WINDOW}|${screen.priceField}|^a${price}{Enter}`,
      `WEBDIALOG|${DIALOG}|${screen.lossText}|Oui|${LOSS_DIALOG_TIMEOUT_MS}`,
      `WEBWAIT|${DIALOG}|Oui|${screen.createText}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${DIALOG}|Oui`,
      `SLEEP|${c.afterSave}`,
    )
  })
  return out
}

function promoEndLines() {
  const screen = PROMO_SCREEN
  return [
    // Po ostatnim zapisie strona artykułów się przeładowuje: klik w starą stronę by przepadł, a schowany program
    // wracał na wierzch po przeładowaniu. Powrót dopiero na nowej stronie, chowanie po wejściu na nagłówek operacji.
    `WEBWAIT|${WINDOW}|${screen.articleField}||${PAGE_TIMEOUT_MS}`,
    `SLEEP|${PROMO_MACRO_CONFIG.beforeBack}`,
    `WEBCLICK|${WINDOW}|${screen.backButton}`,
    `WEBWAIT|${WINDOW}|${screen.codeField}||${PAGE_TIMEOUT_MS}`,
    `WINMIN|${PROGRAM_WINDOW}`,
  ]
}

/** Obsługa → Dostawcy (ac-e001): karta dostawcy, a po Excel wybór zakresu kodów do listy. */
const SUPPLIER_SCREEN = {
  maintenanceTab: 'mnTp_MAINTENANCE',
  menuItem: 'mnTv_MAINTENANCE_ac-e001_Fournisseurs',
  codeField: 'lstZ01_Code_TxtBox',
  originField: 'lstZ02_Origine_TxtBox',
  origin: '2',
  keywordField: 'txtZ05_MotDirecteur_TxtBox',
  /**
   * Wymagany: bez niego Zatwierdź daje „Uwaga! Musisz wpisać kod NIP”. Na karcie nowego dostawcy (po kodzie) to zwykłe
   * pole tekstowe; `lstZ43_CodeNIP_TbxNip_TxtBox` jest tylko na pustej karcie z kodem 0.
   */
  nipField: 'txtZ43_CodeNIP_TxtBox',
  name1Field: 'txtZ03_RaisonSociale1_TxtBox',
  name2Field: 'txtZ04_RaisonSociale2_TxtBox',
  streetField: 'txtZ06_Adresse1_TxtBox',
  postalCodeField: 'txtZ08_CodePostal_TxtBox',
  officeField: 'txtZ13_BureauDistr_TxtBox',
  phonePrefixField: 'txtZ10_TelephonePrefixe_TxtBox',
  phoneNumberField: 'txtZ10_TelephoneNumero_TxtBox',
  /** Telefon jest wymagany, a w aplikacji go nie mamy: 11 w obu polach (decyzja sklepu). */
  phone: '11',
  /** „Aktu. stanów zamknięcie dostawy”. */
  stockOnCloseCheckbox: 'chkZ48_MajStockClotureLiv_CheckBox1',
  /** „Pełna realiz. zamów.” (ExceptionLivraison); „Zam. uzupeł.” obok to Reliquats. Ustalone z kolejności pól na ekranie. */
  fullOrderCheckbox: 'chkZ17_ExceptionLivraison_CheckBox1',
  approveButton: 'btnB9_Valider_LnkBouton',
  createText: 'Utworzy',
  excelButton: 'btnB10_Excel_LnkBouton',
  /** Ekran Excel: zakres kodów, domyślnie cała lista. */
  excelFromField: 'lstZ1_FrsMin_TxtBox',
  excelApproveButton: 'btnB2_Valider_LnkBouton',
} as const

/** Tyle makro czeka po kodzie, czy Mercalys wczyta kartę istniejącego dostawcy (nazwa w polu). */
const SUPPLIER_EXISTING_CHECK_MS = 3000

export type SupplierMacro = {
  code: string
  keyword: string
  nip: string
  name1: string
  name2: string
  street: string
  postalCode: string
  city: string
}

/**
 * Tekst do WEBKEY: odtwarzacz naciska prawdziwe klawisze, więc znaki specjalne AutoHotkey idą w klamrach, a `|` (separator
 * linii makra) i znaki nowej linii wypadają.
 */
const keysText = (value: string) => value.replace(/[|\r\n]/g, ' ').replace(/[+^!#{}]/g, '{$&}')

/** Tyle kolejnych kodów makro próbuje, gdy proponowany jest w Mercalys zajęty. */
export const SUPPLIER_CODE_TRIES = 10

/**
 * Nowy dostawca: Obsługa → Dostawcy, kod (zajęty, czyli z wczytaną kartą: kolejny, najwyżej {@link SUPPLIER_CODE_TRIES}
 * prób, potem makro staje), Pochodz. 2, hasło, NIP, nazwa, adres, kod pocztowy, biuro dystr., telefon 11 i 11, pola Aktu.
 * stanów zamknięcie dostawy i Pełna realiz. zamów., Zatwierdź, Tak, potem Excel z całą listą dostawców i Mercalys na
 * pasek zadań. Wymaga odtwarzacza od wersji 40 (WEBFREECODE).
 */
export function buildSupplierMacro(supplier: SupplierMacro) {
  const s = SUPPLIER_SCREEN
  const key = (field: string, value: string, end = '{Tab}') => `WEBKEY|${WINDOW}|${field}|^a${keysText(value)}${end}`
  return (
    [
      `# Nowy dostawca ${supplier.code} (albo kolejny wolny): ${keysText(supplier.name1)}`,
      mercalysOpenLine(PROGRAM_WINDOW),
      `WEBENSURE|${WINDOW}|${s.menuItem}|${s.maintenanceTab}`,
      // Kod zajęty (lista dostawców w aplikacji jest nieaktualna): Mercalys wczytuje kartę istniejącego dostawcy, więc
      // odtwarzacz bierze kolejny kod, każdy na świeżej karcie. Wszystkie zajęte zatrzymują makro, nic nie jest nadpisane.
      `WEBFREECODE|${WINDOW}|${s.menuItem}|${s.codeField}|${s.name1Field}|${supplier.code}|${SUPPLIER_CODE_TRIES}|${SUPPLIER_EXISTING_CHECK_MS}`,
      key(s.originField, s.origin),
      key(s.keywordField, supplier.keyword),
      key(s.nipField, supplier.nip),
      key(s.name1Field, supplier.name1),
      ...(supplier.name2 ? [key(s.name2Field, supplier.name2)] : []),
      key(s.streetField, supplier.street),
      key(s.postalCodeField, supplier.postalCode),
      key(s.officeField, supplier.city),
      key(s.phonePrefixField, s.phone, '{Enter}'),
      key(s.phoneNumberField, s.phone, '{Enter}'),
      `WEBCHECK|${WINDOW}|${s.stockOnCloseCheckbox}`,
      `WEBCHECK|${WINDOW}|${s.fullOrderCheckbox}`,
      // Bez `|` w wyrażeniu: odtwarzacz dzieli po nim linię. Kod sprawdził już WEBFREECODE.
      `WEBVALUE|${WINDOW}|${s.codeField}|^0*[1-9][0-9]*$`,
      `WEBVALUE|${WINDOW}|${s.originField}|^${s.origin}$`,
      // Mercalys może dopisać myślniki albo spacje między cyframi.
      `WEBVALUE|${WINDOW}|${s.nipField}|^${supplier.nip.split('').join('[- ]?')}$`,
      `WEBVALUE|${WINDOW}|${s.stockOnCloseCheckbox}|^tak$`,
      `WEBVALUE|${WINDOW}|${s.fullOrderCheckbox}|^tak$`,
      `WEBCLICK|${WINDOW}|${s.approveButton}`,
      `WEBWAIT|${DIALOG}|Oui|${s.createText}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${DIALOG}|Oui`,
      `SLEEP|${AFTER_CONFIRM_SLEEP_MS}`,
      `WEBWAIT|${WINDOW}|${s.excelButton}||${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${s.excelButton}`,
      `WEBWAIT|${WINDOW}|${s.excelFromField}||${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${s.excelApproveButton}`,
      `SLEEP|${AFTER_CONFIRM_SLEEP_MS}`,
      `WINMIN|${PROGRAM_WINDOW}`,
    ].join('\r\n') + '\r\n'
  )
}

/** Korekta od dostawcy: protokół (ve-e040) i jego szczegół (ve-e046). */
const CORRECTION_SCREEN = {
  stockTab: 'mnTp_STOCK',
  protocolMenuItem: 'mnTv_VENTE_ve-e040_GestionProtocole',
  typeField: 'lstZ2_TypeProtocole_TxtBox',
  /** 8 = korekta dostawy, błąd dostawcy. */
  supplierErrorType: '8',
  /** 9 = korekta dostawy z bazy (bon dostawcy 538 albo 88110; refaktura 16538 to typ 8). */
  baseErrorType: '9',
  dueDateField: 'txdZ6_DateEcheance_TxtBox',
  approveButton: 'btnB4_Valider_LnkBouton',
  createText: 'Utworzy',
  detailButton: 'btnB3_Detail_LnkBouton',
  bonField: 'txtZ19_NumBL_TxtBox',
  /** Kod artykułu: ITM8 albo EAN (EAN z kilkoma ITM8 otwiera okno wyboru, więc makro wpisuje ITM8 z bonu). */
  eanField: 'txtZ6_CodeArticle_TxtBox',
  /** Pusty wiersz ma `0,000`; po EAN-ie Mercalys wpisuje ilość z bonu (np. `4,000`). */
  quantityField: 'txnZ9_Quantite_TxtBox',
  /** Pusty wiersz ma `0,00`; po EAN-ie cena zakupu z bonu. */
  priceField: 'txpZ16_PAUnitaireHT_TxtBox',
  closeButton: 'btnB3_Cloturer_LnkBouton',
  closeText: 'zatwierdzi',
  /**
   * „Sprzedaż ze stratą ! Minimalna cena sprzedaży jest … Czy potwierdzasz stratną sprzedaż ?”: tylko czasem, zaraz po
   * zapisie ceny zakupu. Samo okno pytania w tytule: w głównym oknie brak przycisku oznacza wolny przegląd strony.
   */
  lossWindow: 'okno dialogowe strony',
  lossText: 'Minimalna cena;ze strat',
} as const

/** Pierwsze okno po Zakończ znika, a drugie pojawia się razem z PDF-em. */
const BETWEEN_CLOSE_DIALOGS_MS = 2000

/** Nowy protokół z dzisiejszym terminem płatności; po zapisie szczegół otwiera się sam albo przyciskiem. */
export function correctionStartLines(today: string, baseProtocol = false) {
  const screen = CORRECTION_SCREEN
  const type = baseProtocol ? screen.baseErrorType : screen.supplierErrorType
  return [
    mercalysOpenLine(PROGRAM_WINDOW),
    `WEBENSURE|${WINDOW}|${screen.protocolMenuItem}|${screen.stockTab}`,
    `WEBCLICK|${WINDOW}|${screen.protocolMenuItem}`,
    `WEBWAIT|${WINDOW}|${screen.typeField}||${PAGE_TIMEOUT_MS}`,
    `WEBKEY|${WINDOW}|${screen.typeField}|^a${type}{Enter}`,
    `SLEEP|${AFTER_CONFIRM_SLEEP_MS}`,
    `WEBKEY|${WINDOW}|${screen.dueDateField}|^a${mercalysDate(today)}{Tab}`,
    `WEBVALUE|${WINDOW}|${screen.typeField}|^0*${type}$`,
    `WEBVALUE|${WINDOW}|${screen.dueDateField}|${datePattern(today)}`,
    `WEBCLICK|${WINDOW}|${screen.approveButton}`,
    `WEBWAIT|${DIALOG}|Oui|${screen.createText}|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `SLEEP|${AFTER_CONFIRM_SLEEP_MS}`,
    `WEBENSURE|${WINDOW}|${screen.bonField}|${screen.detailButton}`,
  ]
}

/** Numer bonu pozycji: zawsze w swoim polu, więc klawisze nie trafią w inne okno ani pole. */
export const correctionBonLine = (bonNumber: string) =>
  `WEBKEY|${WINDOW}|${CORRECTION_SCREEN.bonField}|^a${bonNumber}{Enter}`

/**
 * Wzorzec liczby z Mercalys: pole po wyjściu dopisuje zera (4 → 4,000, 1,5 → 1,500), a tysiące bywają rozdzielone spacją.
 */
const numberPattern = (value: number, digits: number) => {
  const [whole, fraction = ''] = value.toFixed(digits).split('.')
  const sign = whole.startsWith('-') ? '-' : ''
  const integer = whole.replace('-', '').split('').join('[ \\x{A0}]?')
  const decimals = fraction.replace(/0+$/, '')
  return decimals === ''
    ? `^${sign}${integer}(,0{0,${digits}})?$`
    : `^${sign}${integer},${decimals}0{0,${digits - decimals.length}}$`
}

/** Wartość różna od zera (0,000, 0,00 i puste pole to wiersz bez wczytanego artykułu). */
const NON_ZERO = '^(?!-?0*(,0*)?$)-?[0-9 \\x{A0}]+(,[0-9]+)?$'
const ZERO_OR_EMPTY = '^0*(,0*)?$'

export type CorrectionArticleStep = {
  /** ITM8 z bonu (13 cyfr), a bez niego EAN: pole kodu artykułu przyjmuje oba. */
  code: string
  quantity: number
  /** Ilość wpisana do pola, np. `1,5`. */
  quantityText: string
  priceText: string
  /**
   * Czekać po EAN-ie na cenę z bonu. Nie na ilość: pozycja wyzerowana wcześniejszą korektą ma po wczytaniu dalej 0.
   * `false`, gdy artykułu nie ma na bonie albo ma tam cenę 0 (wtedy zwykła przerwa).
   */
  waitForPrice: boolean
}

/**
 * Pozycja korekty po numerze bonu. Najpierw pusty wiersz (cena 0,00), potem EAN w swoim polu i czekanie, aż Mercalys
 * wczyta artykuł z bonu (cena różna od zera): przy lagu klawisze nie trafią już w złe pola. Ilość i cena idą w swoje
 * pola i są sprawdzane przed zapisem pozycji Enterem w polu ceny.
 */
export function correctionArticleLines(
  step: CorrectionArticleStep,
  timing: { load: number; afterEan: number; afterQuantity: number },
) {
  const screen = CORRECTION_SCREEN
  const out = [
    `WEBVALUE|${WINDOW}|${screen.priceField}|${ZERO_OR_EMPTY}|${timing.load}`,
    `WEBKEY|${WINDOW}|${screen.eanField}|^a${step.code}{Enter}`,
    step.waitForPrice
      ? `WEBVALUE|${WINDOW}|${screen.priceField}|${NON_ZERO}|${timing.load}`
      : `SLEEP|${timing.afterEan}`,
  ]
  out.push(
    `WEBKEY|${WINDOW}|${screen.quantityField}|^a${step.quantityText}{Enter}`,
    `SLEEP|${timing.afterQuantity}`,
    `WEBVALUE|${WINDOW}|${screen.quantityField}|${numberPattern(step.quantity, 3)}|${timing.load}`,
    `WEBKEY|${WINDOW}|${screen.priceField}|^a${step.priceText}`,
    `WEBVALUE|${WINDOW}|${screen.priceField}|^${step.priceText}$|${timing.load}`,
    `KEY|{Enter}`,
  )
  return out
}

/** Pytanie o sprzedaż ze stratą po zapisie pozycji = Tak; bez pytania makro czeka tylko `timeoutMs`. */
export const correctionLossLine = (timeoutMs: number) =>
  `WEBDIALOG|${CORRECTION_SCREEN.lossWindow}|${CORRECTION_SCREEN.lossText}|Oui|${timeoutMs}`

/**
 * Zakończenia nie da się cofnąć, więc decyduje pracownik; „Nie” zostawia otwarty dokument do sprawdzenia. Z `print` PDF
 * korekty idzie do drukarki domyślnej (PDFPRINTALL, odtwarzacz od wersji 0.51), a PDF-y otwarte wcześniej zamykają się
 * przed zakończeniem, żeby nie poszły do drukarki razem z nim; bez `print` PDF-y są tylko zamykane.
 */
export function correctionFinishLines(bonNumber: string, count: number, print = false) {
  const screen = CORRECTION_SCREEN
  return [
    `ASK|Wpisano pozycji: ${count} (bon ${bonNumber}). Sprawdź dokument w Mercalys. Zakończyć korektę? Tego nie da się cofnąć.`,
    ...(print ? [`WINCLOSEALL|${PDF_WINDOW}|0`] : []),
    `WEBCLICK|${WINDOW}|${screen.closeButton}`,
    `WEBWAIT|${DIALOG}|Oui|${screen.closeText}|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `SLEEP|${BETWEEN_CLOSE_DIALOGS_MS}`,
    `WEBWAIT|${DIALOG}|Oui||${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `WINMIN|${PROGRAM_WINDOW}`,
    `${print ? 'PDFPRINTALL' : 'WINCLOSEALL'}|${PDF_WINDOW}|${PDF_TIMEOUT_MS}`,
  ]
}

/** Przesunięcie stanu (st-e041). */
const TRANSFER_SCREEN = {
  stockTab: 'mnTp_STOCK',
  menuItem: 'mnTv_STOCK_st-e041_TransfertDeStock',
  eanField: 'txtZ1_CodeArticle_TxtBox',
  quantityField: 'txnZ2_Quantite_TxtBox',
  transferButton: 'btnB4_TranfertStock_LnkBouton',
  /** Pierwsze pytanie: „Czy chcesz zmienić stan magazynowy dla tych artykułów?”. */
  confirmText: 'zmieni',
  /** Drugie pytanie: „Czy chcesz wydrukować dane transakcji?”. */
  printText: 'wydrukowa',
} as const

export function transferStartLines() {
  const screen = TRANSFER_SCREEN
  return [
    mercalysOpenLine(PROGRAM_WINDOW),
    `WEBENSURE|${WINDOW}|${screen.menuItem}|${screen.stockTab}`,
    `WEBCLICK|${WINDOW}|${screen.menuItem}`,
    `WEBWAIT|${WINDOW}|${screen.eanField}||${PAGE_TIMEOUT_MS}`,
  ]
}

/** EAN i ilość zawsze w swoich polach, więc klawisze nie trafią w inne okno ani pole; `^a` zastępuje startowe 0,000. */
export const transferEanLine = (ean: string) => `WEBKEY|${WINDOW}|${TRANSFER_SCREEN.eanField}|^a${ean}{Enter}`

export const transferQuantityLine = (quantity: string) =>
  `WEBKEY|${WINDOW}|${TRANSFER_SCREEN.quantityField}|^a${quantity}{Enter}`

/** Przeniesienie wszystkich wpisanych artykułów, Tak przy zmianie stanu i Tak przy wydruku; pytania rozróżnia ich tekst. */
export function transferFinishLines() {
  const screen = TRANSFER_SCREEN
  return [
    `WEBCLICK|${WINDOW}|${screen.transferButton}`,
    `WEBWAIT|${DIALOG}|Oui|${screen.confirmText}|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `WEBWAIT|${DIALOG}|Oui|${screen.printText}|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `WINMIN|${PROGRAM_WINDOW}`,
  ]
}

/**
 * Kod PLU dla wagi: karta artykułu (ar-e001), lista jego kodów EAN (ar-e005, „Kod EAN13”) i tekst dowolny (ar-e007).
 */
const PLU_SCREEN = {
  articleTab: 'mnTp_ARTICLE',
  articleMenuItem: 'mnTv_ARTICLE_ar-e001_FicheArticle',
  eanField: 'txtZ4_CodeEAN_TxtBox',
  itm8Field: 'txtZ1_CodeITM_TxtBox',
  /** „Kod EAN13 :” na karcie: link pojawia się dopiero po wczytaniu artykułu. */
  eanCodesButton: 'lnkZ4_CodeEAN_LnkAviso',
  /** ar-e005: EAN wybranego wiersza; pusty, dopóki wiersz nie jest otwarty. */
  eanDetailField: 'txtZ02_CodeEAN_TxtBox',
  /** „Sekcja wagi” (1, 2): po wyborze Mercalys nadaje numer klawisza wagi. */
  sectionField: 'lstZ11_SectionPLUCode_TxtBox',
  scaleKeyField: 'txeZ14_ToucheBalance_TxtBox',
  pluField: 'txtZ12_CodePLU_TxtBox',
  eanApproveButton: 'btnB4_Valider_LnkBouton',
  /** „Tekst dowolny :” na karcie i jego pierwszy komentarz. */
  freeTextButton: 'lnkZ42_TexteLibre_LnkAviso',
  commentField: 'txtZ1_Commentaire1_TxtBox',
  freeTextApproveButton: 'btnB2_Valider_LnkBouton',
  /** Format etykiety na karcie artykułu. */
  labelFormatField: 'lstZ37_FormatEtiq_TxtBox',
  cardApproveButton: 'btnB6_Valider_LnkBouton',
  backButton: 'btnB1_Retour_LnkBouton',
  resetButton: 'btnB2_RAZ_LnkBouton',
  /** „Czy zachować wpis?” po Zatwierdź na liście EAN-ów i w tekście dowolnym. */
  keepText: 'zachowa',
  /** Zatwierdź na karcie artykułu może zapytać o zachowanie albo utworzenie wpisu; bez pytania makro idzie dalej. */
  cardConfirmText: 'zachowa;Utworzy',
} as const

/** Odczyt numeru klawisza wagi: do makra trafia jako %PLU%. */
const PLU_VARIABLE = 'PLU'
/** Kod PLU ma najwyżej 4 cyfry (tyle mieści pole), 0 to „jeszcze nie nadany”. */
const PLU_PATTERN = '^[1-9]\\d{0,3}$'

export const PLU_MACRO_CONFIG = {
  /** Po „Tak” strona się przeładowuje: klik w starą stronę by przepadł. */
  afterConfirm: 1500,
  /** Ile makro czeka na pytanie po Zatwierdź na karcie artykułu (pojawia się tylko czasem). */
  cardDialogTimeout: 3000,
  /** Ile makro czeka na numer klawisza wagi po wyborze sekcji. */
  scaleKeyTimeout: 15000,
} as const

export type PluSection = '1' | '2'

/**
 * Makro kodów PLU dla nowych kodów 24…: dla każdego EAN-u po kolei karta artykułu, „Kod EAN13”, podwójny klik w wiersz
 * z tym EAN-em, sekcja wagi, numer klawisza wagi przepisany do kodu PLU, Zatwierdź, Tak, Powrót, „Tekst dowolny”
 * z komentarzem „KOD: <PLU>”, Zatwierdź, Tak, Powrót, format etykiety sekcji, Zatwierdź i ZER. Artykuł, który ma już kod PLU,
 * nie dostaje nowego, ale jego kod trafia do komentarza i format jest ustawiany. EAN-u bez artykułu w Mercalys makro
 * nie przeskakuje: staje z błędem (najpierw trzeba wczytać plik nowych artykułów). Na końcu chowa Mercalys.
 * Wymaga odtwarzacza od wersji 41 (WEBDBLROW, WEBREAD).
 */
export function buildPluMacro({
  eans,
  section,
  labelFormat,
}: {
  eans: string[]
  section: PluSection
  labelFormat: string
}) {
  const screen = PLU_SCREEN
  const c = PLU_MACRO_CONFIG
  const plu = `%${PLU_VARIABLE}%`
  const out = [
    `# Kody PLU: ${eans.length}, sekcja wagi ${section}, format ${labelFormat}`,
    mercalysOpenLine(PROGRAM_WINDOW),
    `WEBENSURE|${WINDOW}|${screen.articleMenuItem}|${screen.articleTab}`,
    `WEBCLICK|${WINDOW}|${screen.articleMenuItem}`,
  ]
  eans.forEach((ean, index) => {
    const hasPlu = `plu-ma-${index + 1}`
    // Kod 12-cyfrowy Mercalys może pokazać jako EAN-13 z zerem z przodu.
    const eanValue = `^0*${ean.replace(/^0+/, '')}$`
    out.push(
      `# ${index + 1}/${eans.length}: EAN ${ean}`,
      `WEBWAIT|${WINDOW}|${screen.eanField}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.eanField}|^a${ean}{Enter}`,
      // Wczytany artykuł ma ITM8; po ZER. pole jest puste, więc stara karta nie uda wczytanej.
      `WEBVALUE|${WINDOW}|${screen.itm8Field}|^0*[1-9]\\d*$|${PAGE_TIMEOUT_MS}`,
      `WEBVALUE|${WINDOW}|${screen.eanField}|${eanValue}`,
      `WEBCLICK|${WINDOW}|${screen.eanCodesButton}`,
      `WEBWAIT|${WINDOW}|${screen.eanDetailField}||${PAGE_TIMEOUT_MS}`,
      `WEBDBLROW|${WINDOW}|${GRID}|${ean}|${PAGE_TIMEOUT_MS}`,
      `WEBVALUE|${WINDOW}|${screen.eanDetailField}|${eanValue}|${PAGE_TIMEOUT_MS}`,
      // Kod PLU już jest: zapamiętany od razu (po zapisie Mercalys czyści szczegóły wiersza), bez nowej sekcji
      // i zapisu, dalej tylko komentarz i format. Pusty kod nadpisze odczyt numeru klawisza wagi.
      `WEBREAD|${WINDOW}|${screen.pluField}|${PLU_VARIABLE}|^(\\d{1,4})?$|${PAGE_TIMEOUT_MS}`,
      `WEBIFVALUE|${WINDOW}|${screen.pluField}|^0?$|${hasPlu}|500`,
      `WEBKEY|${WINDOW}|${screen.sectionField}|^a${section}{Tab}`,
      `WEBREAD|${WINDOW}|${screen.scaleKeyField}|${PLU_VARIABLE}|${PLU_PATTERN}|${c.scaleKeyTimeout}`,
      `WEBKEY|${WINDOW}|${screen.pluField}|^a${plu}{Tab}`,
      `WEBVALUE|${WINDOW}|${screen.pluField}|^${plu}$`,
      `WEBCLICK|${WINDOW}|${screen.eanApproveButton}`,
      `WEBWAIT|${DIALOG}|Oui|${screen.keepText}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${DIALOG}|Oui`,
      `SLEEP|${c.afterConfirm}`,
      `LABEL|${hasPlu}`,
      `WEBCLICK|${WINDOW}|${screen.backButton}`,
      `WEBWAIT|${WINDOW}|${screen.freeTextButton}||${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${screen.freeTextButton}`,
      `WEBWAIT|${WINDOW}|${screen.commentField}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.commentField}|^aKOD: ${plu}`,
      `WEBVALUE|${WINDOW}|${screen.commentField}|^KOD: ${plu}$`,
      `WEBCLICK|${WINDOW}|${screen.freeTextApproveButton}`,
      `WEBWAIT|${DIALOG}|Oui|${screen.keepText}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${DIALOG}|Oui`,
      `SLEEP|${c.afterConfirm}`,
      `WEBCLICK|${WINDOW}|${screen.backButton}`,
      `WEBWAIT|${WINDOW}|${screen.labelFormatField}||${PAGE_TIMEOUT_MS}`,
      `WEBVALUE|${WINDOW}|${screen.eanField}|^${ean}$|${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.labelFormatField}|^a${labelFormat}{Tab}`,
      `WEBVALUE|${WINDOW}|${screen.labelFormatField}|^${labelFormat}$`,
      `WEBCLICK|${WINDOW}|${screen.cardApproveButton}`,
      `WEBDIALOG|${DIALOG}|${screen.cardConfirmText}|Oui|${c.cardDialogTimeout}`,
      `SLEEP|${c.afterConfirm}`,
      `WEBWAIT|${WINDOW}|${screen.resetButton}||${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${screen.resetButton}`,
      // Pusta karta: kolejny EAN nie trafi w stary artykuł, a jego ITM8 nie uda wczytanego.
      `WEBVALUE|${WINDOW}|${screen.eanField}|^$|${PAGE_TIMEOUT_MS}`,
      `WEBVALUE|${WINDOW}|${screen.itm8Field}|^0*$|${PAGE_TIMEOUT_MS}`,
    )
  })
  out.push(`WINMIN|${PROGRAM_WINDOW}`)
  return out.join('\r\n') + '\r\n'
}

/**
 * Kody MEA: Cadencier → Lista Cadencier (ca-e064), filtr Nazwa Cad., przetworzenie pliku (ca-e029), Wybór (ca-e062),
 * Zatwierdź, Integracja (ca-e032), Integracja, Tak, PDF, Powrót.
 */
const MEA_SCREEN = {
  cadencierTab: 'mnTp_CADENCIER',
  listMenuItem: 'mnTv_CADENCIER_ca-e064_ListeCadenciers',
  nameFilter: 'txtZ1_LibelleCadencier_TxtBox',
  /** Przycisk „Przetworzenie” w wierszu (`dgDetail__ctl<n>_ibtC11_Traitement`; numer w id liczy też wiersze schowane filtrem). */
  rowButton: 'ibtC11_Traitement',
  /** „Wybór” (zielona gwiazdka). Obok jest „Wybór wysz.” (`btnB11_SelectionRecherche`), tryb wyszukiwania: nie ten. */
  selectionButton: 'btnB3_Select_LnkBouton',
  approveSelectionButton: 'btnB2_Valider_LnkBouton',
  integrationButton: 'btnB8_Integration_LnkBouton',
  integrateButton: 'btnB2_Integrer_LnkBouton',
  /** „Czy potwierdzasz integrację wybranych artykułów?” */
  integrateText: 'integracj',
  backButton: 'btnB1_Retour_LnkBouton',
} as const

/** Pod jednym kodem MEA bywa 1-3 plików cadencier; tyle razy makro bierze najwyżej pierwszy plik z listy. */
export const MEA_MAX_FILES = 5
/**
 * Po integracji Acrobat pokazuje raport; czekamy, aż się pojawi i okna przestaną się zmieniać. Integracja dużego pliku
 * trwa ponad 45 s, a PDF spóźniony za makro zabierał klawisze filtra, więc tyle co sama integracja.
 */
const MEA_PDF_TIMEOUT_MS = INTEGRATION_TIMEOUT_MS
/** Gdy filtr po wpisaniu nie ma kodu, PDF właśnie się otwiera: czekamy, aż ustoi (4 s bez zmian), i go zamykamy. */
const MEA_LATE_PDF_MS = 8000
/** Tyle WEBIFVALUE czeka, zanim uzna, że w filtrze jest już ten kod (pole jest od razu, więc krótko). */
const FILTER_CHECK_MS = 300

/**
 * Makro kodów MEA: dla każdego kodu filtr listy cadencier i po kolei każdy plik z tym kodem (Przetworzenie, Wybór,
 * Zatwierdź, Integracja, Integracja, Tak, zamknięcie PDF-a, Powrót). Po każdym pliku kod jest wpisywany od nowa, a filtr
 * listy działa na stronie, bez przeładowania: WEBIFROW czeka, aż widoczne są tylko wiersze z kodem (albo żaden), i podaje
 * przycisk n-tego jako %ROW%; gdy plików jest mniej, przechodzi do następnego kodu. Na końcu chowa Mercalys.
 * Wymaga odtwarzacza od wersji 41 (WEBIFROW z %ROW%, LABEL).
 */
export function buildMeaMacro(codes: string[]) {
  const screen = MEA_SCREEN
  const out = [
    `# Kody MEA: ${codes.length}`,
    mercalysOpenLine(PROGRAM_WINDOW),
    `WEBENSURE|${WINDOW}|${screen.listMenuItem}|${screen.cadencierTab}`,
    `WEBCLICK|${WINDOW}|${screen.listMenuItem}`,
    `WEBWAIT|${WINDOW}|${screen.nameFilter}||${PAGE_TIMEOUT_MS}`,
  ]
  for (const code of codes) {
    const label = `mea-${code}`
    out.push(`# Kod MEA ${code}`)
    for (let row = 1; row <= MEA_MAX_FILES; row++) {
      const typed = `${label}-${row}`
      const retyped = `${typed}-again`
      out.push(
        `# Plik ${row}`,
        // Raport poprzedniego pliku potrafi otworzyć się dopiero teraz (długa integracja, drugi PDF): zamykamy go, zanim
        // klawisze filtra trafią w Acrobata.
        `WINCLOSEALL|${PDF_WINDOW}|0`,
        // Po Powrót w filtrze zostaje ten sam kod, a WEBIFROW i tak liczy tylko wiersze z kodem: kod wpisujemy tylko,
        // gdy w polu jest inny (pasuje = idzie do WEBKEY, nie pasuje = przeskok za nie).
        `WEBIFVALUE|${WINDOW}|${screen.nameFilter}|^(?!${code}$)|${typed}|${FILTER_CHECK_MS}`,
        `WEBKEY|${WINDOW}|${screen.nameFilter}|^a${code}{Enter}`,
        // PDF, który wyskoczył w trakcie wpisywania, zabrał klawisze: filtr nie ma kodu, więc zamykamy go i wpisujemy jeszcze raz.
        `WEBIFVALUE|${WINDOW}|${screen.nameFilter}|^(?!${code}$)|${typed}|${FILTER_CHECK_MS}`,
        `WINCLOSEALL|${PDF_WINDOW}|${MEA_LATE_PDF_MS}`,
        `WEBIFVALUE|${WINDOW}|${screen.nameFilter}|^(?!${code}$)|${retyped}|${FILTER_CHECK_MS}`,
        `WEBKEY|${WINDOW}|${screen.nameFilter}|^a${code}{Enter}`,
        `LABEL|${retyped}`,
        `LABEL|${typed}`,
        // Zintegrowany plik znika z listy (pokazuje tylko „Do przetw.”), więc zawsze pierwszy widoczny plik z kodem;
        // pusta lista = kod skończony, przeskok do następnego.
        `WEBIFROW|${WINDOW}|${screen.nameFilter}|${GRID}|${screen.rowButton}|1|${code}|${label}|${PAGE_TIMEOUT_MS}`,
        // Filtr chowa wiersze na stronie, więc przycisk n-tego widocznego pliku podaje WEBIFROW (%ROW%).
        `WEBCLICK|${WINDOW}|%ROW%`,
        `WEBWAIT|${WINDOW}|${screen.selectionButton}||${PAGE_TIMEOUT_MS}`,
        `WEBCLICK|${WINDOW}|${screen.selectionButton}`,
        `WEBWAIT|${WINDOW}|${screen.approveSelectionButton}||${PAGE_TIMEOUT_MS}`,
        `WEBCLICK|${WINDOW}|${screen.approveSelectionButton}`,
        `WEBWAIT|${WINDOW}|${screen.integrationButton}||${PAGE_TIMEOUT_MS}`,
        `WEBCLICK|${WINDOW}|${screen.integrationButton}`,
        `WEBWAIT|${WINDOW}|${screen.integrateButton}||${PAGE_TIMEOUT_MS}`,
        `WEBCLICK|${WINDOW}|${screen.integrateButton}`,
        `WEBWAIT|${DIALOG}|Oui|${screen.integrateText}|${PAGE_TIMEOUT_MS}`,
        `WEBCLICK|${DIALOG}|Oui`,
        // Raport integracji otwiera się w Acrobacie po końcu integracji (zwykle 2-3 s, przy dużym pliku ponad minutę);
        // ekran po integracji nie ma już przycisku Integracja, więc najpierw PDF (WINCLOSEALL czeka, aż się pojawi
        // i ustoi), potem Powrót, gdy już jest.
        `WINCLOSEALL|${PDF_WINDOW}|${MEA_PDF_TIMEOUT_MS}`,
        `WEBWAIT|${WINDOW}|${screen.backButton}||${INTEGRATION_TIMEOUT_MS}`,
        `WEBCLICK|${WINDOW}|${screen.backButton}`,
        `WEBWAIT|${WINDOW}|${screen.nameFilter}||${PAGE_TIMEOUT_MS}`,
      )
    }
    out.push(`LABEL|${label}`)
  }
  out.push(`WINMIN|${PROGRAM_WINDOW}`)
  return out.join('\r\n') + '\r\n'
}

/**
 * Taryfa → Kontrola ceny: okno pobierania danych z historią plików zmian cen (dgHisto: data, użytkownik, stacja, stan
 * „Przetw. N raz”, przycisk opracowania w wierszu) i Różne ceny (ar-e084).
 */
const PRICE_CONTROL_SCREEN = {
  tariffTab: 'mnTp_TARIF',
  /** „Kontrola ceny”: samo rozwija menu z Pobraniem danych i Różnymi cenami. */
  controlMenuItem: 'mnTv_TARIF_ControlePrix',
  /** „Pobranie danych”: okno pobierania z terminala (PopUpPortable) z przyciskiem Historia. */
  downloadMenuItem: 'mnTv_TARIF_ar-e083_Dechargement',
  historyButton: 'btnHistorique_LnkBouton',
  historyBackButton: 'btnRetourHisto_LnkBouton',
  historyGrid: 'dgHisto',
  /** Przycisk opracowania w wierszu historii (`dgHisto__ctl<n>_ImgButtonTraiter_ImageButton1`). */
  processButton: 'ImgButtonTraiter_ImageButton1',
  nextPageButton: 'pgrDetail_ibtSuivant',
  /**
   * Historia ma dziesiątki stron (10023, 19.11.2026: 29), a kolejności nie znamy, więc makro przegląda wszystkie;
   * kończy wcześniej, gdy przycisk następnej strony jest wyłączony.
   */
  historyPages: 60,
  /** „Przetworzyć plik ponownie?” */
  processText: 'ponownie',
  differentPricesMenuItem: 'mnTv_TARIF_ar-e084_PrixDifferents',
  selectAllButton: 'btnB2_SelectTous_LnkBouton',
  alignMsiButton: 'btnB6_AlignerMSI_LnkBouton',
  /**
   * Po „Zaznacz wszystko”: tabela nie ma kolumny stanu (zaznaczony wiersz widać tylko kolorem), więc czekamy, aż
   * „Suma wyboru” w nagłówku strony zrówna się z „Suma linii”. Bez `|`, bo to separator pól makra.
   */
  selectedPagePattern: String.raw`i)Suma linii\s*:\s*([1-9]\d*)(?!\d)[\s\S]*?Suma wyboru\s*:\s*\1(?!\d)`,
  /**
   * Po Porównaj MSI: artykuł w promocji MSI pomija, więc zostaje w tabeli (tabela nie musi być pusta), a po przeładowaniu
   * nic nie jest zaznaczone. Czekamy na „Suma wyboru: 0” ze strony, a nie na wygląd wierszy: wcześniejszy wzorzec
   * gwiazdki przed nazwą nie łapał każdej promocji i makro stawało na pozostałych artykułach. Przed kliknięciem suma
   * wyboru równa się sumie linii (od 1), więc warunek nie przejdzie za wcześnie; pusta tabela kończy krok tak samo.
   */
  alignedPagePattern: String.raw`i)Suma wyboru\s*:\s*0(?!\d)`,
  /** „Art.w promocji nie będą uwzględnione”: komunikat bez tytułu, przycisk Zamknij. */
  alertWindow: 'ahk_class Internet Explorer_TridentDlgFrame',
  alertText: 'promocji',
  alertButton: 'Fermer',
} as const

/** Opracowanie dużego pliku i wyrównanie cen potrafią trwać. */
const PRICE_CONTROL_TIMEOUT_MS = 120000
const PRICE_CONTROL_ALERT_MS = 15000

/*
 * To samo makro składa serwer dla agenta (backend: dataentry/component/PriceControlMacro), więc zmiana ekranów Mercalys
 * to zmiana w obu miejscach.
 */

/** Plik zmiany cen do opracowania: czas z nazwy pliku (jak w historii Mercalys) i liczba cen. */
export type PriceControlMacroFile = {
  fileName: string
  lineCount: number
  label: string
}

/** Data i godzina z nazwy `CTRLPRIX_RRRRMMDD_HHMMSS_…`; null = nazwa w innym układzie. */
export function priceControlFileTime(fileName: string) {
  const match = /CTRLPRIX_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})/i.exec(fileName)
  if (!match) return null
  const [, year, month, day, hour, minute] = match
  return { year, month, day, hour, minute }
}

/**
 * Wiersz historii tego pliku: data (dowolny separator, rok z 2 albo 4 cyframi) oraz godzina i minuta z nazwy pliku albo
 * minuta później (zapis pliku na przełomie minut). Historia ma tylko kolumny Data, Użytkownik i Stacja (skan z 10023,
 * 19.11.2026), więc liczby cen ani stanu opracowania w wierszu nie ma: wcześniejszy wzorzec z nimi nie trafiał w żaden
 * wiersz. Bez `|`, bo dzieli linię makra.
 */
export function priceControlRowPattern(file: PriceControlMacroFile) {
  const time = priceControlFileTime(file.fileName)
  if (!time) return null
  const date = String.raw`(?<!\d)${time.day}[./-]${time.month}[./-](?:${time.year.slice(0, 2)})?${time.year.slice(2)}(?!\d)`
  // Kolejna minuta klasą znaków, bo alternatywa z `|` rozcięłaby linię; przy końcówce 9 (14:29 → 14:30) tylko ta minuta.
  const last = Number(time.minute[1])
  const minute = last < 9 ? `${time.minute[0]}[${last}${last + 1}]` : time.minute
  const clock = String.raw`(?<!\d)${time.hour}:${minute}(?!\d)`
  return String.raw`i)^(?=.*${date})(?=.*${clock})`
}

/**
 * „Wprowadź zmiany cen”: dla każdego pliku Taryfa → Kontrola ceny → Pobranie danych → Historia, opracowanie wiersza tego pliku (data,
 * godzina, liczba cen, „Przetw. 0 raz”), Tak, zamknięcie PDF-a, potem Różne ceny: Zaznacz wszystko, czekanie na
 * zaznaczone wiersze, Porównaj MSI, Zamknij przy komunikacie o promocjach i czekanie na „Suma wyboru: 0” (artykuły w promocji zostają w tabeli). Pliku, którego
 * nie ma w historii, makro nie rusza (wpis w podsumowaniu). Wymaga odtwarzacza od wersji 0.60 (WEBROWBUTTON z tekstem wiersza siatki, nie tabelki przycisku; WEBGRIDWAIT z warunkiem page i none z wyrażeniem).
 */
export function buildPriceControlMacro(files: PriceControlMacroFile[]) {
  const screen = PRICE_CONTROL_SCREEN
  const out = [
    `# Wprowadzenie zmian cen: ${files.length} (odtwarzacz od wersji 0.60)`,
    mercalysOpenLine(PROGRAM_WINDOW),
  ]
  files.forEach((file, index) => {
    const pattern = priceControlRowPattern(file)
    if (!pattern) return
    const skip = `zmiana-cen-${index + 1}`
    const empty = `${skip}-bez-roznic`
    const label = file.label.replace(/[|\r\n]/g, ' ')
    out.push(
      `# ${index + 1}/${files.length}: ${label}`,
      `WEBENSURE|${WINDOW}|${screen.controlMenuItem}|${screen.tariffTab}`,
      // Kontrola ceny rozwija się tylko, gdy Pobrania danych nie widać; klik w Pobranie danych ponawiany, aż jest Historia.
      `WEBOPEN|${WINDOW}|${screen.controlMenuItem}|${screen.downloadMenuItem}|${screen.historyButton}`,
      `WEBWAIT|${WINDOW}|${screen.historyButton}||${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${screen.historyButton}`,
      `WEBWAIT|${WINDOW}|${screen.historyBackButton}||${PAGE_TIMEOUT_MS}`,
      `WEBROWBUTTON|${WINDOW}|${screen.historyGrid}|${pattern}|${screen.processButton}|${skip}|${label}|${screen.nextPageButton}|${screen.historyPages}|${PAGE_TIMEOUT_MS}`,
      `WEBWAIT|${DIALOG}|Oui|${screen.processText}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${DIALOG}|Oui`,
      `WINCLOSEALL|${PDF_WINDOW}|${PRICE_CONTROL_TIMEOUT_MS}`,
      `WEBENSURE|${WINDOW}|${screen.controlMenuItem}|${screen.tariffTab}`,
      `WEBOPEN|${WINDOW}|${screen.controlMenuItem}|${screen.differentPricesMenuItem}|${screen.selectAllButton}`,
      `WEBWAIT|${WINDOW}|${screen.selectAllButton}||${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${screen.selectAllButton}`,
      `WEBGRIDWAIT|${WINDOW}|${screen.alignMsiButton}|page|${screen.selectedPagePattern}|${empty}|Różne ceny bez artykułów: ${label}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${screen.alignMsiButton}`,
      `WEBDIALOG|${screen.alertWindow}|${screen.alertText}|${screen.alertButton}|${PRICE_CONTROL_ALERT_MS}`,
      // Po wyrównaniu zostają artykuły w promocji (albo nic), bez zaznaczenia; pusta tabela skacze do etykiety zaraz niżej.
      `WEBGRIDWAIT|${WINDOW}|${screen.alignMsiButton}|page|${screen.alignedPagePattern}|${empty}||${PRICE_CONTROL_TIMEOUT_MS}`,
      `LABEL|${empty}`,
      `LABEL|${skip}`,
    )
  })
  out.push(`WINMIN|${PROGRAM_WINDOW}`)
  return out.join('\r\n') + '\r\n'
}
