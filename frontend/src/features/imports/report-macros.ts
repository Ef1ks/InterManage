import { PERIOD_REPORT_MONTHS, REPORT_TITLES, partPath, type MacroReport } from '@/features/imports/import-sources'
import { addDays, toIsoDate, todayIso } from '@/lib/dates'
import { mercalysOpenLine } from '@/lib/mercalys-login'

/*
 * Makra dla odtwarzacza (Alt+E, od wersji 46): raport z Mercalys otwarty w Excelu i zapisany poleceniem EXCELSAVE
 * pod ścieżką z ustawień importu, skąd bierze go „Odśwież dane”. Raport, którego ekranów jeszcze nie znamy, makro
 * otwiera z pomocą pracownika: pyta (ASK), a po „Tak” czeka na Excel.
 */

const WINDOW = 'Mercalys'
const PROGRAM_WINDOW = 'Mercalys ahk_exe Mercalys.exe'
const PAGE_TIMEOUT_MS = 30_000
/** Ile czeka makro na Excel z jednym raportem: kartoteka i sprzedaż ładują się kilka minut. */
const EXCEL_WAIT_MS = 600_000

/** Artykuł → Wydruk artykułu → Wydruk Ustawialny (ar-e027), Excel → WG ITM8 (ar-e054) → Zatwierdź. */
const PRINT_SCREEN = {
  articleTab: 'mnTp_ARTICLE',
  printMenu: 'mnTv_ARTICLE_EditionArticle',
  printItem: 'mnTv_ARTICLE_ar-e027_EditionParametrable',
  /** Numer wydruku ustawialnego (lista do wpisania, 2 cyfry); po Tab Mercalys wczytuje jego ustawienia. */
  numberField: 'lstZ1_NumeroEdition_TxtBox',
  /** Tytuł wczytanego wydruku: pusty na świeżym ekranie, więc po nim widać, że wydruk się wczytał. */
  titleField: 'txtZ4_Titre1_TxtBox',
  excelButton: 'btnB4_Excel_LnkBouton',
  /** Ekran wyboru listy (ar-e054): przycisk listy po tekście, bo numer pozycji w liście może się zmienić. */
  listPage: 'ar-e054',
  byItm8: 'WG ITM8',
  itm8FromField: 'txtZ1_ItmMin_TxtBox',
  approveButton: 'btnB2_Valider_LnkBouton',
  /** „Innych list....” na ekranie wyboru listy: lista wszystkich list artykułów ze stronami. */
  otherLists: 'lkbAutres_LinkAviso',
  listNumberField: 'txtZ1_NumListe_TxtBox',
}

/** Artykuł → Karta EAN: od razu wybór listy (ar-e054), „WG ITM8” → Zatwierdź → tabela kodów (ar-e056) → Excel. */
const EAN_SCREEN = {
  articleTab: 'mnTp_ARTICLE',
  menuItem: 'mnTv_ARTICLE_ar-e002_FicheEan',
  listPage: 'ar-e054',
  byItm8: 'WG ITM8',
  itm8FromField: 'txtZ1_ItmMin_TxtBox',
  approveButton: 'btnB2_Valider_LnkBouton',
  tablePage: 'ar-e056',
  excelButton: 'btnB5_Excel_LnkBouton',
}

/** Taryfa → Lista aktualnych promocji (ar-e079): data końca do = dziś + 2 lata, Zatwierdź, PDF w Acrobacie. */
const PROMOTIONS_SCREEN = {
  tariffTab: 'mnTp_TARIF',
  menuItem: 'mnTv_TARIF_ar-e079_ListeDesPromosEnCours',
  endToField: 'txdZ2_DateFinMax_TxtBox',
  approveButton: 'btnB2_Valider_LnkBouton',
}

/**
 * Pilotaż → Statystyki Eksperta → Wejścia/wyjścia → „Wejścia/Wyjścia” (SaisieParametre REQ_ENTREES_SORTIES):
 * poziom szczegółu 1, zakres 2 (wg dnia), ruch 3, Zatwierdź parametry, daty od-do, Wykonać, wynik → Format Excel.
 */
