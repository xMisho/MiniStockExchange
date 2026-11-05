import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PaperPortfolio } from './PaperPortfolio'
import { getTradingBalance } from '../../api/orders'
import { sellShares } from '../../utils/tradeIntent'
import { useSymbolResource } from '../../hooks/useSymbolResource'
vi.mock('../../auth/authState', () => ({ useAuth: () => ({ user: {id: 1}, busy: false }) }))
vi.mock('../../hooks/useSymbolResource')
vi.mock('../../utils/tradeIntent')
vi.mock('../../api/orders', () => ({ getTradingBalance: vi.fn() }))
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(useSymbolResource).mockReturnValue({ resource: {status: 'ready', data: {cash: 90000, startingCash: 100000, equity: 100000, reservedCash: 100, availableCash: 89900, realizedPnl: 10, unrealizedPnl: -20, positions: [{symbol:'AAPL', quantity:6, averagePrice:100, markPrice:90, marketValue:540, realizedPnl:10, unrealizedPnl:-60}]}}, refreshing:false, refreshError:false, errorMessage:null, updatedAt:null, retry:vi.fn() })
})
afterEach(cleanup)
it('checks free shares before preparing a SELL, without submitting any order', async () => {
  vi.mocked(getTradingBalance).mockResolvedValue({cash:90000,reservedCash:100,availableCash:89900,holdings:6,reservedShares:2,availableShares:4})
  render(<PaperPortfolio active />)
  expect(screen.getByText('$90,000.00')).toBeTruthy()
  expect(screen.getByText('−$20.00')).toBeTruthy()
  fireEvent.click(screen.getByRole('button',{name:'Sell AAPL shares'}))
  await waitFor(() => expect(sellShares).toHaveBeenCalledWith('AAPL',4))
})
it('explains reservations when all owned shares are already reserved', async () => {
  vi.mocked(getTradingBalance).mockResolvedValue({cash:90000,reservedCash:100,availableCash:89900,holdings:6,reservedShares:6,availableShares:0})
  render(<PaperPortfolio active />)
  fireEvent.click(screen.getByRole('button',{name:'Sell AAPL shares'}))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'No available shares to sell. Existing SELL orders may already reserve your holdings.')
  expect(sellShares).not.toHaveBeenCalled()
})
