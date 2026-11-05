import { useEffect, useRef, useState } from 'react'
import { getTradingBalance } from '../../api/orders'
import { sellShares } from '../../utils/tradeIntent'
import { getPortfolio } from '../../api/workspace'
import { useSymbolResource } from '../../hooks/useSymbolResource'
import { useAuth } from '../../auth/authState'
import { formatMoney, formatCount } from '../../utils/format'
export function PaperPortfolio({ active }: { active: boolean }) {
  const auth = useAuth()
  const [selling, setSelling] = useState<string | null>(null)
  const [sellError, setSellError] = useState<string | null>(null)
  const sellLock = useRef(false)
  const sellRequest = useRef<AbortController | null>(null)
  useEffect(() => () => sellRequest.current?.abort(), [])
  const prepareSell = async (symbol: string) => {
    if (sellLock.current || auth.busy) return
    sellLock.current = true; setSelling(symbol); setSellError(null)
    const controller = new AbortController(); sellRequest.current = controller
    try {
      const balance = await getTradingBalance(symbol, controller.signal)
      if (controller.signal.aborted) return
      if (balance.availableShares <= 0) { setSellError('No available shares to sell. Existing SELL orders may already reserve your holdings.'); return }
      sellShares(symbol, balance.availableShares)
    } catch (failure) { if (!controller.signal.aborted) setSellError(failure instanceof Error ? failure.message : 'Could not check available shares. Please try again.') }
    finally { sellLock.current = false; if (!controller.signal.aborted) setSelling(null) }
  }
  const state = useSymbolResource(active && auth.user ? `portfolio:${auth.user.id}` : null, getPortfolio, 5000)
  const data = state.resource.status === 'ready' ? state.resource.data : null
  return <section className="data-panel" aria-label="Local paper portfolio"><div className="data-heading"><h2>Paper portfolio</h2><span>Local executions only · USD</span></div>
    {!auth.user ? <p>Sign in to view your paper portfolio.</p> : !data ? <p role={state.resource.status === 'error' ? 'alert' : 'status'}>{state.errorMessage ?? 'Loading portfolio…'}</p> : <>
      <dl className="portfolio-metrics">{[['Virtual cash', data.cash], ['Reserved cash', data.reservedCash ?? 0], ['Available cash', data.availableCash ?? data.cash], ['Equity', data.equity], ['Realized P/L', data.realizedPnl], ['Unrealized P/L', data.unrealizedPnl]].map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{formatMoney(value as number, String(label).includes('P/L'))}</dd></div>)}</dl>
      {data.positions.length === 0 ? <p className="panel-message">No shares owned yet. Open BUY orders are waiting for a seller; shares appear after a fill or an admin demo allocation.</p> : <div className="table-scroll portfolio-scroll" tabIndex={0} role="region" aria-label="Paper positions, scroll horizontally"><table className="market-table"><thead><tr>{['Symbol', 'Shares (signed)', 'Average price', 'Local mark', 'Value', 'Unrealized P/L', 'Realized P/L', 'Trade'].map(x => <th scope="col" key={x}>{x}</th>)}</tr></thead><tbody>{data.positions.map(p => <tr key={p.symbol}><td>{p.symbol}</td><td>{formatCount(p.quantity)}</td><td>{formatMoney(p.averagePrice)}</td><td>{formatMoney(p.markPrice)}</td><td>{formatMoney(p.marketValue)}</td><td>{formatMoney(p.unrealizedPnl, true)}</td><td>{formatMoney(p.realizedPnl, true)}</td><td>{p.quantity > 0 ? <button type="button" className="text-button" disabled={auth.busy || selling !== null} aria-label={`Sell ${p.symbol} shares`} onClick={() => { void prepareSell(p.symbol) }}>{selling === p.symbol ? 'Checking shares…' : 'Sell shares'}</button> : 'No shares to sell'}</td></tr>)}</tbody></table></div>}
    </>}
    {sellError && <p role="alert">{sellError}</p>}
    {(state.refreshError || state.resource.status === 'error') && <button className="text-button" onClick={state.retry}>Retry portfolio</button>}
    <p className="panel-footnote">Sell shares opens a SELL ticket; it does not submit an order. The server checks available shares after existing sell reservations. A matching local buyer is required for execution.</p>
    <p className="panel-footnote">Starts at $100,000 virtual cash and replays your committed local fills using average cost. Marks use the latest local execution, not Alpaca. Open limit orders reserve cash or shares. New orders require sufficient available cash or holdings. Admin demo allocations purchase shares at the local reference price. Self-trades net to zero. No deposits or withdrawals.</p>
  </section>
}
