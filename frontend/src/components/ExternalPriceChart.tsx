import { useCallback, useState } from 'react'
import { getExternalHistory, historyKey } from '../api/externalMarket'
import { useSymbolResource } from '../hooks/useSymbolResource'
import type { HistoryRange } from '../types/externalMarket'
import { formatCount, formatPrice, formatUtcDateTime } from '../utils/format'
import { CandlestickCanvas } from './CandlestickCanvas'
import { VolumeChart } from './VolumeChart'

export function ExternalPriceChart({ symbol }: { symbol: string }) {
  const [range, setRange] = useState<HistoryRange>('1D')
  const [mode, setMode] = useState<'candles' | 'line'>('candles')
  const [renderError, setRenderError] = useState(false)
  const failed = useCallback(() => setRenderError(true), [])
  const { resource, errorMessage, refreshError, retry } = useSymbolResource(historyKey(symbol, range), getExternalHistory, 60000)
  const response = resource.status === 'ready' ? resource.data : null
  const candles = response?.data.candles
  return <section className="data-panel candle-panel" aria-label={`${symbol} external price history`}>
    <div className="data-heading"><div><p className="eyebrow">ALPACA · IEX · EXTERNAL DATA</p><h2>Market price history</h2></div><span>{range === '1D' ? 'Latest session · 1-minute bars' : range === '1M' ? '1 month · Hourly bars' : '1 year · Daily bars'} · UTC</span></div>
    <div className="chart-controls"><div className="chart-switch" role="group" aria-label="Market chart range">{([['1D', 'Latest session'], ['1M', '1 month'], ['1Y', '1 year']] as const).map(([value, label]) => <button key={value} type="button" aria-pressed={range === value} onClick={() => { setRange(value); setRenderError(false) }}>{label}</button>)}</div>
      <div className="chart-switch" role="group" aria-label="Market chart type"><button type="button" aria-pressed={mode === 'candles'} onClick={() => { setMode('candles'); setRenderError(false) }}>Candlesticks</button><button type="button" aria-pressed={mode === 'line'} onClick={() => { setMode('line'); setRenderError(false) }}>Close price</button></div></div>
    {!candles ? <div className="panel-message" role={resource.status === 'error' ? 'alert' : 'status'}><p>{resource.status === 'error' ? errorMessage ?? 'External charts are unavailable.' : 'Loading market history…'}</p>{resource.status === 'error' && <button className="primary-button" type="button" onClick={retry}>Retry market history</button>}</div>
      : candles.length === 0 ? <p className="panel-message">No provider bars are available for this period.</p> : <>
        {range === '1D' && candles.length < 10 && <p className="source-warning">Limited session data: {candles.length} reported bars so far. The 15-minute history cutoff and sparse IEX activity can leave gaps.</p>}
        {renderError ? <div role="alert" className="panel-message">Chart unavailable. Exact provider bars are in the table below. <button type="button" className="text-button" onClick={() => setRenderError(false)}>Retry chart</button></div> : <CandlestickCanvas key={`${symbol}:${range}:${mode}`} symbol={symbol} mode={mode} candles={candles} onError={failed} />}
        <VolumeChart candles={candles} symbol={symbol} />
        <details className="candle-data"><summary>View provider bars</summary><div className="table-scroll"><table className="market-table"><thead><tr>{['Time (UTC)', 'Open', 'High', 'Low', 'Close', 'IEX volume', 'IEX trades'].map(label => <th scope="col" key={label}>{label}</th>)}</tr></thead><tbody>{candles.map(bar => <tr key={bar.startTime}><td>{formatUtcDateTime(bar.startTime)}</td><td>{formatPrice(bar.open)}</td><td>{formatPrice(bar.high)}</td><td>{formatPrice(bar.low)}</td><td>{formatPrice(bar.close)}</td><td>{new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 }).format(bar.volume)}</td><td>{bar.tradeCount === null ? '—' : formatCount(bar.tradeCount)}</td></tr>)}</tbody></table></div></details>
      </>}
    <p className="panel-footnote">Historical requests exclude the most recent 15 minutes. Latest session uses the most recent provider session within ten days, including weekends and holidays. Bars are split adjusted; idle periods are not invented.</p>
    {(response?.stale || refreshError) && <p className="source-warning" role="status">{response?.warning ?? errorMessage ?? 'History refresh failed; showing previous data.'}</p>}
    {response && <p className="source-timestamp">Provider fetched {formatUtcDateTime(response.fetchedAt)}</p>}
    <p className="chart-attribution">Market data: <a href="https://alpaca.markets/data" target="_blank" rel="noreferrer">Alpaca / IEX</a> · Charts: <a href="https://www.tradingview.com/" target="_blank" rel="noreferrer">TradingView Lightweight Charts™</a></p>
  </section>
}
