import { cleanup, render } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { CandlestickCanvas } from './CandlestickCanvas'

const mocks = vi.hoisted(() => ({ setData: vi.fn(), remove: vi.fn(), fitContent: vi.fn(), create: vi.fn() }))
vi.mock('lightweight-charts', () => ({ CandlestickSeries: {}, ColorType: { Solid: 'solid' }, createChart: mocks.create }))
afterEach(() => { cleanup(); vi.resetAllMocks() })
const candles = [{ startTime: '2026-10-31T14:32:00Z', open: 100, high: 105, low: 99, close: 102, volume: 20, tradeCount: 3 }]
it('maps OHLC to epoch seconds, sizes responsively and removes the chart on unmount', () => {
  mocks.create.mockReturnValue({ addSeries: () => ({ setData: mocks.setData }), timeScale: () => ({ fitContent: mocks.fitContent }), remove: mocks.remove })
  const view = render(<CandlestickCanvas symbol="AAPL" candles={candles} onError={vi.fn()} />)
  expect(mocks.setData).toHaveBeenCalledWith([{ time: Date.parse(candles[0].startTime) / 1000, open: 100, high: 105, low: 99, close: 102 }])
  const options = mocks.create.mock.calls[0][1]
  expect(options.autoSize).toBe(true)
  expect(options.layout.attributionLogo).toBe(true)
  expect(options.localization.timeFormatter(Date.parse(candles[0].startTime) / 1000)).toBe('2026-10-31 14:32:00 UTC')
  expect(mocks.fitContent).toHaveBeenCalledOnce()
  view.unmount()
  expect(mocks.remove).toHaveBeenCalledOnce()
})
it('reports chart initialization failure without crashing the dashboard', () => {
  mocks.create.mockImplementation(() => { throw new Error('Canvas unavailable') })
  const onError = vi.fn()
  render(<CandlestickCanvas symbol="AAPL" candles={candles} onError={onError} />)
  expect(onError).toHaveBeenCalledOnce()
})
