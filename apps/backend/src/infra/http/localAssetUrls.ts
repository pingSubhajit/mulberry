/** Prefixes every string under `value` that starts with `prefix` with `origin`, leaving all else alone. */
export function absolutizeLocalUrls<T>(value: T, prefix: string, origin: string): T {
  if (typeof value === "string") {
    return (value.startsWith(prefix) ? `${origin}${value}` : value) as T
  }
  if (Array.isArray(value)) return value.map((item) => absolutizeLocalUrls(item, prefix, origin)) as T
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, absolutizeLocalUrls(item, prefix, origin)]),
    ) as T
  }
  return value
}
