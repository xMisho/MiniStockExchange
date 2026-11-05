import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ExternalMarketDashboard } from './ExternalMarketDashboard'
import { getExternalQuote, getMarketConnection } from '../api/externalMarket'
vi.mock('../api/externalMarket')
vi.mock('../auth/authState', () => ({ useAuth: () => ({ user: null, status: 'anonymous', busy: false, holdSession: () => () => {} }) }))
vi.mock('../components/workspace/MarketSession', () => ({ MarketSession: () => <div>Session status</div> }))
vi.mock('../components/workspace/SymbolSearch', () => ({ SymbolSearch: () => <div>Stock search</div> }))
vi.mock('../components/workspace/PriceAlerts', () => ({ PriceAlerts: () => <div>Price alerts</div> }))
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
  expect(screen.queryByText('Local trading form')).toBeNull()
  expect(screen.getByRole('heading', { name: 'Your market, at a glance.' })).toBeTruthy()
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

it('renders all ten starter stocks without requiring external credentials', async () => {
  const symbols = ['AAPL','TSLA','NVDA','MSFT','AMZN','GOOGL','META','AMD','AVGO','PLTR']
  vi.mocked(getMarketConnection).mockResolvedValue({provider:'Alpaca',feed:'IEX',configured:false,instruments:symbols.map(symbol=>({symbol,companyName:symbol,currency:'USD'}))})
  render(<ExternalMarketDashboard />)
  await screen.findByText('Connect Alpaca to load real prices')
  expect(screen.getByRole('group',{name:'Select an external stock'}).querySelectorAll('button')).toHaveLength(10)
  expect(getExternalQuote).not.toHaveBeenCalled()
})

it('automatically requests quotes for all ten starter stocks without selecting them', async () => {
  const symbols = ['AAPL','TSLA','NVDA','MSFT','AMZN','GOOGL','META','AMD','AVGO','PLTR']
  vi.mocked(getMarketConnection).mockResolvedValue({provider:'Alpaca',feed:'IEX',configured:true,instruments:symbols.map(symbol=>({symbol,companyName:symbol,currency:'USD'}))})
  vi.mocked(getExternalQuote).mockImplementation(() => new Promise(() => {}))
  render(<ExternalMarketDashboard />)
  await act(async () => {})
  expect(new Set(vi.mocked(getExternalQuote).mock.calls.map(([key]) => key))).toEqual(new Set(symbols.map(symbol => `external:${symbol}`)))
  expect(screen.queryByText('Select to load')).toBeNull()
})
