const priceFormat = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const orderPriceFormat = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 })
const counterFormat = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 })

export function formatPrice(value: number | null): string {
  return value === null ? '—' : priceFormat.format(value)
}

export function formatChange(value: number | null): string {
  return value === null ? '—' : `${value > 0 ? '+' : ''}${priceFormat.format(value)}`
}

export function formatCount(value: number): string {
  return counterFormat.format(value)
}

export function formatUtcTime(value: string): string {
  return `${new Date(value).toISOString().slice(11, 19)} UTC`
}

export function formatUtcDateTime(value: string): string {
  return `${new Date(value).toISOString().slice(0, 19).replace('T', ' ')} UTC`
}

export function formatOrderPrice(value: number | null): string {
  return value === null ? '—' : orderPriceFormat.format(value)
}
