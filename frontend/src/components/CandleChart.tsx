import { VolumeChart } from './VolumeChart'
import { RefreshStatus } from './RefreshStatus'
import { useCallback, useState } from 'react'
import { getCandles } from '../api/marketData'
import { useSymbolResource } from '../hooks/useSymbolResource'
import { formatCount, formatPrice, formatUtcDateTime } from '../utils/format'
import { CandlestickCanvas } from './CandlestickCanvas'
import { PanelMessage } from './PanelMessage'

export function CandleChart({ symbol }: { symbol: string }) {
  const { resource, retry, updatedAt, refreshError, refreshing } = useSymbolResource(symbol, getCandles, 10000)
  const [mode, setMode] = useState<'candles' | 'line'>('candles')
  const [renderError, setRenderError] = useState(false)
  const chartFailed = useCallback(() => setRenderError(true), [])
  const candles = resource.status === 'ready' ? resource.data : null

  return (
    <section className="data-panel candle-panel" aria-label={`${symbol} candle chart`}>
      <div className="data-heading"><div><p className="eyebrow">EXECUTION CHARTS</p><h2>Price history</h2></div><span>1 minute · UTC · Latest 100</span></div>
      <div className="chart-switch" role="group" aria-label="Price chart view">
        <button type="button" aria-pressed={mode === 'candles'} onClick={() => { setMode('candles'); setRenderError(false) }}>Candlesticks</button>
        <button type="button" aria-pressed={mode === 'line'} onClick={() => { setMode('line'); setRenderError(false) }}>Close price</button>
      </div>
      {!candles ? <PanelMessage loading={resource.status === 'loading'} name="candles" onRetry={retry} />
        : candles.length === 0 ? <p className="panel-message">No candles yet. Candles appear in minutes with executions.</p> : (
          <>
            {renderError ? <div className="panel-message" role="alert"><p>The chart couldn’t be displayed. Candle data is available below.</p>
              <button className="primary-button" type="button" onClick={() => setRenderError(false)}>Retry chart</button></div>
              : <CandlestickCanvas key={mode} mode={mode} candles={candles} symbol={symbol} onError={chartFailed} />}
            <VolumeChart candles={candles} symbol={symbol} />
            <p className="panel-footnote">Idle minutes are omitted. The latest candle may still change. Refreshes every 10 seconds while this tab is visible.</p>
            <details className="candle-data"><summary>View candle data</summary><div className="table-scroll">
              <table className="market-table"><caption className="sr-only">{symbol} exact candle values, oldest to newest</caption>
                <thead><tr>{['Start (UTC)', 'Open', 'High', 'Low', 'Close', 'Volume', 'Trades'].map((label) => <th scope="col" key={label}>{label}</th>)}</tr></thead>
                <tbody>{candles.map((candle) => <tr key={candle.startTime}>
                  <td><time dateTime={candle.startTime}>{formatUtcDateTime(candle.startTime)}</time></td>
                  <td>{formatPrice(candle.open)}</td><td>{formatPrice(candle.high)}</td><td>{formatPrice(candle.low)}</td><td>{formatPrice(candle.close)}</td>
                  <td>{formatCount(candle.volume)}</td><td>{formatCount(candle.tradeCount)}</td>
                </tr>)}</tbody>
              </table></div></details>
          </>
        )}
      <p className="chart-attribution">Charts by <a href="https://www.tradingview.com/" target="_blank" rel="noreferrer">TradingView</a> · Lightweight Charts™</p>
      <RefreshStatus updatedAt={updatedAt} refreshError={refreshError} refreshing={refreshing} onRetry={retry} />
    </section>
  )
}
