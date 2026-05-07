export function validDateOrNow(raw: string): Date {
  const parsed = new Date(raw)
  return Number.isNaN(parsed.getTime()) ? new Date() : parsed
}

export function isLocalDateString(value: string | null | undefined): value is string {
  return typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)
}

export function dateOnly(date: Date): string {
  return date.toISOString().slice(0, 10)
}

export function addDays(day: string, delta: number): string {
  const date = new Date(`${day}T00:00:00.000Z`)
  date.setUTCDate(date.getUTCDate() + delta)
  return dateOnly(date)
}

export function normalizeDateString(raw: string | Date | null): string | null {
  if (!raw) return null
  if (raw instanceof Date) {
    return raw.toISOString().slice(0, 10)
  }
  if (/^\d{4}-\d{2}-\d{2}/.test(raw)) {
    return raw.slice(0, 10)
  }
  const parsed = new Date(raw)
  if (!Number.isNaN(parsed.getTime())) {
    return parsed.toISOString().slice(0, 10)
  }
  return raw.slice(0, 10)
}

export function normalizeTimestampString(raw: string | Date | null): string | null {
  if (!raw) return null
  if (raw instanceof Date) return raw.toISOString()
  const parsed = new Date(raw)
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString()
}
