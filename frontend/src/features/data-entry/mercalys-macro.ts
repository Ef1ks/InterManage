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
  integration: 'mnTv_ACHAT_na-e014_IntegrationFichiersBl',
  blScreen: 'mnTv_ACHAT_ac-e082_GestionDesBl',
  orders: 'mnTv_ACHAT_Commande',
  orderEntry: 'mnTv_ACHAT_ac-e011_SaisieCommandeCompleteCmd',
} as const

const INTEGRATION_SCREEN = {
  confirmText: 'interacj',
  result: 'btnB1_Retour_LnkBouton',
} as const

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
      `WEBWAIT|${WINDOW}|${INTEGRATION_SCREEN.result}||${INTEGRATION_TIMEOUT_MS}`,
      `WEBOPEN|${WINDOW}|${MENU.directDeliveries}|${MENU.blScreen}`,
      `WEBWAIT|${WINDOW}|${bl.createCodeButton}||${PAGE_TIMEOUT_MS}`,
      `WEBROWS|${WINDOW}|${GRID}|${bl.orderColumn}|${bl.createCodeButton}|${bl.nextPageButton}|${documents.length}|${DIALOG}|${bl.confirmText}|${bl.confirmButton}`,
      `WEBOPEN|${WINDOW}|${MENU.orders}|${MENU.orderEntry}`,
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

/** Korekta od dostawcy: protokół (ve-e040) i jego szczegół (ve-e046). */
const CORRECTION_SCREEN = {
  stockTab: 'mnTp_STOCK',
  protocolMenuItem: 'mnTv_VENTE_ve-e040_GestionProtocole',
  typeField: 'lstZ2_TypeProtocole_TxtBox',
  /** 8 = korekta dostawy, błąd dostawcy. */
  supplierErrorType: '8',
  dueDateField: 'txdZ6_DateEcheance_TxtBox',
  approveButton: 'btnB4_Valider_LnkBouton',
  createText: 'Utworzy',
  detailButton: 'btnB3_Detail_LnkBouton',
  bonField: 'txtZ19_NumBL_TxtBox',
  closeButton: 'btnB3_Cloturer_LnkBouton',
  closeText: 'zatwierdzi',
} as const

/** Pierwsze okno po Zakończ znika, a drugie pojawia się razem z PDF-em. */
const BETWEEN_CLOSE_DIALOGS_MS = 2000

/** Nowy protokół z dzisiejszym terminem płatności; po zapisie szczegół otwiera się sam albo przyciskiem. */
export function correctionStartLines(today: string) {
  const screen = CORRECTION_SCREEN
  return [
    `WINOPEN|${PROGRAM_WINDOW}`,
    `WEBENSURE|${WINDOW}|${screen.protocolMenuItem}|${screen.stockTab}`,
    `WEBCLICK|${WINDOW}|${screen.protocolMenuItem}`,
    `WEBWAIT|${WINDOW}|${screen.typeField}||${PAGE_TIMEOUT_MS}`,
    `WEBKEY|${WINDOW}|${screen.typeField}|^a${screen.supplierErrorType}{Enter}`,
    `SLEEP|${AFTER_CONFIRM_SLEEP_MS}`,
    `WEBKEY|${WINDOW}|${screen.dueDateField}|^a${mercalysDate(today)}{Tab}`,
    `WEBVALUE|${WINDOW}|${screen.typeField}|^0*${screen.supplierErrorType}$`,
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
