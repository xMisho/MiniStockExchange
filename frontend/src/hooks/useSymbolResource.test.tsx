import { StrictMode } from 'react'
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useSymbolResource } from './useSymbolResource'

beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-10-31T12:00:00Z')); vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible') })
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks() })
async function tick(ms = 0) { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }
function visibility(value: DocumentVisibilityState) {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue(value)
  act(() => document.dispatchEvent(new Event('visibilitychange')))
}

it('polls every two seconds and records the latest successful update', async () => {
  const load = vi.fn().mockResolvedValue(1)
  const { result } = renderHook(() => useSymbolResource('AAPL', load))
  await tick()
  expect(result.current.resource).toEqual({ status: 'ready', data: 1 })
  const first = result.current.updatedAt
  await tick(1999)
  expect(load).toHaveBeenCalledTimes(1)
  await tick(1)
  expect(load).toHaveBeenCalledTimes(2)
  expect(result.current.updatedAt).toBe(first! + 2000)
})

it('retains data during refresh and failure, then recovers automatically', async () => {
  let reject!: (reason: Error) => void
  const load = vi.fn().mockResolvedValueOnce(1).mockImplementationOnce(() => new Promise((_, fail) => { reject = fail })).mockResolvedValue(2)
  const { result } = renderHook(() => useSymbolResource('AAPL', load))
  await tick()
  const updated = result.current.updatedAt
  await tick(2000)
  expect(result.current.resource).toEqual({ status: 'ready', data: 1 })
  expect(result.current.refreshing).toBe(true)
  await act(async () => reject(new Error('offline')))
  expect(result.current.refreshError).toBe(true)
  expect(result.current.updatedAt).toBe(updated)
  expect(result.current.resource).toEqual({ status: 'ready', data: 1 })
  await tick(2000)
  expect(result.current.resource).toEqual({ status: 'ready', data: 2 })
  expect(result.current.refreshError).toBe(false)
})

it('never overlaps requests, including manual retries, and waits after completion', async () => {
  let resolve!: (value: number) => void
  const load = vi.fn().mockImplementation(() => new Promise((done) => { resolve = done }))
  const { result } = renderHook(() => useSymbolResource('AAPL', load))
  await tick(10000)
  act(() => result.current.retry())
  expect(load).toHaveBeenCalledTimes(1)
  await act(async () => resolve(1))
  await tick(1999)
  expect(load).toHaveBeenCalledTimes(1)
  await tick(1)
  expect(load).toHaveBeenCalledTimes(2)
})

it('pauses hidden tabs, aborts in-flight reads and refreshes on return without stale overwrite', async () => {
  let oldResolve!: (value: number) => void
  const load = vi.fn().mockImplementationOnce(() => new Promise((done) => { oldResolve = done })).mockResolvedValue(2)
  const { result } = renderHook(() => useSymbolResource('AAPL', load))
  const signal = load.mock.calls[0][1] as AbortSignal
  visibility('hidden')
  expect(signal.aborted).toBe(true)
  await tick(20000)
  expect(load).toHaveBeenCalledTimes(1)
  visibility('visible')
  await tick()
  expect(result.current.resource).toEqual({ status: 'ready', data: 2 })
  await act(async () => oldResolve(99))
  expect(result.current.resource).toEqual({ status: 'ready', data: 2 })
})

it('does not start hidden and uses the slower candle interval', async () => {
  visibility('hidden')
  const load = vi.fn().mockResolvedValue([])
  renderHook(() => useSymbolResource('AAPL', load, 10000))
  await tick(20000)
  expect(load).not.toHaveBeenCalled()
  visibility('visible')
  await tick()
  await tick(9999)
  expect(load).toHaveBeenCalledTimes(1)
  await tick(1)
  expect(load).toHaveBeenCalledTimes(2)
})

it('cleans timers and requests in StrictMode and on symbol changes', async () => {
  const load = vi.fn().mockResolvedValue(1)
  const { rerender, unmount } = renderHook(({ symbol }) => useSymbolResource(symbol, load), { initialProps: { symbol: 'AAPL' }, wrapper: StrictMode })
  await tick()
  const signals = load.mock.calls.map((call) => call[1] as AbortSignal)
  rerender({ symbol: 'TSLA' })
  await tick()
  expect(signals.every((signal) => signal.aborted)).toBe(true)
  unmount()
  const count = load.mock.calls.length
  await tick(20000)
  expect(load).toHaveBeenCalledTimes(count)
})
