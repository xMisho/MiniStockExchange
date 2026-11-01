import { useEffect, useRef } from 'react'
import { CandlestickSeries, ColorType, createChart } from 'lightweight-charts'
import type { IChartApi, ISeriesApi, Time, UTCTimestamp } from 'lightweight-charts'
import type { Candle } from '../types/market'
import { formatUtcDateTime } from '../utils/format'

interface Props { candles: Candle[]; symbol: string; onError: () => void }

export function CandlestickCanvas({ candles, symbol, onError }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const chartRef = useRef<IChartApi | null>(null)
  const seriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const fittedSymbol = useRef<string | null>(null)

  useEffect(() => {
    if (!container.current) return
    let chart: IChartApi | undefined
    try {
      chart = createChart(container.current, {
        autoSize: true,
        height: 300,
        layout: { background: { type: ColorType.Solid, color: '#ffffff' }, textColor: '#68786d', attributionLogo: true },
        grid: { vertLines: { color: '#f0f3ec' }, horzLines: { color: '#f0f3ec' } },
        timeScale: { timeVisible: true, secondsVisible: false, borderColor: '#dce2db',
          tickMarkFormatter: (time: Time) => typeof time === 'number' ? new Date(time * 1000).toISOString().slice(5, 16).replace('T', ' ') : '' },
        rightPriceScale: { borderColor: '#dce2db' },
        localization: { locale: 'en-US', timeFormatter: (time: Time) => typeof time === 'number' ? formatUtcDateTime(new Date(time * 1000).toISOString()) : '' },
      })
      chartRef.current = chart
      seriesRef.current = chart.addSeries(CandlestickSeries, {
        upColor: '#28734e', downColor: '#a34545', borderVisible: false,
        wickUpColor: '#28734e', wickDownColor: '#a34545',
      })
    } catch {
      onError()
    }
    return () => { chart?.remove(); chartRef.current = null; seriesRef.current = null; fittedSymbol.current = null }
  }, [onError])

  useEffect(() => {
    if (!seriesRef.current || !chartRef.current) return
    try {
      seriesRef.current.setData(candles.map((candle) => ({
        time: Math.floor(Date.parse(candle.startTime) / 1000) as UTCTimestamp,
        open: candle.open, high: candle.high, low: candle.low, close: candle.close,
      })))
      if (fittedSymbol.current !== symbol) {
        chartRef.current.timeScale().fitContent()
        fittedSymbol.current = symbol
      }
    } catch { onError() }
  }, [candles, symbol, onError])

  return <div ref={container} className="candlestick-canvas" role="img" aria-label={`${symbol} one-minute OHLC candlesticks, UTC time. Exact values are in the candle data table below.`} />
}