const SALES_SCREEN = {
  pilotageTab: 'mnTp_PILOTAGE',
  expertMenu: 'mnTv_PILOTAGE_StatistiquesStandard',
  menuItem: 'mnTv_PILOTAGE_selectionstatistique_EntreesSortiesStandard',
  /** „Wejścia/Wyjścia” na liście statystyk; po id, bo tekst pozycji menu różni się tylko wielkością litery. */
  statistic: 'ListeStatistiques__ctl0_LinkButtonListe_LinkAviso',
  detailLevel: '_ctl11_ListeDeroulanteMP1_TxtBox',
  scope: '_ctl12_ListeDeroulanteMP1_TxtBox',
  movement: '_ctl13_ListeDeroulanteMP1_TxtBox',
  approveParameters: 'ButtonAvisoPar__LnkBouton',
  dateFrom: '_ctl16_TextEditDateDattyp1Min_TxtBox',
  dateTo: '_ctl16_TextEditDateDattyp1Max_TxtBox',
  runButton: 'ButtonAvisoValider__LnkBouton',
  resultPage: 'AfficheResultat.aspx',
  excelButton: 'ButtonAvisoForexc__LnkBouton',
}

/** Zestawienie sprzedaży za kilka dni liczy się w Mercalys długo. */
const STATISTIC_TIMEOUT_MS = 600_000

/** Pilotaż → Stany → Raport Ruchów Mag. wg dokumentów (ve-e048): data min i max, Zatwierdź, od razu Excel. */
const DOCUMENTS_SCREEN = {
  pilotageTab: 'mnTp_PILOTAGE',
  statesMenu: 'mnTv_PILOTAGE_Etats',
  menuItem: 'mnTv_VENTE_ve-e048_rapportMouvenementEnFonctionDocuments',
  dateFrom: 'txdZ23_DateMin_TxtBox',
  dateTo: 'txdZ25_DateMax_TxtBox',
  approveButton: 'btnB2_Valider_LnkBouton',
}

/** Dane sklepu potrzebne makrom w chwili kopiowania. */
export type ReportMacroContext = {
  /** Ostatni dzień sprzedaży w bazie (RRRR-MM-DD); null = brak sprzedaży, undefined = jeszcze nie wiadomo. */
  lastSaleDate?: string | null
  /** Ostatni dzień objęty wgranymi raportami dokumentów (najpóźniejsze „do” z importów); null = brak. */
  lastDocumentDate?: string | null
  /** Dni objęte wgranymi raportami (połączone okresy z serwera); z dziur makro drukuje zaległe raporty. */
  salesCovered?: Period[]
  documentsCovered?: Period[]
}

/** Od tego dnia makro uzupełnia dziury w sprzedaży i dokumentach. */
export const HISTORY_START = '2024-01-01'

/** Najpóźniejszy koniec okresu z wgranych raportów dokumentów. */
export function lastDocumentDate(imports: { periodTo: string | null }[] | undefined): string | null {
  return (imports ?? []).reduce<string | null>(
    (latest, item) => (item.periodTo && (!latest || item.periodTo > latest) ? item.periodTo : latest),
    null,
  )
}

/**
 * Okres raportu dokumentów: od dnia przed ostatnim dniem z raportów (najpóźniej od przedwczoraj) do jutra. Daty
 * w tym raporcie bywają przesunięte, więc zakres jest celowo szerszy; ponowne wgranie dokumentów tylko je uzupełnia.
 */
export function documentsPeriod(context: ReportMacroContext, today = todayIso()) {
  const yesterday = addDays(today, -1)
  const last = context.lastDocumentDate && context.lastDocumentDate < yesterday ? context.lastDocumentDate : yesterday
  return { from: addDays(last, -1), to: addDays(today, 1) }
}

/**
 * Okres raportu sprzedaży: od pierwszego dnia, którego nie ma w bazie, najpóźniej od wczoraj, do dziś. Dzień
 * wygenerowania raportu nie jest zapisywany (sklep jeszcze sprzedaje), więc jutro raport weźmie go jeszcze raz.
 */
export function salesPeriod(context: ReportMacroContext, today = todayIso()) {
  const yesterday = addDays(today, -1)
  const firstMissing = context.lastSaleDate ? addDays(context.lastSaleDate, 1) : yesterday
  return { from: firstMissing < yesterday ? firstMissing : yesterday, to: today }
}

type Period = { from: string; to: string }

/** RRRR-MM-DD plus miesiące; dzień ponad koniec miesiąca przechodzi na ostatni (31.01 + 1 = 28.02). */
function addMonthsToDate(iso: string, months: number) {
  const [year, month, day] = iso.split('-').map(Number)
  const last = new Date(year, month - 1 + months + 1, 0)
  return toIsoDate(new Date(last.getFullYear(), last.getMonth(), Math.min(day, last.getDate())))
}

