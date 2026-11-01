import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ExternalMarketDashboard } from './ExternalMarketDashboard'
import { getExternalQuote, getMarketConnection } from '../api/externalMarket'
vi.mock('../api/externalMarket')
vi.mock('../components/ExternalPriceChart', () => ({ ExternalPriceChart: ({ symbol }: { symbol: string }) => <div>{symbol} provider chart</div> }))
vi.mock('../components/trading/TradingWorkspace', () => ({ TradingWorkspace: () => <div>Local trading form</div> }))
vi.mock('../components/MarketPanels', () => ({ MarketPanels: () => <div>Local market panels</div> }))
const instruments = [{ symbol: 'AAPL', companyName: 'Apple Inc.', currency: 'USD' as const }, { symbol: 'TSLA', companyName: 'Tesla, Inc.', currency: 'USD' as const }]
afterEach(() => { cleanup(); vi.resetAllMocks() })
it('shows setup without substituting simulated prices when credentials are missing', async () => {
  vi.mocked(getMarketConnection).mockResolvedValue({ provider: 'Alpaca', feed: 'IEX', configured: false, instruments })
  render(<ExternalMarketDashboard />)
  expect(await screen.findByText('Connect Alpaca to load real prices')).toBeTruthy()
  expect(getExternalQuote).not.toHaveBeenCalled()
  expect(screen.getByText(/not routed to Alpaca/)).toBeTruthy()
})
it('switches independent provider quotes and charts and identifies provenance', async () => {
  vi.mocked(getMarketConnection).mockResolvedValue({ provider: 'Alpaca', feed: 'IEX', configured: true, instruments })
  vi.mocked(getExternalQuote).mockImplementation(async key => ({ provider: 'Alpaca', feed: 'IEX', currency: 'USD', coverage: 'Single exchange', fetchedAt: '2026-11-01T12:00:00Z', stale: false, warning: null, data: { symbol: key.split(':')[1], price: 102, priceAsOf: '2026-10-30T19:00:00Z', bid: null, ask: null, quoteAsOf: null, previousClose: 100, change: 2, changePercent: 2, sessionOpen: 101, sessionHigh: 103, sessionLow: 99, sessionVolume: 10, sessionTradeCount: 4, sessionVwap: 101, sessionDate: '2026-10-30' } }))
  render(<ExternalMarketDashboard />)
  await act(async () => {})
  expect(screen.getByRole('region', { name: 'AAPL external market quote' })).toBeTruthy()
  expect(screen.getByText(/not total US-market volume/)).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'TSLA Tesla, Inc.' }))
  await act(async () => {})
  expect(screen.getByRole('region', { name: 'TSLA external market quote' })).toBeTruthy()
  expect(screen.getByText('TSLA provider chart')).toBeTruthy()
})
