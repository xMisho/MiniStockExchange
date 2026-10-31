import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { TradingWorkspace } from './TradingWorkspace'
import { useAuth } from '../../auth/authState'
import { amendOrder, cancelOrder, getMyOrders, OrderRequestError, submitOrder } from '../../api/orders'
import { refreshMarket } from '../../utils/marketEvents'
import type { Order, OrderPage } from '../../types/orders'

vi.mock('../../auth/authState')
vi.mock('../../utils/marketEvents')
vi.mock('../../api/orders', async (original) => ({ ...await original<typeof import('../../api/orders')>(), getMyOrders: vi.fn(), submitOrder: vi.fn(), amendOrder: vi.fn(), cancelOrder: vi.fn() }))
const open: Order = { id: 12, userId: 1, symbol: 'AAPL', side: 'BUY', type: 'LIMIT', limitPrice: 100, originalQuantity: 5, remainingQuantity: 5, filledQuantity: 0, status: 'OPEN', createdAt: '2026-10-31T12:00:00Z', updatedAt: '2026-10-31T12:00:00Z' }
const user = { id: 1, username: 'example', email: 'example@example.test', role: 'USER' as const }
const release = vi.fn()
const auth = { user, status: 'authenticated' as const, error: null, busy: false, holdSession: vi.fn(() => release), restore: vi.fn(), signIn: vi.fn(), signOut: vi.fn() }
const page = (orders: Order[] = [open], index = 0, count = orders.length): OrderPage => ({ content: orders, page: index, size: 25, totalElements: count, totalPages: Math.ceil(count / 25) })
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(useAuth).mockReturnValue(auth)
  vi.mocked(getMyOrders).mockResolvedValue(page())
  vi.mocked(submitOrder).mockResolvedValue(open)
  vi.mocked(amendOrder).mockResolvedValue(undefined)
  vi.mocked(cancelOrder).mockResolvedValue(undefined)
})
afterEach(cleanup)
function fillOrder() {
  fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '5' } })
  fireEvent.change(screen.getByLabelText('Limit price'), { target: { value: '100.2500' } })
}

