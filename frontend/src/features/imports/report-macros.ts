import {
  PERIOD_REPORT_MONTHS,
  REPORT_TITLES,
  partLengthOf,
  partPath,
  type MacroReport,
  type PartLength,
} from '@/features/imports/import-sources'
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
/** Raport sprzedaży i dokumentów za dłuższy okres Mercalys potrafi przygotowywać dużo dłużej. */
const LONG_EXCEL_WAIT_MS = 1_800_000
const LONG_EXCEL_REPORTS: MacroReport[] = ['SALES', 'DOCUMENTS']

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
  /** Powrót jest na wyniku zawsze, także pustym (bez sprzedaży w okresie nie ma przycisku Excel). */
  returnButton: 'ButtonAvisoRetour__LnkBouton',
  excelButton: 'ButtonAvisoForexc__LnkBouton',
}

/** Ile po wczytaniu wyniku czeka makro na przycisk Excel, zanim uzna wynik za pusty. */
const EMPTY_RESULT_WAIT_MS = 20_000

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
  /** Dzień otwarcia sklepu (RRRR-MM-DD): wcześniejszych dni makro nie drukuje. */
  openedOn?: string | null
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

/** O tyle dni wcześniej zaczyna się każda dziura w raportach dokumentów (bezpieczny zakład z poprzednim okresem). */
const DOCUMENTS_OVERLAP_DAYS = 2

/**
 * Bieżący okres raportu dokumentów: od przedwczoraj do jutra. Daty w tym raporcie bywają przesunięte, więc zakres
 * jest celowo szerszy; ponowne wgranie dokumentów tylko je uzupełnia. Starsze dni bierze makro tylko z dziur
 * w okresach wgranych raportów (`periodParts`), więc wgrany wcześniej okres nie jest drukowany drugi raz
 * (dawniej bieżący okres zaczynał się od ostatniego dnia z raportów i przy zaległości obejmował wszystko do dziś).
 */
export function documentsPeriod(_context: ReportMacroContext, today = todayIso()) {
  return { from: addDays(today, -2), to: addDays(today, 1) }
}

/**
 * Bieżący okres raportu sprzedaży: wczoraj i dziś. Dzień wygenerowania raportu nie jest zapisywany (sklep jeszcze
 * sprzedaje), więc jutro raport weźmie go jeszcze raz. Starsze dni, jak w dokumentach, tylko z dziur w okresach
 * wgranych raportów.
 */
export function salesPeriod(_context: ReportMacroContext, today = todayIso()) {
  return { from: addDays(today, -1), to: today }
}

type Period = { from: string; to: string }

/** RRRR-MM-DD plus miesiące; dzień ponad koniec miesiąca przechodzi na ostatni (31.01 + 1 = 28.02). */
function addMonthsToDate(iso: string, months: number) {
  const [year, month, day] = iso.split('-').map(Number)
  const last = new Date(year, month - 1 + months + 1, 0)
  return toIsoDate(new Date(last.getFullYear(), last.getMonth(), Math.min(day, last.getDate())))
}

/**
 * Okres podzielony na części najwyżej po `length` (tygodnie albo miesiące, do limitu wydruku w Mercalys), od
 * najstarszej: makro zapisuje je po kolei, a ostatnia, najnowsza ma ścieżkę z ustawień.
 */
