/** „1 osoba”, „3 osoby”, „5 osób”. */
export const people = (count: number) =>
  `${count} ${count === 1 ? 'osoba' : [2, 3, 4].includes(count % 10) && ![12, 13, 14].includes(count % 100) ? 'osoby' : 'osób'}`