/**
 * Okres podzielony na części najwyżej po `months` miesięcy (limit wydruku w Mercalys), od najstarszej: makro zapisuje
 * je po kolei, a ostatnia, najnowsza ma ścieżkę z ustawień.
 */
export function splitPeriod(period: Period, months: number): Period[] {
  const parts: Period[] = []
  let from = period.from
  while (from <= period.to) {
    const end = addDays(addMonthsToDate(from, months), -1)
    const to = end < period.to ? end : period.to
    parts.push({ from, to })
    from = addDays(to, 1)
  }
  return parts
}

/** Dni od `from` do `to` (włącznie), których nie obejmuje żaden okres z `covered`, jako okresy od najstarszego. */
export function missingPeriods(covered: Period[], from: string, to: string): Period[] {
  const gaps: Period[] = []
  let next = from
  for (const range of [...covered].sort((a, b) => a.from.localeCompare(b.from))) {
    if (next > to) break
    if (range.to < next) continue
    if (range.from > next) {
      const end = addDays(range.from, -1)
      gaps.push({ from: next, to: end < to ? end : to })
    }
    next = addDays(range.to, 1)
  }
  if (next <= to) gaps.push({ from: next, to })
  return gaps
}

/**
 * Części okresu raportu (sprzedaż, dokumenty), od najstarszej: dziury od HISTORY_START w okresach wgranych raportów,
 * potem bieżący okres, każde podzielone do limitu Mercalys. Raport bez okresu to jedna część bez dat.
 */
export function periodParts(kind: MacroReport, context: ReportMacroContext): (Period | null)[] {
  const months = PERIOD_REPORT_MONTHS[kind]
  if (!months) return [null]
  const current = kind === 'SALES' ? salesPeriod(context) : documentsPeriod(context)
  const covered = (kind === 'SALES' ? context.salesCovered : context.documentsCovered) ?? []
  const gaps = missingPeriods(covered, HISTORY_START, addDays(current.from, -1))
  return [...gaps, current].flatMap((period) => splitPeriod(period, months))
}

/** RRRR-MM-DD jako DDMMRRRR (pole daty w Mercalys) i wzorzec wartości po wpisaniu (DD/MM/RRRR). */
const typedDate = (iso: string) => `${iso.slice(8, 10)}${iso.slice(5, 7)}${iso.slice(0, 4)}`
const datePattern = (iso: string) =>
  `^${iso.slice(8, 10)}/?${iso.slice(5, 7)}/?(${iso.slice(0, 2)})?${iso.slice(2, 4)}$`

/** Raporty drukowane z Mercalys tylko do PDF (zapis PDFSAVE zamiast EXCELSAVE). */
const PDF_REPORTS: readonly MacroReport[] = ['PROMOTIONS']

/** Najstarszy odtwarzacz, który wykona makro z tymi raportami (PDFSAVE w obecnej postaci od wersji 49). */
export function macroPlayerVersion(kinds: MacroReport[]) {
  return kinds.some((kind) => PDF_REPORTS.includes(kind)) ? '0.49' : '0.46'
}

/** Dziś + 2 lata jako DDMMRRRR, a 29 lutego przechodzi na 28 (rok bez 29 lutego). */
function twoYearsAhead(today = new Date()) {
  const year = today.getFullYear() + 2
  const month = today.getMonth()
  const day = Math.min(today.getDate(), new Date(year, month + 1, 0).getDate())
  const pad = (value: number) => String(value).padStart(2, '0')
  return { day: pad(day), month: pad(month + 1), year: String(year) }
}

/** Tabela wszystkich kodów sklepu ładuje się dłużej niż zwykła strona. */
const TABLE_TIMEOUT_MS = 300_000

export type ReportMacroSettings = {
  path: string
  /** Numer wydruku ustawialnego w Mercalys (Baza danych). */
  printNumber?: string
  /** Numer listy artykułów w Mercalys (Obecne w cadencier). */
  listNumber?: string
}

/** Raporty, których wydruk w Mercalys ma numer ustawiany w ustawieniach importu. */
export const PRINT_NUMBER_REPORTS: readonly MacroReport[] = ['PRODUCTS', 'CADENCIER']

/** Raporty z listy artykułów („Innych list…”), której numer ustawia się w ustawieniach importu. */
export const LIST_NUMBER_REPORTS: readonly MacroReport[] = ['CADENCIER']

export function printNumberError(value: string): string | null {
  const number = value.trim()
  if (!number) return 'Wpisz numer wydruku ustawialnego z Mercalys'
  return /^\d{1,2}$/.test(number) ? null : 'Numer wydruku to 1 albo 2 cyfry'
}

