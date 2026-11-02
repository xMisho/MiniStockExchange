import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { CandlestickCanvas } from './CandlestickCanvas'

const mocks = vi.hoisted(() => ({ setData: vi.fn(), remove: vi.fn(), fitContent: vi.fn(), setRange: vi.fn(), create: vi.fn() }))
vi.mock('lightweight-charts', () => ({ CandlestickSeries: {}, LineSeries: {}, ColorType: { Solid: 'solid' }, createChart: mocks.create }))
afterEach(() => { cleanup(); vi.resetAllMocks() })
const candles = [{ startTime: '2026-10-31T14:32:00Z', open: 100, high: 105, low: 99, close: 102, volume: 20, tradeCount: 3 }]
it('maps OHLC to epoch seconds, sizes responsively and removes the chart on unmount', () => {
  mocks.create.mockReturnValue({ addSeries: () => ({ setData: mocks.setData }), timeScale: () => ({ fitContent: mocks.fitContent, setVisibleLogicalRange: mocks.setRange }), remove: mocks.remove })
  const view = render(<CandlestickCanvas symbol="AAPL" candles={candles} onError={vi.fn()} />)
  expect(mocks.setData).toHaveBeenCalledWith([{ time: Date.parse(candles[0].startTime) / 1000, open: 100, high: 105, low: 99, close: 102 }])
  const options = mocks.create.mock.calls[0][1]
  expect(options.autoSize).toBe(true)
  expect(options.timeScale.barSpacing).toBe(18)
  expect(options.timeScale.rightOffset).toBe(2)
  expect(options.handleScroll).toEqual({ mouseWheel: false, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false })
  expect(options.handleScale).toEqual({ mouseWheel: false, pinch: true })
  expect(options.layout.attributionLogo).toBe(true)
  expect(options.localization.timeFormatter(Date.parse(candles[0].startTime) / 1000)).toBe('2026-10-31 14:32:00 UTC')
  expect(mocks.fitContent).toHaveBeenCalledOnce()
  expect(mocks.setRange).not.toHaveBeenCalled()
  view.unmount()
  expect(mocks.remove).toHaveBeenCalledOnce()
})
it('reports chart initialization failure without crashing the dashboard', () => {
  mocks.create.mockImplementation(() => { throw new Error('Canvas unavailable') })
  const onError = vi.fn()
  render(<CandlestickCanvas symbol="AAPL" candles={candles} onError={onError} />)
  expect(onError).toHaveBeenCalledOnce()
})

it('updates candles without recreating the chart or resetting its zoom on refresh', () => {
  mocks.create.mockReturnValue({ addSeries: () => ({ setData: mocks.setData }), timeScale: () => ({ fitContent: mocks.fitContent, setVisibleLogicalRange: mocks.setRange }), remove: mocks.remove })
  const onError = vi.fn()
  const view = render(<CandlestickCanvas symbol="AAPL" candles={candles} onError={onError} />)
  view.rerender(<CandlestickCanvas symbol="AAPL" candles={[...candles, { ...candles[0], startTime: '2026-10-31T14:33:00Z' }]} onError={onError} />)
  expect(mocks.create).toHaveBeenCalledOnce()
  expect(mocks.setData).toHaveBeenCalledTimes(2)
  expect(mocks.fitContent).toHaveBeenCalledOnce()
  expect(mocks.setRange).not.toHaveBeenCalled()
  expect(mocks.remove).not.toHaveBeenCalled()
  view.unmount()
  expect(mocks.remove).toHaveBeenCalledOnce()
})

it('maps the close-price chart to actual candle closes', () => {
  mocks.create.mockReturnValue({ addSeries: () => ({ setData: mocks.setData }), timeScale: () => ({ fitContent: mocks.fitContent, setVisibleLogicalRange: mocks.setRange }), remove: mocks.remove })
  render(<CandlestickCanvas symbol="AAPL" candles={candles} mode="line" onError={vi.fn()} />)
  expect(mocks.setData).toHaveBeenCalledWith([{ time: Date.parse(candles[0].startTime) / 1000, value: 102 }])
})

it('zooms deliberately around the visible center and resets the view', () => {
  const setVisibleLogicalRange = vi.fn()
  mocks.create.mockReturnValue({ addSeries: () => ({ setData: mocks.setData }), timeScale: () => ({ fitContent: mocks.fitContent, getVisibleLogicalRange: () => ({ from: 0, to: 8 }), setVisibleLogicalRange }), remove: mocks.remove })
  render(<CandlestickCanvas symbol="AAPL" candles={candles} onError={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: 'Zoom in' }))
  expect(setVisibleLogicalRange).toHaveBeenLastCalledWith({ from: 1, to: 7 })
  fireEvent.click(screen.getByRole('button', { name: 'Zoom out' }))
  const range = setVisibleLogicalRange.mock.calls.at(-1)![0]
  expect(range.to - range.from).toBeCloseTo(8 / 0.75)
  expect((range.from + range.to) / 2).toBeCloseTo(4)
  fireEvent.click(screen.getByRole('button', { name: 'Reset view' }))
  expect(mocks.fitContent).toHaveBeenCalledTimes(2)
})
