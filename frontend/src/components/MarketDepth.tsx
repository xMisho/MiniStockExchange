import { useEffect, useRef } from 'react'
import { chooseBookPrice } from '../utils/bookEvents'
import { formatOrderPrice } from '../utils/format'
import { RefreshStatus } from './RefreshStatus'
import { getMarketDepth } from '../api/marketData'
import { useSymbolResource } from '../hooks/useSymbolResource'
import { formatCount } from '../utils/format'
import { PanelMessage } from './PanelMessage'

export function MarketDepth({ symbol }: { symbol: string }) {
  const { resource, retry, updatedAt, refreshError, refreshing } = useSymbolResource(symbol, getMarketDepth)
  const depth = resource.status === 'ready' ? resource.data : null
  const table = useRef<HTMLTableElement>(null)
  const previous = useRef(new Map<string, number>())
  useEffect(() => {
    if (!depth) return
    const next = new Map<string, number>()
    for (const [side, levels] of [['bid', depth.bids], ['ask', depth.asks]] as const) {
      for (const level of levels) {
        const key = `${side}:${level.price}`
        next.set(key, level.quantity)
        if (previous.current.has(key) && previous.current.get(key) !== level.quantity && !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
          table.current?.querySelector(`[data-level="${key}"]`)?.animate?.([{ backgroundColor: '#e2eee7' }, { backgroundColor: 'transparent' }], { duration: 800 })
        }
      }
    }
    previous.current = next
  }, [depth])
  const rows = depth ? Array.from({ length: Math.max(depth.bids.length, depth.asks.length) }, (_, index) => index) : []

  return (
    <section className="data-panel" aria-label={`${symbol} market depth`}>
      <div className="data-heading"><h2>Market depth</h2><span>Local book · Top 5 levels</span></div>
      {depth && <p className="panel-footnote">Spread {depth.bids[0] && depth.asks[0] ? formatOrderPrice(depth.asks[0].price - depth.bids[0].price) : '—'} · Select a price to set your limit. This does not submit an order.</p>}
      {!depth ? <PanelMessage loading={resource.status === 'loading'} name="depth" onRetry={retry} />
        : rows.length === 0 ? <p className="panel-message">No resting orders yet.</p> : (
          <><table ref={table} className="market-table depth-table"><caption className="sr-only">{symbol} bid and ask price levels</caption>
            <thead><tr><th scope="col">Bid</th><th scope="col">Size</th><th scope="col">Ask</th><th scope="col">Size</th></tr></thead>
            <tbody>{rows.map((index) => <tr key={index}>
              <td className="positive">{depth.bids[index] ? <button type="button" className="book-price positive" aria-label={`Use bid ${depth.bids[index].price} as limit price`} onClick={() => chooseBookPrice(symbol, depth.bids[index].price)}>{formatOrderPrice(depth.bids[index].price)}</button> : '—'}</td>
              <td data-level={depth.bids[index] ? `bid:${depth.bids[index].price}` : undefined}>{depth.bids[index] ? formatCount(depth.bids[index].quantity) : '—'}</td>
              <td className="negative">{depth.asks[index] ? <button type="button" className="book-price negative" aria-label={`Use ask ${depth.asks[index].price} as limit price`} onClick={() => chooseBookPrice(symbol, depth.asks[index].price)}>{formatOrderPrice(depth.asks[index].price)}</button> : '—'}</td>
              <td data-level={depth.asks[index] ? `ask:${depth.asks[index].price}` : undefined}>{depth.asks[index] ? formatCount(depth.asks[index].quantity) : '—'}</td>
            </tr>)}</tbody>
          </table><p className="panel-footnote">Aggregated remaining shares at each price.</p></>
        )}
      <RefreshStatus updatedAt={updatedAt} refreshError={refreshError} refreshing={refreshing} onRetry={retry} />
    </section>
  )
}