export function splitPeriod(period: Period, length: PartLength): Period[] {
  const parts: Period[] = []
  let from = period.from
  while (from <= period.to) {
    const end =
      length.unit === 'week' ? addDays(from, length.count * 7 - 1) : addDays(addMonthsToDate(from, length.count), -1)
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
 * potem bieżący okres, każde podzielone na części długości z ustawień (domyślnie limit Mercalys). Raport bez okresu
 * to jedna część bez dat.
 */
export function periodParts(kind: MacroReport, context: ReportMacroContext, partLength?: string): (Period | null)[] {
  if (!PERIOD_REPORT_MONTHS[kind]) return [null]
  const length = partLengthOf(kind, partLength)
  const current = kind === 'SALES' ? salesPeriod(context) : documentsPeriod(context)
  const covered = (kind === 'SALES' ? context.salesCovered : context.documentsCovered) ?? []
  // Przed otwarciem sklepu nie było sprzedaży ani dokumentów: te dni dawałyby puste raporty.
  const start = context.openedOn && context.openedOn > HISTORY_START ? context.openedOn : HISTORY_START
  // Dokumenty: dziura zaczyna się dwa dni wcześniej (po 31.01 od 30.01), jak bieżący okres, bo daty w tym raporcie
  // bywają przesunięte; ponowne wgranie tych dni tylko je uzupełnia. Nie przed początkiem i nie przed poprzednią dziurą.
  const overlap = kind === 'DOCUMENTS' ? DOCUMENTS_OVERLAP_DAYS : 0
  const gaps = missingPeriods(covered, start, addDays(current.from, -1)).map((gap, index, all) => {
    const earliest = index > 0 ? addDays(all[index - 1].to, 1) : start
    const from = addDays(gap.from, -overlap)
    return { from: from < earliest ? earliest : from, to: gap.to }
  })
  const periods = [...gaps, { from: current.from < start ? start : current.from, to: current.to }]
  return periods.filter((period) => period.from <= period.to).flatMap((period) => splitPeriod(period, length))
}

/** RRRR-MM-DD jako DDMMRRRR (pole daty w Mercalys) i wzorzec wartości po wpisaniu (DD/MM/RRRR). */
const typedDate = (iso: string) => `${iso.slice(8, 10)}${iso.slice(5, 7)}${iso.slice(0, 4)}`
const datePattern = (iso: string) =>
  `^${iso.slice(8, 10)}/?${iso.slice(5, 7)}/?(${iso.slice(0, 2)})?${iso.slice(2, 4)}$`

/** Raporty drukowane z Mercalys tylko do PDF (zapis PDFSAVE zamiast EXCELSAVE). */
const PDF_REPORTS: readonly MacroReport[] = ['PROMOTIONS']

/**
 * Najstarszy odtwarzacz, który wykona makro z tymi raportami: WEBIFSEEN (pusty wynik sprzedaży) od 0.55, PDFSAVE
 * w obecnej postaci od 0.49.
 */
export function macroPlayerVersion(kinds: MacroReport[]) {
  if (kinds.includes('SALES')) return '0.55'
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
  /** Długość części zaległego okresu (sprzedaż, dokumenty); puste = limit Mercalys. */
  partLength?: string
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
function openSteps(
  kind: MacroReport,
  settings: ReportMacroSettings,
  period: Period | null,
  emptyLabel = 'pusty',
  continuing = false,
): string[] | null {
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
      // Okres bez sprzedaży (np. zamknięte święto) daje pusty wynik bez przycisku Excel: ta część nie ma pliku.
      `WEBPAGE|${WINDOW}|${s.resultPage}|${s.returnButton}|${STATISTIC_TIMEOUT_MS}`,
      `WEBIFSEEN|${WINDOW}|${s.excelButton}|${emptyLabel}|${EMPTY_RESULT_WAIT_MS}|Sprzedaż ${period.from} - ${period.to}: pusty wynik, bez pliku`,
      `WEBCLICK|${WINDOW}|${s.excelButton}`,
    ]
  }
  if (kind === 'DOCUMENTS') {
    const s = DOCUMENTS_SCREEN
    if (!period) return null
    // Kolejna część: po Zatwierdź formularz zostaje na ekranie (Excel otwiera się sam), więc menu nie klikamy.
    // Ponowny klik przeładowywał formularz z datami poprzedniej części, a pole daty było widać już na starej stronie:
    // makro wpisywało daty przed przeładowaniem i drukowało w kółko poprzednią część (06713, 19.11.2026).
    const open = continuing
      ? []
      : [
          `WEBENSURE|${WINDOW}|${s.statesMenu}|${s.pilotageTab}`,
          `WEBOPEN|${WINDOW}|${s.statesMenu}|${s.menuItem}|${s.dateFrom}`,
        ]
    return [
      ...open,
      `WEBWAIT|${WINDOW}|${s.dateFrom}||${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${s.dateFrom}|^a${typedDate(period.from)}{Tab}`,
      `WEBVALUE|${WINDOW}|${s.dateFrom}|${datePattern(period.from)}|${PAGE_TIMEOUT_MS}`,
      `WEBKEY|${WINDOW}|${s.dateTo}|^a${typedDate(period.to)}{Tab}`,
      `WEBVALUE|${WINDOW}|${s.dateTo}|${datePattern(period.to)}|${PAGE_TIMEOUT_MS}`,
      // Obie daty jeszcze raz tuż przed Zatwierdź: przeładowany w międzyczasie formularz zatrzyma makro.
      `WEBVALUE|${WINDOW}|${s.dateFrom}|${datePattern(period.from)}|${PAGE_TIMEOUT_MS}`,
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
    const parts = periodParts(kind, context, settings.partLength)
    parts.forEach((period, part) => {
      const emptyLabel = `pusty-${index + 1}-${part + 1}`
      const steps = openSteps(kind, settings, period, emptyLabel, part > 0)
      const partLabel =
        parts.length > 1 && period ? `, część ${part + 1} z ${parts.length}: ${period.from} - ${period.to}` : ''
      lines.push(`# ${title}${step}${partLabel}`)
      if (steps) lines.push(...steps)
      else
        lines.push(
          `ASK|${title}${step}: otwórz ten raport w Mercalys. Kliknij Tak, a potem przycisk Excel w Mercalys. Nie kończy makro.`,
        )
      const path = partPath(settings.path.trim(), part + 1, parts.length)
      const wait = LONG_EXCEL_REPORTS.includes(kind) ? LONG_EXCEL_WAIT_MS : EXCEL_WAIT_MS
      lines.push(PDF_REPORTS.includes(kind) ? `PDFSAVE|${path}|${wait}` : `EXCELSAVE|${path}|${wait}`)
      if (kind === 'SALES' && steps) lines.push(`LABEL|${emptyLabel}`)
    })
  })
  lines.push(`WINMIN|${PROGRAM_WINDOW}`)
  return lines.join('\r\n') + '\r\n'
}
