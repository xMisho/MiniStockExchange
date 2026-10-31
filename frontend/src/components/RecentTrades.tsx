import { getRecentTrades } from '../api/marketData'
import { useSymbolResource } from '../hooks/useSymbolResource'
import { formatCount, formatPrice, formatUtcDateTime, formatUtcTime } from '../utils/format'
import { PanelMessage } from './PanelMessage'

export function RecentTrades({ symbol }: { symbol: string }) {
  const { resource, retry } = useSymbolResource(symbol, getRecentTrades)
  const trades = resource.status === 'ready' ? resource.data.content : null
  return (
    <section className="data-panel" aria-label={`${symbol} recent trades`}>
      <div className="data-heading"><h2>Recent trades</h2><span>Latest 10 · ID order</span></div>
      {!trades ? <PanelMessage loading={resource.status === 'loading'} name="trades" onRetry={retry} />
        : trades.length === 0 ? <p className="panel-message">No executions yet.</p> : (
          <table className="market-table"><caption className="sr-only">{symbol} latest executions by trade ID descending</caption>
            <thead><tr><th scope="col">Price</th><th scope="col">Size</th><th scope="col">Time (UTC)</th></tr></thead>
            <tbody>{trades.map((trade) => <tr key={trade.tradeId}>
              <td>{formatPrice(trade.executionPrice)}</td><td>{formatCount(trade.quantity)}</td>
              <td><time dateTime={trade.executedAt} title={formatUtcDateTime(trade.executedAt)}>{formatUtcTime(trade.executedAt)}</time></td>
            </tr>)}</tbody>
          </table>
        )}
    </section>
  )
}
