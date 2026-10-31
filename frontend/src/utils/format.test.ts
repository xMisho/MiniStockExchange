import { expect, it } from 'vitest'
import { formatChange, formatCount, formatPrice } from './format'

it('keeps missing prices distinct from actual zero values', () => {
  expect(formatPrice(null)).toBe('—')
  expect(formatPrice(0)).toBe('0.00')
  expect(formatCount(0)).toBe('0')
})

it('formats signs, two decimal prices and grouped counters consistently', () => {
  expect(formatChange(null)).toBe('—')
  expect(formatChange(2.48)).toBe('+2.48')
  expect(formatChange(-2.48)).toBe('-2.48')
  expect(formatChange(0)).toBe('0.00')
  expect(formatPrice(101.876)).toBe('101.88')
  expect(formatCount(12430)).toBe('12,430')
})
