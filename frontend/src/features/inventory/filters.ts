/**
 * Filtry listy w Magazynie i produktach. W adresie po polsku (`stan=dodatni|zero|ujemny`, `stan_od`, `stan_do`,
 * `rot_od`, `rot_do`, `nom` powtarzane, `cadencier=tak|nie`, `sortuj=itm8|stan|rotacja|cena`,
 * `kierunek=malejaco`), w API po angielsku. Domyślne wartości nie trafiają do adresu.
 */
export type StockSign = 'all' | 'positive' | 'zero' | 'negative'
export type CadencierFilter = 'all' | 'with' | 'without'
export type ProductSort = 'name' | 'itm8' | 'stock' | 'rotation' | 'price'
export type SortDirection = 'asc' | 'desc'

export type ProductFilters = {
  stock: StockSign
  stockFrom: string
  stockTo: string
  rotationFrom: string
  rotationTo: string
  nomenclatures: string[]
  cadencier: CadencierFilter
  sort: ProductSort
  direction: SortDirection
}

const STOCK_PARAM: Record<StockSign, string | null> = {
  all: null,
  positive: 'dodatni',
  zero: 'zero',
  negative: 'ujemny',
}
const CADENCIER_PARAM: Record<CadencierFilter, string | null> = { all: null, with: 'tak', without: 'nie' }
const SORT_PARAM: Record<ProductSort, string | null> = {
  name: null,
  itm8: 'itm8',
  stock: 'stan',
  rotation: 'rotacja',
  price: 'cena',
}

const fromParam = <T extends string>(map: Record<T, string | null>, value: string | null, fallback: T): T =>
  (Object.keys(map) as T[]).find((key) => map[key] != null && map[key] === value) ?? fallback

export function readFilters(params: URLSearchParams): ProductFilters {
  return {
    stock: fromParam(STOCK_PARAM, params.get('stan'), 'all'),
    stockFrom: params.get('stan_od') ?? '',
    stockTo: params.get('stan_do') ?? '',
    rotationFrom: params.get('rot_od') ?? '',
    rotationTo: params.get('rot_do') ?? '',
    nomenclatures: params.getAll('nom'),
    cadencier: fromParam(CADENCIER_PARAM, params.get('cadencier'), 'all'),
    sort: fromParam(SORT_PARAM, params.get('sortuj'), 'name'),
    direction: params.get('kierunek') === 'malejaco' ? 'desc' : 'asc',
  }
}

/** Zapisuje filtry w adresie, nie ruszając pozostałych parametrów (wyszukiwanie, wybrany artykuł). */
export function writeFilters(params: URLSearchParams, filters: ProductFilters): URLSearchParams {
  const next = new URLSearchParams(params)
  const set = (key: string, value: string | null) => (value ? next.set(key, value) : next.delete(key))
  set('stan', STOCK_PARAM[filters.stock])
  set('stan_od', filters.stockFrom.trim() || null)
  set('stan_do', filters.stockTo.trim() || null)
  set('rot_od', filters.rotationFrom.trim() || null)
  set('rot_do', filters.rotationTo.trim() || null)
  next.delete('nom')
  filters.nomenclatures.forEach((code) => next.append('nom', code))
  set('cadencier', CADENCIER_PARAM[filters.cadencier])
  set('sortuj', SORT_PARAM[filters.sort])
  set('kierunek', filters.direction === 'desc' ? 'malejaco' : null)
  return next
}

export const EMPTY_FILTERS: ProductFilters = readFilters(new URLSearchParams())

/** Ile filtrów zawęża listę (sortowanie się nie liczy). */
export function activeFilterCount(filters: ProductFilters) {
  return [
    filters.stock !== 'all',
    filters.stockFrom.trim() !== '' || filters.stockTo.trim() !== '',
    filters.rotationFrom.trim() !== '' || filters.rotationTo.trim() !== '',
    filters.nomenclatures.length > 0,
    filters.cadencier !== 'all',
  ].filter(Boolean).length
}

/** Liczba z pola ("1,5" też); niepoprawna albo pusta = bez granicy. */
const number = (value: string) => {
  const parsed = Number(value.trim().replace(',', '.'))
  return value.trim() !== '' && Number.isFinite(parsed) ? String(parsed) : null
}

/** Parametry zapytania do API. */
export function filterParams(filters: ProductFilters): URLSearchParams {
  const params = new URLSearchParams()
  const set = (key: string, value: string | null) => value != null && params.set(key, value)
  set('stock', filters.stock === 'all' ? null : filters.stock.toUpperCase())
  set('stockFrom', number(filters.stockFrom))
  set('stockTo', number(filters.stockTo))
  set('rotationFrom', number(filters.rotationFrom))
  set('rotationTo', number(filters.rotationTo))
  filters.nomenclatures.forEach((code) => params.append('nomenclatures', code))
  set('cadencier', filters.cadencier === 'all' ? null : filters.cadencier.toUpperCase())
  set('sort', filters.sort === 'name' ? null : filters.sort)
  set('direction', filters.direction === 'desc' ? 'desc' : null)
  return params
}