it('requires login and never loads private history while anonymous', () => {
  vi.mocked(useAuth).mockReturnValue({ ...auth, user: null, status: 'anonymous' })
  render(<TradingWorkspace symbol="AAPL" />)
  expect(screen.getByText(/Sign in using the header/)).toBeTruthy()
  expect(getMyOrders).not.toHaveBeenCalled()
  expect(screen.queryByRole('button', { name: 'Submit BUY order' })).toBeNull()
})
it('submits a limit order once and shows the backend-confirmed ID and status', async () => {
  render(<TradingWorkspace symbol="AAPL" />)
  await screen.findByText('#12')
  fillOrder()
  fireEvent.click(screen.getByRole('button', { name: 'Submit BUY order' }))
  expect(await screen.findByText(/Order #12 · AAPL · OPEN/)).toBeTruthy()
  expect(submitOrder).toHaveBeenCalledWith('AAPL', { side: 'BUY', type: 'LIMIT', quantity: 5, limitPrice: '100.2500' })
  expect(refreshMarket).toHaveBeenCalledWith('AAPL')
  expect(getMyOrders).toHaveBeenCalledTimes(2)
  expect(auth.holdSession).toHaveBeenCalledOnce()
  expect(release).toHaveBeenCalledOnce()
})
it('disables market price and sends no limit price', async () => {
  render(<TradingWorkspace symbol="AAPL" />)
  fireEvent.change(screen.getByLabelText('Order type'), { target: { value: 'MARKET' } })
  expect((screen.getByLabelText('Limit price') as HTMLInputElement).disabled).toBe(true)
  fireEvent.change(screen.getByLabelText('Side'), { target: { value: 'SELL' } })
  fireEvent.change(screen.getByLabelText('Quantity'), { target: { value: '3' } })
  fireEvent.click(screen.getByRole('button', { name: 'Submit SELL order' }))
  await waitFor(() => expect(submitOrder).toHaveBeenCalledWith('AAPL', { side: 'SELL', type: 'MARKET', quantity: 3 }))
})
it('prevents duplicate clicks while a mutation is in flight', async () => {
  let resolve!: (order: Order) => void
  vi.mocked(submitOrder).mockImplementationOnce(() => new Promise((done) => { resolve = done }))
  render(<TradingWorkspace symbol="AAPL" />); fillOrder()
  const button = screen.getByRole('button', { name: 'Submit BUY order' })
  fireEvent.click(button); fireEvent.click(button)
  expect(submitOrder).toHaveBeenCalledTimes(1)
  expect((button as HTMLButtonElement).closest('fieldset')?.disabled).toBe(true)
  await act(async () => resolve(open))
  expect(submitOrder).toHaveBeenCalledTimes(1)
})
it('shows server validation errors without claiming success or changing rows', async () => {
  vi.mocked(submitOrder).mockRejectedValueOnce(new OrderRequestError('Invalid order request.', false, 400))
  render(<TradingWorkspace symbol="AAPL" />)
  await screen.findByText('#12'); fillOrder()
  fireEvent.click(screen.getByRole('button', { name: 'Submit BUY order' }))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Invalid order request.')
  expect(refreshMarket).not.toHaveBeenCalled()
  expect(getMyOrders).toHaveBeenCalledTimes(1)
})
it('blocks mutations after uncertain outcomes and offers My Orders verification without replay', async () => {
  vi.mocked(submitOrder).mockRejectedValueOnce(new OrderRequestError('Trading outcome is uncertain.', true, 503))
  render(<TradingWorkspace symbol="AAPL" />); fillOrder()
  fireEvent.click(screen.getByRole('button', { name: 'Submit BUY order' }))
  expect(await screen.findByText('Do not resubmit blindly.')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Submit BUY order' }))
  fireEvent.click(screen.getByRole('button', { name: 'Check My Orders' }))
  await waitFor(() => expect(getMyOrders).toHaveBeenCalledTimes(3))
  expect(submitOrder).toHaveBeenCalledTimes(1)
  expect(screen.getByRole('heading', { name: 'My Orders' })).toBe(document.activeElement)
  fireEvent.click(screen.getByRole('button', { name: 'I have checked My Orders' }))
  expect(screen.queryByText('Do not resubmit blindly.')).toBeNull()
  expect(submitOrder).toHaveBeenCalledTimes(1)
})
it('only exposes mutation controls on open limit orders', async () => {
  vi.mocked(getMyOrders).mockResolvedValue(page([
    open,
    { ...open, id: 11, status: 'PARTIALLY_FILLED', filledQuantity: 2, remainingQuantity: 3 },
    { ...open, id: 10, status: 'FILLED', filledQuantity: 5, remainingQuantity: 0 },
    { ...open, id: 9, status: 'CANCELLED' },
    { ...open, id: 8, type: 'MARKET', limitPrice: null, status: 'CANCELLED' },
  ]))
  render(<TradingWorkspace symbol="AAPL" />)
  await screen.findByText('#8')
  expect(screen.getAllByRole('button', { name: /^Amend #/ })).toHaveLength(2)
  expect(screen.getAllByRole('button', { name: /^Cancel #/ })).toHaveLength(2)
  expect(screen.getAllByText('2026-10-31 12:00:00 UTC', { selector: 'time' })).toHaveLength(5)
})
it('amends remaining quantity, then reloads history and market data', async () => {
  render(<TradingWorkspace symbol="AAPL" />)
  fireEvent.click(await screen.findByRole('button', { name: 'Amend #12' }))
  const form = screen.getByRole('form', { name: 'Amend order 12' })
  fireEvent.change(within(form).getByLabelText('New price'), { target: { value: '101.25' } })
  fireEvent.change(within(form).getByLabelText('New remaining quantity'), { target: { value: '3' } })
  fireEvent.click(within(form).getByRole('button', { name: 'Save amendment #12' }))
  expect(await screen.findByText(/Amendment confirmed for order #12/)).toBeTruthy()
  expect(amendOrder).toHaveBeenCalledWith(12, '101.25', '3')
  expect(refreshMarket).toHaveBeenCalledWith('AAPL')
  expect(getMyOrders).toHaveBeenCalledTimes(2)
})
it('cancels only after the backend confirms and refreshes history', async () => {
  let resolve!: () => void
  vi.mocked(cancelOrder).mockImplementationOnce(() => new Promise((done) => { resolve = done }))
  render(<TradingWorkspace symbol="AAPL" />)
  fireEvent.click(await screen.findByRole('button', { name: 'Cancel #12' }))
  expect(screen.queryByText(/Cancellation confirmed/)).toBeNull()
  expect(screen.getByText('#12')).toBeTruthy()
  await act(async () => resolve())
  expect(await screen.findByText(/Cancellation confirmed for order #12/)).toBeTruthy()
  expect(getMyOrders).toHaveBeenCalledTimes(2)
})
it('resets paging for server-side status filtering', async () => {
  vi.mocked(getMyOrders).mockImplementation(async (index, status) => page(status === 'FILLED' ? [] : [open], index, 51))
  render(<TradingWorkspace symbol="AAPL" />)
  fireEvent.click(await screen.findByRole('button', { name: 'Next orders' }))
  await waitFor(() => expect(getMyOrders).toHaveBeenLastCalledWith(1, '', expect.any(AbortSignal)))
  fireEvent.change(screen.getByLabelText('Order status'), { target: { value: 'FILLED' } })
  await waitFor(() => expect(getMyOrders).toHaveBeenLastCalledWith(0, 'FILLED', expect.any(AbortSignal)))
  expect(await screen.findByText(/No orders on this page with status FILLED/)).toBeTruthy()
})
it('keeps history available if there is no selected stock', async () => {
  render(<TradingWorkspace symbol={null} />)
  expect(await screen.findByText('#12')).toBeTruthy()
  expect(screen.getByText(/Select a registered stock/)).toBeTruthy()
})
it('clears private history and aborts its request on logout', async () => {
  const view = render(<TradingWorkspace symbol="AAPL" />)
  await screen.findByText('#12')
  const signal = vi.mocked(getMyOrders).mock.calls[0][2]
  vi.mocked(useAuth).mockReturnValue({ ...auth, user: null, status: 'anonymous' })
  view.rerender(<TradingWorkspace symbol="AAPL" />)
  expect(screen.queryByText('#12')).toBeNull()
  expect(signal?.aborted).toBe(true)
})
it('ignores an old filtered history response after the filter changes', async () => {
  let resolve!: (data: OrderPage) => void
  vi.mocked(getMyOrders).mockImplementationOnce(() => new Promise((done) => { resolve = done })).mockResolvedValue(page([]))
  render(<TradingWorkspace symbol="AAPL" />)
  fireEvent.change(screen.getByLabelText('Order status'), { target: { value: 'FILLED' } })
  await screen.findByText(/No orders on this page/)
  await act(async () => resolve(page()))
  expect(screen.queryByText('#12')).toBeNull()
})
it('refreshes a stale terminal row after a 409 conflict', async () => {
  vi.mocked(cancelOrder).mockRejectedValueOnce(new OrderRequestError('Order is no longer open.', false, 409))
  vi.mocked(getMyOrders).mockResolvedValueOnce(page()).mockResolvedValue(page([{ ...open, status: 'FILLED', remainingQuantity: 0, filledQuantity: 5 }]))
  render(<TradingWorkspace symbol="AAPL" />)
  fireEvent.click(await screen.findByRole('button', { name: 'Cancel #12' }))
  expect(await screen.findByText('Order is no longer open.')).toBeTruthy()
  await screen.findByText('FILLED', { selector: '.order-status' })
  expect(screen.queryByRole('button', { name: 'Cancel #12' })).toBeNull()
})
