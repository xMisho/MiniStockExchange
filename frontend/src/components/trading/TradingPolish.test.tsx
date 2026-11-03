import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { OrderForm } from './OrderForm'
import { OrderLifecycle } from './OrderLifecycle'
import { MarketDepth } from '../MarketDepth'
import { getOrderEvents, getOrderPreview } from '../../api/orders'
import { getPortfolio } from '../../api/workspace'
import { getMarketDepth } from '../../api/marketData'
import { useSymbolResource } from '../../hooks/useSymbolResource'
import type { Order } from '../../types/orders'
vi.mock('../../hooks/useSymbolResource')
vi.mock('../../api/orders', async original => ({ ...await original<typeof import('../../api/orders')>(), getOrderPreview: vi.fn(), getOrderEvents: vi.fn() }))
const submit = vi.fn().mockResolvedValue(true)
const depth = { symbol: 'AAPL', bids: [{ price: 99.125, quantity: 4 }], asks: [{ price: 100.25, quantity: 2 }] }
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(useSymbolResource).mockImplementation((_key, loader) => ({
    resource: { status: 'ready', data: loader === getPortfolio ? { cash: 99480, positions: [{ symbol: 'AAPL', quantity: 6 }] } : loader === getMarketDepth ? depth : { bestBidPrice: 99.125, bestAskPrice: 100.25 } },
    retry: vi.fn(), updatedAt: Date.now(), refreshing: false, refreshError: false, errorMessage: null,
  }) as ReturnType<typeof useSymbolResource>)
  vi.mocked(getOrderPreview).mockResolvedValue({ requestedQuantity: 5, filledQuantity: 2, unfilledQuantity: 3, estimatedValue: 200.5, averagePrice: 100.25, worstPrice: 100.25, observedAt: '2026-11-03T10:00:00Z' })
})
afterEach(cleanup)
it('copies book prices with four-decimal precision without submitting or changing side', () => {
  render(<><OrderForm symbol="AAPL" disabled={false} onSubmit={submit} /><MarketDepth symbol="AAPL" /></>)
  fireEvent.change(screen.getByLabelText('Side'), { target: { value: 'SELL' } })
  fireEvent.click(screen.getByRole('button', { name: 'Use bid 99.125 as limit price' }))
  expect((screen.getByLabelText('Limit price') as HTMLInputElement).value).toBe('99.125')
  expect((screen.getByLabelText('Side') as HTMLSelectElement).value).toBe('SELL')
  expect(document.activeElement).toBe(screen.getByLabelText('Limit price'))
  expect(submit).not.toHaveBeenCalled()
})
it('shows cash, holdings, limit notional and explains balances are not reserved', () => {
  render(<OrderForm symbol="AAPL" disabled={false} onSubmit={submit} />)
  fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '2' } })
  fireEvent.change(screen.getByLabelText('Limit price'), { target: { value: '100.25' } })
  expect(screen.getByText('$200.50')).toBeTruthy()
  expect(screen.getByText(/Paper cash/).textContent).toContain('99,480.00')
  expect(screen.getByText(/Paper cash/).textContent).toContain('Holdings 6')
  expect(screen.getByText(/cash is not reserved/)).toBeTruthy()
})
it('previews market liquidity and never submits automatically', async () => {
  render(<OrderForm symbol="AAPL" disabled={false} onSubmit={submit} />)
  fireEvent.change(screen.getByLabelText('Order type'), { target: { value: 'MARKET' } })
  fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '5' } })
  expect(await screen.findByText(/Insufficient local liquidity/)).toBeTruthy()
  expect(screen.getByText('2 / 5 shares')).toBeTruthy()
  expect(submit).not.toHaveBeenCalled()
  expect(getOrderPreview).toHaveBeenCalledWith('AAPL', 'BUY', 5, expect.any(AbortSignal))
})
it('does not copy a book price into a disabled ticket', () => {
  render(<><OrderForm symbol="AAPL" disabled onSubmit={submit} /><MarketDepth symbol="AAPL" /></>)
  fireEvent.click(screen.getByRole('button', { name: 'Use ask 100.25 as limit price' }))
  expect((screen.getByLabelText('Limit price') as HTMLInputElement).value).toBe('')
})
it('ignores an old preview when quantity changes', async () => {
  render(<OrderForm symbol="AAPL" disabled={false} onSubmit={submit} />)
  fireEvent.change(screen.getByLabelText('Order type'), { target: { value: 'MARKET' } })
  fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '5' } })
  await screen.findByText('2 / 5 shares')
  fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '8' } })
  expect(screen.queryByText('2 / 5 shares')).toBeNull()
})
it('loads lifecycle only when expanded and shows individual fills and amendment quantities', async () => {
  vi.mocked(getOrderEvents).mockResolvedValue([
    { id: 2, kind: 'AMENDED', occurred_at: '2026-11-03T10:02:00Z', price: 99, quantity: 3, previous_price: 100, previous_remaining: 1, remaining_quantity: 3, filled_quantity: 1 },
    { id: 1, kind: 'FILL', occurred_at: '2026-11-03T10:01:00Z', price: 100, quantity: 1, previous_price: null, previous_remaining: null, remaining_quantity: null, filled_quantity: null },
  ])
  const order: Order = { id: 7, userId: 1, symbol: 'AAPL', side: 'SELL', type: 'LIMIT', limitPrice: 99, originalQuantity: 2, remainingQuantity: 3, filledQuantity: 1, status: 'PARTIALLY_FILLED', createdAt: '2026-11-03T10:00:00Z', updatedAt: '2026-11-03T10:02:00Z' }
  render(<OrderLifecycle order={order} />)
  expect(getOrderEvents).not.toHaveBeenCalled()
  fireEvent.click(screen.getByText('Order activity'))
  await waitFor(() => expect(getOrderEvents).toHaveBeenCalledWith(7, expect.any(AbortSignal)))
  expect(await screen.findByText(/1 shares at 100.00/)).toBeTruthy()
  expect(screen.getByText(/requested remaining 1 → 3/)).toBeTruthy()
})

it('offers every registered stock in the ticket and delegates selection without submitting', () => {
  const change = vi.fn()
  render(<OrderForm symbol="AAPL" disabled={false} stocks={[{ symbol: 'AAPL', companyName: 'Apple Inc.', referencePrice: 100 }, { symbol: 'TSLA', companyName: 'Tesla, Inc.', referencePrice: 200 }]} onSymbolChange={change} onSubmit={submit} />)
  const picker = screen.getByRole('combobox', { name: /Trade stock/ })
  expect((picker as HTMLSelectElement).value).toBe('AAPL')
  expect(screen.getByRole('option', { name: 'TSLA · Tesla, Inc.' })).toBeTruthy()
  fireEvent.change(picker, { target: { value: 'TSLA' } })
  expect(change).toHaveBeenCalledWith('TSLA')
  expect(submit).not.toHaveBeenCalled()
})
it('locks the ticket symbol while an order request is pending', () => {
  render(<OrderForm symbol="TSLA" disabled stocks={[{ symbol: 'TSLA', companyName: 'Tesla, Inc.', referencePrice: 200 }]} onSymbolChange={vi.fn()} onSubmit={submit} />)
  expect((screen.getByRole('combobox', { name: /Trade stock/ }) as HTMLSelectElement).closest('fieldset')?.disabled).toBe(true)
})
