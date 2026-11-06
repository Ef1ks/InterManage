/** Weryfikacja piekarni (uprawnienie jak wprowadzanie danych, do tego bony i faktury). */
export const bakeryPath = '/weryfikacja-piekarni'
export const bakeryCheckPath = (id: string) => `${bakeryPath}/${id}`
