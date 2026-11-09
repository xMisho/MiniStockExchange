import { formatPrice } from '../utils/format'

export function PriceValue({ value }: { value: number | null }) {
  return <span className="price-value">{formatPrice(value)}</span>
}
