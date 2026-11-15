/** Strona modułu MEA (lista z portalu Grupy Muszkieterów; uprawnienie jak wprowadzanie danych). */
export const meaOnlinePath = '/mea'

/** Dokument MEA z ilościami do zamówienia (R1-R4). */
export const meaDocumentPath = (number: string) => `${meaOnlinePath}/${number}`
