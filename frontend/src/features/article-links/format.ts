/** ITM8 bez zer wiodących, jak na etykietach („0000099002446” → „99002446”). */
export const shortItm8 = (itm8: string) => itm8.replace(/^0+(?=\d)/, '')