export function listNumberError(value: string): string | null {
  const number = value.trim()
  if (!number) return 'Wpisz numer listy z Mercalys'
  return /^\d{1,4}$/.test(number) ? null : 'Numer listy to od 1 do 4 cyfr'
}

/** Kroki w Mercalys do chwili, gdy raport ładuje się do Excela; null = raport otwiera pracownik. */
function openSteps(kind: MacroReport, settings: ReportMacroSettings, period: Period | null): string[] | null {
  if (kind === 'PRODUCTS' || kind === 'CADENCIER') {
    const s = PRINT_SCREEN
    const number = settings.printNumber!.trim()
    const toLists = [
      `WEBENSURE|${WINDOW}|${s.printMenu}|${s.articleTab}`,
      `WEBOPEN|${WINDOW}|${s.printMenu}|${s.printItem}|${s.numberField}`,
      `WEBWAIT|${WINDOW}|${s.numberField}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${s.numberField}|^a${number}{Tab}`,
      `WEBVALUE|${WINDOW}|${s.numberField}|^0*${Number(number)}$|${PAGE_TIMEOUT_MS}`,
      `WEBVALUE|${WINDOW}|${s.titleField}|\\S|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${s.excelButton}`,
      `WEBPAGE|${WINDOW}|${s.listPage}|${s.byItm8}|${PAGE_TIMEOUT_MS}`,
    ]
    if (kind === 'PRODUCTS')
      return [
        ...toLists,
        `WEBCLICK|${WINDOW}|${s.byItm8}`,
        `WEBWAIT|${WINDOW}|${s.itm8FromField}||${PAGE_TIMEOUT_MS}`,
        `WEBCLICK|${WINDOW}|${s.approveButton}`,
      ]
    // Obecne w cadencier: „Innych list…”, numer listy z Enterem przewija listę list do jej strony, ale wiersza nie
    // zaznacza (pole wraca do 0, Zatwierdź nic nie otwiera), więc jak ręcznie: dwuklik w wiersz z numerem listy.
    const list = String(Number(settings.listNumber!.trim()))
    return [
      ...toLists,
      `WEBCLICK|${WINDOW}|${s.otherLists}`,
      `WEBWAIT|${WINDOW}|${s.listNumberField}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${s.listNumberField}|^a${list}{Enter}`,
      `WEBDBLROW|${WINDOW}|*|${list}|${PAGE_TIMEOUT_MS}`,
    ]
  }
  if (kind === 'PRODUCT_CODES') {
    const s = EAN_SCREEN
    return [
      `WEBENSURE|${WINDOW}|${s.menuItem}|${s.articleTab}`,
      `WEBCLICK|${WINDOW}|${s.menuItem}`,
      `WEBPAGE|${WINDOW}|${s.listPage}|${s.byItm8}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${s.byItm8}`,
      `WEBWAIT|${WINDOW}|${s.itm8FromField}||${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${s.approveButton}`,
      `WEBPAGE|${WINDOW}|${s.tablePage}|${s.excelButton}|${TABLE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${s.excelButton}`,
    ]
  }
  if (kind === 'PROMOTIONS') {
    const s = PROMOTIONS_SCREEN
    const end = twoYearsAhead()
    return [
      `WEBENSURE|${WINDOW}|${s.menuItem}|${s.tariffTab}`,
      `WEBCLICK|${WINDOW}|${s.menuItem}`,
      `WEBWAIT|${WINDOW}|${s.endToField}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${s.endToField}|^a${end.day}${end.month}${end.year}{Tab}`,
      `WEBVALUE|${WINDOW}|${s.endToField}|^${end.day}/?${end.month}/?(${end.year.slice(0, 2)})?${end.year.slice(2)}$|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${s.approveButton}`,
    ]
  }
  if (kind === 'SALES') {
    const s = SALES_SCREEN
    if (!period) return null
    const choose = (field: string, value: string) => [
      `WEBKEY|${WINDOW}|${field}|^a${value}{Tab}`,
      `WEBVALUE|${WINDOW}|${field}|^${value}$|${PAGE_TIMEOUT_MS}`,
    ]
    return [
      `WEBENSURE|${WINDOW}|${s.expertMenu}|${s.pilotageTab}`,
      `WEBOPEN|${WINDOW}|${s.expertMenu}|${s.menuItem}|${s.statistic}`,
      `WEBCLICK|${WINDOW}|${s.statistic}`,
      `WEBWAIT|${WINDOW}|${s.detailLevel}||${PAGE_TIMEOUT_MS}`,
      ...choose(s.detailLevel, '1'),
      ...choose(s.scope, '2'),
      ...choose(s.movement, '3'),
      `WEBCLICK|${WINDOW}|${s.approveParameters}`,
      `WEBWAIT|${WINDOW}|${s.dateFrom}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${s.dateFrom}|^a${typedDate(period.from)}{Tab}`,
      `WEBVALUE|${WINDOW}|${s.dateFrom}|${datePattern(period.from)}|${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${s.dateTo}|^a${typedDate(period.to)}{Tab}`,
      `WEBVALUE|${WINDOW}|${s.dateTo}|${datePattern(period.to)}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${s.runButton}`,
      `WEBPAGE|${WINDOW}|${s.resultPage}|${s.excelButton}|${STATISTIC_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${s.excelButton}`,
    ]
  }
  if (kind === 'DOCUMENTS') {
    const s = DOCUMENTS_SCREEN
    if (!period) return null
    return [
      `WEBENSURE|${WINDOW}|${s.statesMenu}|${s.pilotageTab}`,
      `WEBOPEN|${WINDOW}|${s.statesMenu}|${s.menuItem}|${s.dateFrom}`,
      `WEBWAIT|${WINDOW}|${s.dateFrom}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${s.dateFrom}|^a${typedDate(period.from)}{Tab}`,
      `WEBVALUE|${WINDOW}|${s.dateFrom}|${datePattern(period.from)}|${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${s.dateTo}|^a${typedDate(period.to)}{Tab}`,
      `WEBVALUE|${WINDOW}|${s.dateTo}|${datePattern(period.to)}|${PAGE_TIMEOUT_MS}`,
      `WEBCLICK|${WINDOW}|${s.approveButton}`,
    ]
  }
  return null
}

