/**
 * Kolory wierszy (pełne, bo kolumna EAN jest przyklejona i nie może prześwitywać). Mocne odcienie z ciemnym tekstem:
 * rodzaje wierszy muszą się wyraźnie różnić na zwykłym monitorze.
 */
export const ROW_TINTS = {
  known: 'bg-slate-300 text-slate-950 dark:bg-slate-700 dark:text-slate-50',
  changed: 'bg-orange-200 text-orange-950 dark:bg-orange-900 dark:text-orange-50',
  changedCell: 'bg-orange-400 text-orange-950 dark:bg-orange-700 dark:text-orange-50',
  new: 'bg-violet-200 text-violet-950 dark:bg-violet-900 dark:text-violet-50',
}

/** Kolory komórek nomenklatury według pewności propozycji. */
export const NOMENCLATURE_TINTS: Record<string, string> = {
  CERTAIN: 'bg-emerald-300 text-emerald-950 dark:bg-emerald-800 dark:text-emerald-50',
  AGREED: 'bg-sky-300 text-sky-950 dark:bg-sky-800 dark:text-sky-50',
  TO_CHECK: 'bg-amber-300 text-amber-950 dark:bg-amber-700 dark:text-amber-50',
}
