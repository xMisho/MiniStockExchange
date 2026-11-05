import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useMarketStream } from './useMarketStream'
import { refreshMarket } from '../utils/marketEvents'
vi.mock('../utils/marketEvents')
class Stream extends EventTarget {
  static instances: Stream[] = []
  onerror: (() => void) | null = null
  close = vi.fn()
  constructor(publicUrl: string) { super(); expect(publicUrl).toBe('/api/events'); Stream.instances.push(this) }
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.clearAllMocks(); Stream.instances = [] })
it('reloads on reconnect, filters symbol hints and closes on symbol change/unmount', () => {
  vi.stubGlobal('EventSource', Stream)
  const view = renderHook(({symbol}) => useMarketStream(symbol), { initialProps: {symbol: 'AAPL'} })
  const source = Stream.instances[0]
  act(() => source.dispatchEvent(new Event('connected')))
  expect(view.result.current).toBe(true)
  expect(refreshMarket).toHaveBeenCalledWith('AAPL')
  vi.mocked(refreshMarket).mockClear()
  act(() => source.dispatchEvent(new MessageEvent('market', {data: '{"symbol":"TSLA"}'})))
  expect(refreshMarket).not.toHaveBeenCalled()
  act(() => source.dispatchEvent(new MessageEvent('market', {data: '{"symbol":"AAPL"}'})))
  expect(refreshMarket).toHaveBeenCalledWith('AAPL')
  act(() => source.onerror?.())
  expect(view.result.current).toBe(false)
  act(() => source.dispatchEvent(new Event('connected')))
  expect(view.result.current).toBe(true)
  view.rerender({symbol: 'TSLA'}); expect(source.close).toHaveBeenCalled()
  view.unmount(); expect(Stream.instances[1].close).toHaveBeenCalled()
})
