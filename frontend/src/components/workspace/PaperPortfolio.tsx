import { getPortfolio } from '../../api/workspace'
import { useSymbolResource } from '../../hooks/useSymbolResource'
import { useAuth } from '../../auth/authState'
import { formatPrice, formatCount, formatChange } from '../../utils/format'
export function PaperPortfolio({ active }: { active: boolean }) {
  const auth = useAuth()
  const state = useSymbolResource(active && auth.user ? `portfolio:${auth.user.id}` : null, getPortfolio, 5000)
  const data = state.resource.status === 'ready' ? state.resource.data : null
  return <section className="data-panel" aria-label="Local paper portfolio"><div className="data-heading"><h2>Paper portfolio</h2><span>Local executions only · USD</span></div>
    {!auth.user ? <p>Sign in to view your paper portfolio.</p> : !data ? <p role={state.resource.status === 'error' ? 'alert' : 'status'}>{state.errorMessage ?? 'Loading portfolio…'}</p> : <>
      <dl className="portfolio-metrics">{[['Virtual cash', data.cash], ['Equity', data.equity], ['Realized P/L', data.realizedPnl], ['Unrealized P/L', data.unrealizedPnl]].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{formatPrice(value as number)}</dd></div>)}</dl>
      {data.positions.length === 0 ? <p className="panel-message">No executed positions yet. Resting orders do not change holdings.</p> : <div className="table-scroll portfolio-scroll" tabIndex={0} role="region" aria-label="Paper positions, scroll horizontally"><table className="market-table"><thead><tr>{['Symbol', 'Shares (signed)', 'Average price', 'Local mark', 'Value', 'Unrealized P/L', 'Realized P/L'].map(x => <th scope="col" key={x}>{x}</th>)}</tr></thead><tbody>{data.positions.map(p => <tr key={p.symbol}><td>{p.symbol}</td><td>{formatCount(p.quantity)}</td><td>{formatPrice(p.averagePrice)}</td><td>{formatPrice(p.markPrice)}</td><td>{formatPrice(p.marketValue)}</td><td>{formatChange(p.unrealizedPnl)}</td><td>{formatChange(p.realizedPnl)}</td></tr>)}</tbody></table></div>}
    </>}
    {(state.refreshError || state.resource.status === 'error') && <button className="text-button" onClick={state.retry}>Retry portfolio</button>}
    <p className="panel-footnote">Starts at $100,000 virtual cash and replays your committed local fills using average cost. Marks use the latest local execution, not Alpaca. No cash/position reservations or margin checks; negative cash and short positions are possible. Self-trades net to zero. No deposits or withdrawals.</p>
  </section>
}
