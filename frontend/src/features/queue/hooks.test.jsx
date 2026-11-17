import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { renderHook, act, cleanup, render, screen, fireEvent } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { useQueuePolling } from './hooks'
import QueuePage from '../../pages/QueuePage'
import { getQueueStatus } from './api'
vi.mock('./api', () => ({ getQueueStatus: vi.fn() }))
const identity=vi.hoisted(()=>({token:'fixture',user:{sub:'test'}}))
vi.mock('../auth/store', () => ({ default: () => identity }))
const queued = { status: 'QUEUED', position: 5, total: 20 }
const respond = (data) => Promise.resolve({ data })
const flush = async () => { await act(async () => { await Promise.resolve() }) }
const tick = async (ms) => { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }
beforeEach(() => { identity.token='fixture';identity.user={sub:'test'};vi.useFakeTimers(); vi.spyOn(Math, 'random').mockReturnValue(0.5); getQueueStatus.mockReset() })
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks() })
it('stops after three errors, retains last success, resumes once without overlapping', async () => {
 getQueueStatus.mockResolvedValueOnce({data:queued}).mockRejectedValue(new Error('offline'))
 const { result }=renderHook(()=>useQueuePolling('1'))
 await flush(); const updated=result.current.lastUpdatedAt
 await tick(5000); expect(result.current.connection).toBe('retrying')
 await tick(10000); await tick(10000); expect(result.current.connection).toBe('paused')
 expect(result.current.lastUpdatedAt).toBe(updated);expect(result.current.data).toEqual(queued)
 await tick(30000);expect(getQueueStatus).toHaveBeenCalledTimes(4)
 let resolve; getQueueStatus.mockImplementationOnce(()=>new Promise(r=>{resolve=r}))
 act(()=>{result.current.resume();result.current.resume()})
 expect(getQueueStatus).toHaveBeenCalledTimes(5);expect(result.current.connection).toBe('checking')
 await act(async()=>resolve({data:queued}));expect(result.current.connection).toBe('polling')
})
it.each([401,403,404,400])('does not retry HTTP %s', async(code)=>{
 getQueueStatus.mockRejectedValue({response:{status:code}})
 const {result}=renderHook(()=>useQueuePolling('1'));await flush();await tick(30000)
 expect(getQueueStatus).toHaveBeenCalledTimes(1);expect(result.current.connection).toBe(code===401?'auth_error':'paused')
})
it.each([408,429,500])('retries temporary HTTP %s',async(code)=>{
 getQueueStatus.mockRejectedValue({response:{status:code}})
 const {result}=renderHook(()=>useQueuePolling('1'));await flush();await tick(20000)
 expect(getQueueStatus).toHaveBeenCalledTimes(3);expect(result.current.connection).toBe('paused')
})
it.each([{status:'WHAT'}, {status:'QUEUED',position:0},null])('pauses malformed replies',async(data)=>{
 getQueueStatus.mockImplementation(()=>respond(data))
 const {result}=renderHook(()=>useQueuePolling('1'));await flush()
 expect(result.current.reason).toBe('invalid');expect(result.current.lastUpdatedAt).toBeNull()
})
it.each(['ADMITTED','BOOKED','NOT_IN_QUEUE'])('finishes on %s',async(status)=>{
 const admitted=vi.fn();getQueueStatus.mockImplementation(()=>respond({status}))
 const {result}=renderHook(()=>useQueuePolling('1',{onAdmitted:admitted}));await flush();await tick(30000)
 expect(result.current.connection).toBe('finished');expect(getQueueStatus).toHaveBeenCalledTimes(1)
 expect(admitted).toHaveBeenCalledTimes(status==='ADMITTED'?1:0)
})
it('cancels old generations without applying their late replies',async()=>{
 let oldResolve;getQueueStatus.mockImplementationOnce(()=>new Promise(r=>{oldResolve=r})).mockResolvedValue({data:queued})
 const admitted=vi.fn();const {result,rerender,unmount}=renderHook(({id})=>useQueuePolling(id,{onAdmitted:admitted}),{initialProps:{id:'1'}})
 await flush();const signal=getQueueStatus.mock.calls[0][1].signal
 rerender({id:'2'});await flush();expect(signal.aborted).toBe(true)
 await act(async()=>oldResolve({data:{status:'ADMITTED'}}));expect(result.current.data).toEqual(queued);expect(admitted).not.toHaveBeenCalled()
 unmount();await tick(30000);expect(getQueueStatus).toHaveBeenCalledTimes(2)
})
it('StrictMode leaves one uncancelled request',async()=>{
 getQueueStatus.mockResolvedValue({data:queued})
 renderHook(()=>useQueuePolling('1'),{wrapper:StrictMode});await flush()
 expect(getQueueStatus).toHaveBeenCalledTimes(1)
})
it('shows stopped polling and resumes the same GET from the page',async()=>{
 getQueueStatus.mockResolvedValueOnce({data:queued}).mockRejectedValue(new Error('offline'))
 render(<MemoryRouter><QueuePage/></MemoryRouter>);await flush();await tick(5000);await tick(20000)
 expect(screen.getByText('연결 오류로 자동 조회를 중단했습니다.')).toBeTruthy()
 expect(screen.getByText('마지막 확인 순번: 5명')).toBeTruthy()
 getQueueStatus.mockResolvedValue({data:{status:'ADMITTED'}})
 fireEvent.click(screen.getByRole('button',{name:'순번 다시 확인'}));await flush()
 expect(screen.getByText('입장 가능!')).toBeTruthy()
})
it('first-load failures have no invented queue position',async()=>{
 getQueueStatus.mockRejectedValue(new Error('offline'))
 const {result}=renderHook(()=>useQueuePolling('1'));await flush();await tick(20000)
 expect(result.current.data).toBeNull();expect(result.current.lastUpdatedAt).toBeNull();expect(result.current.connection).toBe('paused')
})
it('success clears consecutive failure count',async()=>{
 getQueueStatus.mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce({data:queued}).mockRejectedValue(new Error('offline'))
 const {result}=renderHook(()=>useQueuePolling('1'));await flush();await tick(10000);await tick(5000);await tick(10000)
 expect(result.current.connection).toBe('retrying');await tick(10000);expect(result.current.connection).toBe('paused')
})
it.each([[5,0,4750],[1001,0.999,10250],[5,0.5,7000]])('preserves interval and jitter boundaries',async(position,random,delay)=>{
 Math.random.mockReturnValue(random)
 getQueueStatus.mockResolvedValue({data:{...queued,position,...(delay===7000?{nextPollMs:7000}:{})}})
 renderHook(()=>useQueuePolling('1'));await flush();await tick(delay-1);expect(getQueueStatus).toHaveBeenCalledTimes(1)
 await tick(1);expect(getQueueStatus).toHaveBeenCalledTimes(2)
})

it('does not query when logged out',async()=>{
 identity.token=null
 render(<MemoryRouter><QueuePage/></MemoryRouter>);await flush()
 expect(getQueueStatus).not.toHaveBeenCalled()
})
it('user changes discard an admitted countdown',async()=>{
 getQueueStatus.mockResolvedValueOnce({data:{status:'ADMITTED'}}).mockResolvedValue({data:queued})
 const content=()=> <MemoryRouter initialEntries={['/concerts/1/queue']}><Routes>
   <Route path="/concerts/:id/queue" element={<QueuePage/>}/>
   <Route path="/concerts/:id/booking" element={<p>payment destination fixture</p>}/>
 </Routes></MemoryRouter>
 const view=render(content());await flush();await tick(2000)
 identity.user={sub:'other'};view.rerender(content());await flush();await tick(2000)
 expect(screen.queryByText('payment destination fixture')).toBeNull()
 expect(getQueueStatus).toHaveBeenCalledTimes(2)
})