/** Czy makro samo otwiera raport w Mercalys (bez pytania pracownika). */
export const opensItself = (kind: MacroReport) =>
  openSteps(kind, { path: '', printNumber: '0', listNumber: '0' }, { from: todayIso(), to: todayIso() }) !== null

/** Przykładowa ścieżka w podpowiedzi pola zapisu. */
export const pathExample = (kind: MacroReport) =>
  PDF_REPORTS.includes(kind) ? 'P:\\Raporty\\taryfy.pdf' : 'P:\\Raporty\\baza.xlsx'

/** Czy raport ma wszystko, czego makro potrzebuje (ścieżka sprawdzana osobno). */
export function macroSettingsReady(kind: MacroReport, settings: ReportMacroSettings) {
  return (
    (!PRINT_NUMBER_REPORTS.includes(kind) || printNumberError(settings.printNumber ?? '') === null) &&
    (!LIST_NUMBER_REPORTS.includes(kind) || listNumberError(settings.listNumber ?? '') === null)
  )
}

/** Jedno makro na podane raporty, po kolei; na końcu Mercalys wraca na pasek zadań. */
export function buildReportSaveMacro(
  reports: { kind: MacroReport; settings: ReportMacroSettings }[],
  context: ReportMacroContext,
): string {
  const lines = [
    `# Intermanage: zapis raportów z Mercalys do folderów importu (odtwarzacz od wersji ${macroPlayerVersion(reports.map(({ kind }) => kind))})`,
    mercalysOpenLine(PROGRAM_WINDOW),
  ]
  reports.forEach(({ kind, settings }, index) => {
    const title = REPORT_TITLES[kind]
    const step = reports.length > 1 ? ` (${index + 1} z ${reports.length})` : ''
    const parts = periodParts(kind, context)
    parts.forEach((period, part) => {
      const steps = openSteps(kind, settings, period)
      const partLabel =
        parts.length > 1 && period ? `, część ${part + 1} z ${parts.length}: ${period.from} - ${period.to}` : ''
      lines.push(`# ${title}${step}${partLabel}`)
      if (steps) lines.push(...steps)
      else
        lines.push(
          `ASK|${title}${step}: otwórz ten raport w Mercalys. Kliknij Tak, a potem przycisk Excel w Mercalys. Nie kończy makro.`,
        )
      const path = partPath(settings.path.trim(), part + 1, parts.length)
      lines.push(PDF_REPORTS.includes(kind) ? `PDFSAVE|${path}|${EXCEL_WAIT_MS}` : `EXCELSAVE|${path}|${EXCEL_WAIT_MS}`)
    })
  })
  lines.push(`WINMIN|${PROGRAM_WINDOW}`)
  return lines.join('\r\n') + '\r\n'
}
