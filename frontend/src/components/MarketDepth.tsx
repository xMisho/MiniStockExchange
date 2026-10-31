import { RefreshStatus } from './RefreshStatus'
import { getMarketDepth } from '../api/marketData'
import { useSymbolResource } from '../hooks/useSymbolResource'
import { formatCount, formatPrice } from '../utils/format'
import { PanelMessage } from './PanelMessage'

export function MarketDepth({ symbol }: { symbol: string }) {
  const { resource, retry, updatedAt, refreshError, refreshing } = useSymbolResource(symbol, getMarketDepth)
  const depth = resource.status === 'ready' ? resource.data : null
  const rows = depth ? Array.from({ length: Math.max(depth.bids.length, depth.asks.length) }, (_, index) => index) : []

  return (
    <section className="data-panel" aria-label={`${symbol} market depth`}>
      <div className="data-heading"><h2>Market depth</h2><span>Top 5 levels</span></div>
      {!depth ? <PanelMessage loading={resource.status === 'loading'} name="depth" onRetry={retry} />
        : rows.length === 0 ? <p className="panel-message">No resting orders yet.</p> : (
          <><table className="market-table depth-table"><caption className="sr-only">{symbol} bid and ask price levels</caption>
            <thead><tr><th scope="col">Bid</th><th scope="col">Size</th><th scope="col">Ask</th><th scope="col">Size</th></tr></thead>
            <tbody>{rows.map((index) => <tr key={index}>
              <td className="positive">{depth.bids[index] ? formatPrice(depth.bids[index].price) : '—'}</td>
              <td>{depth.bids[index] ? formatCount(depth.bids[index].quantity) : '—'}</td>
              <td className="negative">{depth.asks[index] ? formatPrice(depth.asks[index].price) : '—'}</td>
              <td>{depth.asks[index] ? formatCount(depth.asks[index].quantity) : '—'}</td>
            </tr>)}</tbody>
          </table><p className="panel-footnote">Aggregated remaining shares at each price.</p></>
        )}
      <RefreshStatus updatedAt={updatedAt} refreshError={refreshError} refreshing={refreshing} onRetry={retry} />
    </section>
  )
}
