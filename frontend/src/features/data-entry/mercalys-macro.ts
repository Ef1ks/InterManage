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
 * a podsumowanie mówi, czego nie znalazło. CRLF jak w Windows.
 */
export function buildDeliveryMacro(documents: MacroDocument[]) {
  const bl = BL_SCREEN
  const order = ORDER_SCREEN
  return (
    [
      `# Zamknięcie faktur: ${documents.length}`,
      ...documents.map((document) => `WEBLIST|${document.supplierCode}|${document.number}`),
      `WINOPEN|${PROGRAM_WINDOW}`,
      `WEBENSURE|${WINDOW}|${MENU.directDeliveries}|${MENU.purchasesTab}`,
      `WEBOPEN|${WINDOW}|${MENU.directDeliveries}|${MENU.integration}`,
      `WEBWAIT|${DIALOG}|Oui|${INTEGRATION_SCREEN.confirmText}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${DIALOG}|Oui`,
      // Tabela z podsumowaniem (na-e015). Powrót ma też ekran sprzed integracji, więc czekamy na tę stronę, a nie na sam
      // przycisk: inaczej Powrót padał w trakcie integracji. Potem Powrót i Obsługa BL (klik w menu ponawiany, gdyby przepadł).
      `WEBPAGE|${WINDOW}|${INTEGRATION_SCREEN.resultPage}|${INTEGRATION_SCREEN.result}|${INTEGRATION_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${INTEGRATION_SCREEN.result}`,
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
      `WINCLOSEALL|${PDF_WINDOW}|${PDF_TIMEOUT_MS}`,
      `WINMIN|${PROGRAM_WINDOW}`,
    ].join('\r\n') + '\r\n'
  )
}

/** Obsługa BL bazy (ac-e034): lista plików bonów, a po Wybór linie bonu (ac-e080) z błędami. */
const BASE_BL_SCREEN = {
  menuItem: 'mnTv_ACHAT_ac-e034_BlEuropa',
  selectionButton: 'btnSelection_LnkBouton',
  /** Przycisk ekranu linii bonu: mówi, że Wybór otworzył bon. */
  detailScreen: 'btnB3_Integration_LnkBouton',
  /** Lista plików: nazwa `095_<nr bonu>_00538` w kolumnie 0, data w 1. */
  filesGrid: 'dgDetail',
  /** Linie bonu: ITM8 (13 cyfr z zerami) w kolumnie 0, błąd w kolumnie 7. */
  linesGrid: 'dgArticles',
  missingItm8Text: 'ITM nie istnieje',
  /** Kolumna z kodem ITM8 od zera albo `auto`: pierwsza komórka z samym kodem do 8 cyfr. */
  itm8Column: '0',
  nextPageButton: 'pgrDetail_ibtSuivant',
  pageList: 'pgrDetail_ddlGoTo',
  /** Wiersze z błędami są na początku listy: strona bez „ITM nie istnieje” kończy przegląd. */
  stopOnEmptyPage: '1',
} as const

/** Otwarcie dużego bonu potrafi trwać. */
const BASE_BL_TIMEOUT_MS = 60000

/**
 * „Zbierz brakujące ITM8”: Zakup → Obsługa BL bazy, wiersz z plikiem bonu (nazwa `…_<nr bonu>_…`, zera wiodące dowolne),
 * Wybór, a z linii bonu ze wszystkich stron kody ITM8 z błędem „ITM nie istnieje” trafiają do Schowka. Na końcu powrót
 * na Zakup i Mercalys na pasek zadań. Wymaga odtwarzacza od wersji 38 (WEBPICKRE, WEBCOLLECT).
 */
