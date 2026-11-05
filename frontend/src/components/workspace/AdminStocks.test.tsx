import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AdminStocks } from './AdminStocks'
import { getExternalQuote, getMarketConnection } from '../../api/externalMarket'
import { registerStock } from '../../api/workspace'
vi.mock('../../api/externalMarket')
vi.mock('../../api/workspace')
vi.mock('../../auth/authState', () => ({useAuth: () => ({user:{id:1,role:'ADMIN'},busy:false,holdSession:()=>()=>{}})}))
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(getMarketConnection).mockResolvedValue({provider:'Alpaca',feed:'IEX',configured:true,instruments:[{symbol:'NVDA',companyName:'NVIDIA Corporation',currency:'USD'}]})
})
afterEach(cleanup)
it('prefills names but never invents a local reference or auto-registers a preset', async () => {
  render(<AdminStocks onRegistered={vi.fn()} />)
  fireEvent.change(await screen.findByLabelText('Stock preset'),{target:{value:'NVDA'}})
  expect((screen.getByLabelText('Company name') as HTMLInputElement).value).toBe('NVIDIA Corporation')
  expect((screen.getByLabelText('Reference price') as HTMLInputElement).value).toBe('')
  expect(getExternalQuote).not.toHaveBeenCalled()
  expect(registerStock).not.toHaveBeenCalled()
})
it('copies only an explicitly requested nonstale reported price', async () => {
  vi.mocked(getExternalQuote).mockResolvedValue({stale:false,data:{symbol:'NVDA',price:123.45,priceAsOf:'2026-11-05T10:00:00Z'}} as Awaited<ReturnType<typeof getExternalQuote>>)
  render(<AdminStocks onRegistered={vi.fn()} />)
  fireEvent.change(await screen.findByLabelText('Stock preset'),{target:{value:'NVDA'}})
  fireEvent.click(screen.getByRole('button',{name:'Use external reported price as reference'}))
  await waitFor(()=>expect((screen.getByLabelText('Reference price') as HTMLInputElement).value).toBe('123.45'))
  expect(registerStock).not.toHaveBeenCalled()
})

it('rejects stale provider prices without filling the reference or registering', async () => {
  vi.mocked(getExternalQuote).mockResolvedValue({stale:true,data:{symbol:'NVDA',price:123.45,priceAsOf:'2026-11-05T10:00:00Z'}} as Awaited<ReturnType<typeof getExternalQuote>>)
  render(<AdminStocks onRegistered={vi.fn()} />)
  fireEvent.change(await screen.findByLabelText('Stock preset'),{target:{value:'NVDA'}})
  fireEvent.click(screen.getByRole('button',{name:'Use external reported price as reference'}))
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'A fresh provider response with a reported trade price is required. Enter a reference price manually instead.')
  expect((screen.getByLabelText('Reference price') as HTMLInputElement).value).toBe('')
  expect(registerStock).not.toHaveBeenCalled()
})
