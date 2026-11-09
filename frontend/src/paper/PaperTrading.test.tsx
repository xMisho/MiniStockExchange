import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { PaperTrading } from './PaperTrading'
import * as api from './api'
import { AuthError } from '../api/auth'
vi.mock('./api',()=>({portfolio:vi.fn(),orders:vi.fn(),fills:vi.fn(),submit:vi.fn(),amend:vi.fn(),cancel:vi.fn()}))
vi.mock('../auth/authState',()=>({useAuth:()=>({user:{id:7,username:'paper'},busy:false,holdSession:()=>()=>{}})}))
vi.mock('../api/externalMarket',()=>({getExternalQuote:vi.fn(async()=>({stale:false,data:{symbol:'AAPL',price:100,bid:99,ask:101}}))}))
vi.mock('../components/ExternalPriceChart',()=>({ExternalPriceChart:()=> <div>External price chart</div>}))
const result:api.PaperOrder={id:1,symbol:'AAPL',side:'BUY',type:'MARKET',originalQuantity:1,quantity:1,limitPrice:null,status:'FILLED',createdAt:'2026-11-06T14:00:00Z',updatedAt:'2026-11-06T14:00:00Z',executionPrice:101,quoteAt:'2026-11-06T14:00:00Z',executedAt:'2026-11-06T14:00:00Z'}
const balance:api.PaperPortfolio={cash:100000,reservedCash:0,availableCash:100000,equity:100000,realizedPnl:0,unrealizedPnl:0,positions:[]}
beforeEach(()=>{sessionStorage.clear();vi.mocked(api.portfolio).mockResolvedValue(balance);vi.mocked(api.orders).mockResolvedValue({content:[],page:0,size:25,totalElements:0,totalPages:0});vi.mocked(api.fills).mockResolvedValue([]);vi.mocked(api.submit).mockResolvedValue(result);vi.mocked(api.cancel).mockResolvedValue();vi.mocked(api.amend).mockResolvedValue()})
afterEach(()=>{cleanup();vi.resetAllMocks()})
it('offers all ten stocks without sandbox registration and shows external prices',async()=>{
 render(<PaperTrading view="trade"/>);expect(within(screen.getByLabelText('Paper trading stock')).getAllByRole('option')).toHaveLength(10)
 expect(screen.getByRole('option',{name:'NVDA'})).toBeTruthy();expect(screen.getByText('External price chart')).toBeTruthy()
 await screen.findByText('$99.00 / $101.00')
 expect(screen.getByText(/separate from Matching Sandbox/)).toBeTruthy()
})
it('submits exactly once with an idempotency key and refreshes history',async()=>{
 let finish!:(value:api.PaperOrder)=>void;vi.mocked(api.submit).mockImplementation(()=>new Promise(resolve=>{finish=resolve}))
 render(<PaperTrading view="trade"/>);const submit=screen.getByRole('button',{name:'Submit paper BUY order'})
 fireEvent.click(submit);fireEvent.click(submit)
 await waitFor(()=>expect(api.submit).toHaveBeenCalledOnce());expect((submit as HTMLButtonElement).disabled).toBe(true)
 const request=vi.mocked(api.submit).mock.calls[0][0];expect(request).toEqual(expect.objectContaining({symbol:'AAPL',type:'MARKET',quantity:1}));expect(request.key).toHaveLength(36)
 finish(result);await screen.findByText(/BUY AAPL order #1: FILLED/)
 await waitFor(()=>expect(sessionStorage.getItem('verdex:paper-request:7')).toBeNull())
})
it('sends limit price only for limit orders',async()=>{
 render(<PaperTrading view="trade"/>);fireEvent.change(screen.getByLabelText(/^Order type/),{target:{value:'LIMIT'}})
 fireEvent.change(screen.getByLabelText('Limit price'),{target:{value:'95.25'}})
 fireEvent.click(screen.getByRole('button',{name:'Submit paper BUY order'}))
 await waitFor(()=>expect(api.submit).toHaveBeenCalledWith(expect.objectContaining({type:'LIMIT',limitPrice:'95.25'})))
})
it('does not retry uncertain submissions automatically and reuses the original request explicitly',async()=>{
 vi.mocked(api.submit).mockRejectedValueOnce(new AuthError('Uncertain connection',0,true))
 render(<PaperTrading view="trade"/>);fireEvent.click(screen.getByRole('button',{name:'Submit paper BUY order'}))
 await screen.findByRole('button',{name:'Retry same request safely'});expect(api.submit).toHaveBeenCalledOnce()
 const request=vi.mocked(api.submit).mock.calls[0][0]
 fireEvent.click(screen.getByRole('button',{name:'Retry same request safely'}))
 await waitFor(()=>expect(api.submit).toHaveBeenCalledTimes(2));expect(vi.mocked(api.submit).mock.calls[1][0]).toEqual(request)
})
it('clears safely rejected requests and shows backend messages',async()=>{
 vi.mocked(api.submit).mockRejectedValueOnce(new AuthError('Insufficient paper cash',409,true))
 render(<PaperTrading view="trade"/>);fireEvent.click(screen.getByRole('button',{name:'Submit paper BUY order'}))
 await screen.findByText('Insufficient paper cash');expect(screen.queryByRole('button',{name:'Retry same request safely'})).toBeNull()
})
it('allows cancellation and amendment only on open orders',async()=>{
 vi.mocked(api.orders).mockResolvedValue({content:[{...result,type:'LIMIT',limitPrice:90,status:'OPEN',executionPrice:null},{...result,id:2}],page:0,size:25,totalElements:2,totalPages:1})
 render(<PaperTrading view="orders"/>);await screen.findByRole('button',{name:'Amend'});expect(screen.getAllByRole('button',{name:'Cancel'})).toHaveLength(1)
 fireEvent.click(screen.getByRole('button',{name:'Amend'}));const form=screen.getByRole('region',{name:'Amend paper limit order'})
 fireEvent.change(within(form).getByLabelText('New limit price'),{target:{value:'91'}})
 fireEvent.click(within(form).getByRole('button',{name:'Save amendment'}));await waitFor(()=>expect(api.amend).toHaveBeenCalledWith(1,'91',1))
 await waitFor(()=>expect(screen.getByRole('button',{name:'Cancel'}).hasAttribute('disabled')).toBe(false));fireEvent.click(screen.getByRole('button',{name:'Cancel'}));await waitFor(()=>expect(api.cancel).toHaveBeenCalledWith(1))
})
it('formats paper cash as USD and keeps missing valuation unknown',async()=>{
 vi.mocked(api.portfolio).mockResolvedValue({...balance,equity:null,unrealizedPnl:null})
 render(<PaperTrading view="portfolio"/>);await screen.findAllByText('$100,000.00');expect(screen.getAllByText('—')).toHaveLength(2)
 expect(screen.getByText(/No paper holdings yet/)).toBeTruthy()
})

it('preserves stock and order draft across paper views',async()=>{
 const view=render(<PaperTrading view="trade"/>);
 fireEvent.change(screen.getByLabelText('Paper trading stock'),{target:{value:'TSLA'}})
 fireEvent.change(screen.getByLabelText('Quantity'),{target:{value:'7'}})
 view.rerender(<PaperTrading view="orders"/>);await screen.findByRole('heading',{name:'My paper orders'})
 view.rerender(<PaperTrading view="trade"/>);
 expect((screen.getByLabelText('Paper trading stock') as HTMLSelectElement).value).toBe('TSLA')
 expect((screen.getByLabelText('Quantity') as HTMLInputElement).value).toBe('7')
})
it('filters OPEN orders on the server and resets pagination',async()=>{
 render(<PaperTrading view="orders"/>);await screen.findByText('No paper orders yet.')
 vi.mocked(api.orders).mockResolvedValue({content:[{...result,status:'OPEN',type:'LIMIT',limitPrice:90,executionPrice:null}],page:0,size:25,totalElements:1,totalPages:1})
 fireEvent.change(screen.getByLabelText('Paper order status'),{target:{value:'OPEN'}})
 await screen.findByText('Waiting for a qualifying IEX quote')
 expect(api.orders).toHaveBeenLastCalledWith(0,expect.any(AbortSignal),'OPEN')
})
it('refreshes order status after a background fill without a user mutation',async()=>{
 vi.useFakeTimers()
 try{
  vi.mocked(api.orders).mockResolvedValue({content:[{...result,status:'OPEN',type:'LIMIT',limitPrice:90,executionPrice:null}],page:0,size:25,totalElements:1,totalPages:1})
  render(<PaperTrading view="orders"/>);await act(async()=>{})
  expect(screen.getByText('OPEN')).toBeTruthy()
  vi.mocked(api.orders).mockResolvedValue({content:[result],page:0,size:25,totalElements:1,totalPages:1})
  await act(async()=>{await vi.advanceTimersByTimeAsync(5000)})
  expect(within(screen.getByRole('table')).getByText('FILLED')).toBeTruthy();expect(screen.queryByRole('button',{name:'Cancel'})).toBeNull()
 }finally{cleanup();vi.useRealTimers()}
})

it('shows a scoped active count and uses the server total for all open orders',async()=>{
 vi.mocked(api.orders).mockImplementation(async(page,_signal,status)=>({content:status==='OPEN'?[{...result,status:'OPEN',type:'LIMIT',limitPrice:90}]:[result],page,size:25,totalElements:status==='OPEN'?31:51,totalPages:3}))
 render(<PaperTrading view="orders"/>);
 await screen.findByText('0 open paper orders on this page')
 fireEvent.click(screen.getByRole('button',{name:'View all open paper orders'}))
 await screen.findByText('31 open paper orders')
 expect(screen.getByRole('link',{name:'Looking for sandbox orders?'}).getAttribute('href')).toBe('#/sandbox-orders')
})