export function buildMissingItm8Macro(bonNumber: string) {
  const screen = BASE_BL_SCREEN
  const digits = bonNumber.replace(/\D/g, '').replace(/^0+/, '')
  return (
    [
      `# Zbierz brakujące ITM8: bon ${bonNumber}`,
      `WINOPEN|${PROGRAM_WINDOW}`,
      `WEBENSURE|${WINDOW}|${MENU.deliveries}|${MENU.purchasesTab}`,
      `WEBOPEN|${WINDOW}|${MENU.deliveries}|${screen.menuItem}`,
      `WEBWAIT|${WINDOW}|${screen.selectionButton}||${PAGE_TIMEOUT_MS}`,
      `WEBPICKRE|${WINDOW}|${screen.filesGrid}|(?<!\\d)0*${digits}(?!\\d)|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${screen.selectionButton}`,
      `WEBWAIT|${WINDOW}|${screen.detailScreen}||${BASE_BL_TIMEOUT_MS}`,
      `WEBCOLLECT|${WINDOW}|${screen.linesGrid}|${screen.missingItm8Text}|${screen.itm8Column}|${screen.nextPageButton}|${screen.pageList}|${screen.stopOnEmptyPage}`,
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
    `WINOPEN|${PROGRAM_WINDOW}`,
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
 * Makro przeceny: przywraca Mercalys z paska zadań, otwiera kartę artykułu po EAN-ie i taryfy sprzedaży, wpisuje okres
 * i cenę, zatwierdza, wraca, zeruje kartę i chowa program, więc pracownik wraca do przeglądarki. Inny artykuł na karcie
 * albo wartości, których formularz nie przyjął, zatrzymują makro przed zapisem.
 */
export function buildMarkdownMacro({ ean, itm8, price, from, to }: MarkdownMacro) {
  const screen = MARKDOWN_SCREEN
  const priceText = price.toFixed(2).replace('.', ',')
  return (
    [
      `# Przecena ${ean}`,
      `WINOPEN|${PROGRAM_WINDOW}`,
      `WEBENSURE|${WINDOW}|${screen.articleMenuItem}|${screen.articleTab}`,
      `WEBCLICK|${WINDOW}|${screen.articleMenuItem}`,
      `WEBWAIT|${WINDOW}|${screen.eanField}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.eanField}|^a${ean}{Enter}`,
      `WEBVALUE|${WINDOW}|${screen.itm8Field}|^0*${withoutLeadingZeros(itm8)}$|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${screen.salesTariffsButton}`,
      `WEBWAIT|${WINDOW}|${screen.dateFromField}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.dateFromField}|^a${mercalysDate(from)}{Tab}`,
      `WEBCHECK|${WINDOW}|${screen.promoCheckbox}`,
      `SLEEP|${AFTER_PROMO_SLEEP_MS}`,
      `WEBKEY|${WINDOW}|${screen.dateToField}|^a${mercalysDate(to)}{Tab}`,
      `WEBKEY|${WINDOW}|${screen.priceField}|^a${priceText}{Tab}`,
      `WEBDIALOG|${DIALOG}|${screen.lossText}|Oui|${LOSS_DIALOG_TIMEOUT_MS}`,
      `WEBVALUE|${WINDOW}|${screen.dateFromField}|${datePattern(from)}`,
      `WEBVALUE|${WINDOW}|${screen.promoCheckbox}|^tak$`,
      `WEBVALUE|${WINDOW}|${screen.dateToField}|${datePattern(to)}`,
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
      `WINMIN|${PROGRAM_WINDOW}`,
    ].join('\r\n') + '\r\n'
  )
}

/** Wprowadzanie promocji: nagłówek operacji (ar-e072) i artykuły operacji (ar-e074). */
const PROMO_SCREEN = {
  tariffTab: 'mnTp_TARIF',
  menuItem: 'mnTv_TARIF_ar-e072_SaisieDesPromos',
  codeField: 'lstZ1_CodeOp_TxtBox',
  nameField: 'txtZ2_LibelleOp_TxtBox',
  typeField: 'lstZ3_TypeOp_TxtBox',
  type: '1',
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
} as const

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
} as const

export type PromoMacroItem = { ean: string; price: number; dateTo: string }

/**
 * Wprowadzanie promocji: Taryfa → Wprowadzanie promocji, kod i nazwa operacji, typ 1, koniec operacji = najpóźniejszy
 * koniec artykułów, Zatwierdź, Tak, Wpisz; potem dla każdego artykułu EAN, koniec sprzedaży, cena promocji z Enterem,
 * Tak przy sprzedaży ze stratą (gdy Mercalys zapyta) i Tak przy „Utworzyć zapis ?”. Na końcu Powrót i Mercalys na
 * pasek zadań. Początku operacji makro nie zmienia: Mercalys podpowiada dzisiejszy. Kod istniejącej operacji wypełnia
 * panel: makro ją usuwa (Usuń, Tak) i wpisuje kod od nowa. Wymaga odtwarzacza od wersji 39 (WEBIFVALUE).
 */
export function buildPromoEntryMacro({ code, name, items }: { code: string; name: string; items: PromoMacroItem[] }) {
  const screen = PROMO_SCREEN
  const c = PROMO_MACRO_CONFIG
  const end = items.reduce((latest, item) => (item.dateTo > latest ? item.dateTo : latest), '')
  const out = [
    `# Wprowadzanie promocji ${code}: ${items.length} art.`,
    `WINOPEN|${PROGRAM_WINDOW}`,
    `WEBENSURE|${WINDOW}|${screen.menuItem}|${screen.tariffTab}`,
    `WEBCLICK|${WINDOW}|${screen.menuItem}`,
    `WEBWAIT|${WINDOW}|${screen.codeField}||${PAGE_TIMEOUT_MS}`,
    `WEBKEY|${WINDOW}|${screen.codeField}|^a${code}{Tab}`,
    // Operacja z tym kodem już jest (panel wypełniony, np. PROMO GNIEZNO): Usuń, Tak, kod od nowa.
    `WEBIFVALUE|${WINDOW}|${screen.nameField}|\\S|${screen.freeCodeLabel}|${c.existingCheck}`,
    `WEBCLICK|${WINDOW}|${screen.deleteButton}`,
    `WEBWAIT|${DIALOG}|Oui|${screen.deleteText}|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `SLEEP|${c.afterSave}`,
    `WEBWAIT|${WINDOW}|${screen.codeField}||${PAGE_TIMEOUT_MS}`,
    `WEBKEY|${WINDOW}|${screen.codeField}|^a${code}{Tab}`,
    `WEBVALUE|${WINDOW}|${screen.nameField}|^$|${c.existingCheck}`,
    `LABEL|${screen.freeCodeLabel}`,
    `WEBKEY|${WINDOW}|${screen.nameField}|^a${name}{Tab}`,
    `WEBKEY|${WINDOW}|${screen.typeField}|^a${screen.type}{Tab}`,
    `WEBKEY|${WINDOW}|${screen.endField}|^a${mercalysDate(end)}{Tab}`,
    `WEBVALUE|${WINDOW}|${screen.codeField}|^${code}$`,
    `WEBVALUE|${WINDOW}|${screen.typeField}|^${screen.type}$`,
    `WEBVALUE|${WINDOW}|${screen.endField}|${datePattern(end)}`,
    `WEBCLICK|${WINDOW}|${screen.approveButton}`,
    `WEBWAIT|${DIALOG}|Oui|${screen.createText}|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `SLEEP|${c.afterSave}`,
    `WEBWAIT|${WINDOW}|${screen.enterButton}||${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${WINDOW}|${screen.enterButton}`,
  ]
  for (const item of items) {
    const price = item.price.toFixed(2).replace('.', ',')
    out.push(
      `# EAN ${item.ean}: ${price} do ${item.dateTo}`,
      `WEBWAIT|${WINDOW}|${screen.articleField}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${screen.articleField}|^a${item.ean}{Enter}`,
      `SLEEP|${c.afterEan}`,
      `WEBKEY|${WINDOW}|${screen.saleEndField}|^a${mercalysDate(item.dateTo)}{Tab}`,
      `WEBVALUE|${WINDOW}|${screen.saleEndField}|${datePattern(item.dateTo)}`,
      `WEBKEY|${WINDOW}|${screen.priceField}|^a${price}{Enter}`,
      `WEBDIALOG|${DIALOG}|${screen.lossText}|Oui|${LOSS_DIALOG_TIMEOUT_MS}`,
      `WEBWAIT|${DIALOG}|Oui|${screen.createText}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${DIALOG}|Oui`,
      `SLEEP|${c.afterSave}`,
    )
  }
  out.push(
    // Po ostatnim zapisie strona artykułów się przeładowuje: klik w starą stronę by przepadł, a schowany program
    // wracał na wierzch po przeładowaniu. Powrót dopiero na nowej stronie, chowanie po wejściu na nagłówek operacji.
    `WEBWAIT|${WINDOW}|${screen.articleField}||${PAGE_TIMEOUT_MS}`,
    `SLEEP|${c.beforeBack}`,
    `WEBCLICK|${WINDOW}|${screen.backButton}`,
    `WEBWAIT|${WINDOW}|${screen.codeField}||${PAGE_TIMEOUT_MS}`,
    `WINMIN|${PROGRAM_WINDOW}`,
  )
  return out.join('\r\n') + '\r\n'
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
      `WINOPEN|${PROGRAM_WINDOW}`,
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
  /** 9 = korekta dostawy z bazy (SCA PR, dostawca 538). */
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
} as const

/** Pierwsze okno po Zakończ znika, a drugie pojawia się razem z PDF-em. */
const BETWEEN_CLOSE_DIALOGS_MS = 2000

/** Nowy protokół z dzisiejszym terminem płatności; po zapisie szczegół otwiera się sam albo przyciskiem. */
export function correctionStartLines(today: string, fromBase = false) {
  const screen = CORRECTION_SCREEN
  const type = fromBase ? screen.baseErrorType : screen.supplierErrorType
  return [
    `WINOPEN|${PROGRAM_WINDOW}`,
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

/** Zakończenia nie da się cofnąć, więc decyduje pracownik; „Nie” zostawia otwarty dokument do sprawdzenia. */
export function correctionFinishLines(bonNumber: string, count: number) {
  const screen = CORRECTION_SCREEN
  return [
    `ASK|Wpisano pozycji: ${count} (bon ${bonNumber}). Sprawdź dokument w Mercalys. Zakończyć korektę? Tego nie da się cofnąć.`,
    `WEBCLICK|${WINDOW}|${screen.closeButton}`,
    `WEBWAIT|${DIALOG}|Oui|${screen.closeText}|${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `SLEEP|${BETWEEN_CLOSE_DIALOGS_MS}`,
    `WEBWAIT|${DIALOG}|Oui||${PAGE_TIMEOUT_MS}`,
    `WEBCLICK|${DIALOG}|Oui`,
    `WINMIN|${PROGRAM_WINDOW}`,
    `WINCLOSEALL|${PDF_WINDOW}|${PDF_TIMEOUT_MS}`,
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
    `WINOPEN|${PROGRAM_WINDOW}`,
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
    `WINOPEN|${PROGRAM_WINDOW}`,
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
/** Po integracji Acrobat pokazuje raport; czekamy, aż okna przestaną się zmieniać. */
const MEA_PDF_TIMEOUT_MS = 45000
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
    `WINOPEN|${PROGRAM_WINDOW}`,
    `WEBENSURE|${WINDOW}|${screen.listMenuItem}|${screen.cadencierTab}`,
    `WEBCLICK|${WINDOW}|${screen.listMenuItem}`,
    `WEBWAIT|${WINDOW}|${screen.nameFilter}||${PAGE_TIMEOUT_MS}`,
  ]
  for (const code of codes) {
    const label = `mea-${code}`
    out.push(`# Kod MEA ${code}`)
    for (let row = 1; row <= MEA_MAX_FILES; row++) {
      const typed = `${label}-${row}`
      out.push(
        `# Plik ${row}`,
        // Po Powrót w filtrze zostaje ten sam kod, a WEBIFROW i tak liczy tylko wiersze z kodem: kod wpisujemy tylko,
        // gdy w polu jest inny (pasuje = idzie do WEBKEY, nie pasuje = przeskok za nie).
        `WEBIFVALUE|${WINDOW}|${screen.nameFilter}|^(?!${code}$)|${typed}|${FILTER_CHECK_MS}`,
        `WEBKEY|${WINDOW}|${screen.nameFilter}|^a${code}{Enter}`,
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
        // Raport integracji otwiera się w Acrobacie po 2-3 s; ekran po integracji nie ma już przycisku Integracja,
        // więc najpierw PDF (WINCLOSEALL czeka, aż się pojawi i ustoi), potem Powrót, gdy już jest.
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
