import { useEffect, useRef } from 'react'
import { formatPrice } from '../utils/format'

export function PriceValue({ value }: { value: number | null }) {
  const element = useRef<HTMLSpanElement>(null)
  const previous = useRef<number | null>(null)
  useEffect(() => {
    const old = previous.current
    previous.current = value
    if (old === null || value === null || old === value || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
    const animation = element.current?.animate?.([
      { backgroundColor: value > old ? '#d3eddd' : '#f9dcd7' }, { backgroundColor: 'transparent' },
    ], { duration: 700, easing: 'ease-out' })
    return () => animation?.cancel()
  }, [value])
  return <span ref={element} className="price-value">{formatPrice(value)}</span>
}
