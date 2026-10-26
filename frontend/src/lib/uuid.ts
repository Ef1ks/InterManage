const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** Identyfikatory z API to UUID; zapisy w przeglądarce sprzed tej zmiany (liczby) pomijamy przy odczycie. */
export function isUuid(value: unknown): value is string {
  return typeof value === 'string' && UUID.test(value)
}
