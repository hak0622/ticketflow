import { StrictMode } from 'react'
import { renderHook, act, cleanup } from '@testing-library/react'
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { usePaymentRecovery } from './usePaymentRecovery'
import { tossConfirm, getMyPayment, getBookingDetail } from '../booking/api'
vi.mock('../booking/api',()=>({tossConfirm:vi.fn(),getMyPayment:vi.fn(),getBookingDetail:vi.fn()}))
const args={concertId:'1',orderId:'fixture-order',paymentKey:'fixture-key',amountText:'100',user:'fixture',token:'fixture'}
const data={concertId:1,idempotencyKey:args.orderId,amount:100,paymentId:2,bookingId:3,status:'PENDING',recoveryPhase:'APPROVAL_CHECK'}
const response=(over={})=>({data:{...data,...over}})
const flush=async()=>act(async()=>{await Promise.resolve()})
const tick=async(ms)=>act(async()=>{await vi.advanceTimersByTimeAsync(ms)})
beforeEach(()=>{vi.useFakeTimers();vi.resetAllMocks();tossConfirm.mockResolvedValue(response());getMyPayment.mockResolvedValue(response());getBookingDetail.mockResolvedValue({data:{concertId:1,bookingId:3,status:'PENDING_PAYMENT'}})})
afterEach(()=>{cleanup();vi.useRealTimers()})
it('confirms and queries to completion without approving again',async()=>{
 getMyPayment.mockResolvedValueOnce(response()).mockResolvedValue(response({status:'COMPLETED'}))
 const {result}=renderHook(()=>usePaymentRecovery(args));await flush();await tick(5000)
 expect(result.current.payment.status).toBe('COMPLETED');await tick(20000)
 expect(tossConfirm).toHaveBeenCalledTimes(1);expect(getMyPayment).toHaveBeenCalledTimes(2)
})
it.each([undefined,408,409,429,500])('reads after uncertain approval %s',async(status)=>{
 tossConfirm.mockRejectedValue(status?{response:{status}}:new Error('timeout'))
 getMyPayment.mockResolvedValue(response({status:'COMPLETED'}))
 const {result}=renderHook(()=>usePaymentRecovery(args));await flush()
 expect(result.current.payment.status).toBe('COMPLETED');expect(tossConfirm).toHaveBeenCalledTimes(1)
})
it.each([400,401,403])('does not interpret approval %s as PG failure',async(status)=>{
 tossConfirm.mockRejectedValue({response:{status}})
 const {result}=renderHook(()=>usePaymentRecovery(args));await flush()
 expect(result.current.paused).toBe(true);expect(result.current.payment).toBeNull();expect(getMyPayment).not.toHaveBeenCalled()
})
it.each([{amount:200},{idempotencyKey:'other-order'},{concertId:2},{paymentId:8},{bookingId:9},{status:'UNKNOWN'}])('rejects changed identity %j',async(changes)=>{
 getMyPayment.mockResolvedValue(response(changes))
 const {result}=renderHook(()=>usePaymentRecovery(args));await flush()
 expect(result.current.reason).toBe('mismatch');expect(result.current.paused).toBe(true)
})
it.each(['CANCELED','FAILED'])('keeps %s while booking confirmation is unavailable',async(status)=>{
 tossConfirm.mockResolvedValue(response({status}));getBookingDetail.mockRejectedValue(new Error('offline'))
 const {result}=renderHook(()=>usePaymentRecovery(args));await flush();await tick(20000)
 expect(result.current.payment.status).toBe(status);expect(result.current.paused).toBe(true)
 getBookingDetail.mockResolvedValue({data:{concertId:1,bookingId:3,status:'CANCELLED'}})
 act(()=>{result.current.resume();result.current.resume()});await flush()
 expect(result.current.booking.status).toBe('CANCELLED');expect(result.current.checking).toBe(false);expect(tossConfirm).toHaveBeenCalledTimes(1)
})
it('shares deadline across payment and booking stages',async()=>{
 const {result}=renderHook(()=>usePaymentRecovery(args));await flush();await tick(590000)
 getMyPayment.mockResolvedValue(response({status:'CANCELED'}));await tick(5000);await tick(5000)
 expect(result.current.reason).toBe('deadline');expect(result.current.payment.status).toBe('CANCELED')
})
it('shares initial dispatch in StrictMode',async()=>{
 renderHook(()=>usePaymentRecovery(args),{wrapper:StrictMode});await flush();expect(tossConfirm).toHaveBeenCalledTimes(1)
})
it('does not restart approval for a changed user',async()=>{
 const {result,rerender}=renderHook(({user})=>usePaymentRecovery({...args,user}),{initialProps:{user:'fixture'}})
 await flush();rerender({user:'different'});await flush();await tick(20000)
 expect(result.current.reason).toBe('identity');expect(tossConfirm).toHaveBeenCalledTimes(1)
})
it('distinguishes missing payment and allows GET-only restart',async()=>{
 getMyPayment.mockRejectedValue({response:{status:400,data:{message:'결제 내역이 없습니다.'}}})
 const {result}=renderHook(()=>usePaymentRecovery(args));await flush();await tick(20000)
 expect(result.current.reason).toBe('absent');expect(result.current.paused).toBe(true)
 getMyPayment.mockResolvedValue(response({status:'COMPLETED'}))
 act(()=>{result.current.resume();result.current.resume()});await flush()
 expect(result.current.payment.status).toBe('COMPLETED');expect(tossConfirm).toHaveBeenCalledTimes(1)
})
it('discards late approval and GET results after order change and unmount',async()=>{
 let resolveOld; tossConfirm.mockImplementationOnce(()=>new Promise(r=>{resolveOld=r})).mockResolvedValue(response({idempotencyKey:'fixture-new'}))
 getMyPayment.mockResolvedValue(response({idempotencyKey:'fixture-new'}))
 const {result,rerender,unmount}=renderHook(({orderId})=>usePaymentRecovery({...args,orderId}),{initialProps:{orderId:args.orderId}})
 await flush();rerender({orderId:'fixture-new'});await flush()
 await act(async()=>resolveOld(response({status:'COMPLETED'})))
 expect(result.current.payment.idempotencyKey).toBe('fixture-new');expect(result.current.payment.status).toBe('PENDING')
 const calls=getMyPayment.mock.calls.length;unmount();await tick(20000);expect(getMyPayment).toHaveBeenCalledTimes(calls)
})
it.each(['CONFIRMED','UNKNOWN'])('rejects inconsistent booking status %s',async(status)=>{
 tossConfirm.mockResolvedValue(response({status:'CANCELED'}));getBookingDetail.mockResolvedValue({data:{concertId:1,bookingId:3,status}})
 const {result}=renderHook(()=>usePaymentRecovery(args));await flush()
 expect(result.current.payment.status).toBe('CANCELED');expect(result.current.reason).toBe('mismatch')
})
it('does not share a pending approval with different amount',async()=>{
 let firstResolve;tossConfirm.mockImplementationOnce(()=>new Promise(r=>{firstResolve=r})).mockResolvedValue(response({amount:200,status:'COMPLETED'}))
 const one=renderHook(()=>usePaymentRecovery(args));await flush()
 const two=renderHook(()=>usePaymentRecovery({...args,amountText:'200'}));await flush()
 expect(tossConfirm).toHaveBeenCalledTimes(2);expect(two.result.current.payment.amount).toBe(200)
 await act(async()=>firstResolve(response({status:'COMPLETED'})));one.unmount();two.unmount()
})
it('shares a genuinely pending approval across concurrent subscribers',async()=>{
 let resolve;tossConfirm.mockImplementation(()=>new Promise(r=>{resolve=r}))
 const a=renderHook(()=>usePaymentRecovery(args));const b=renderHook(()=>usePaymentRecovery(args));await flush()
 expect(tossConfirm).toHaveBeenCalledTimes(1)
 await act(async()=>resolve(response({status:'COMPLETED'})))
 expect(a.result.current.payment.status).toBe('COMPLETED');expect(b.result.current.payment.status).toBe('COMPLETED')
})
it('ignores a GET response after its generation has been cancelled',async()=>{
 let late;getMyPayment.mockImplementationOnce(()=>new Promise(r=>{late=r})).mockResolvedValue(response({idempotencyKey:'fixture-new'}))
 const {result,rerender}=renderHook(({orderId})=>usePaymentRecovery({...args,orderId}),{initialProps:{orderId:args.orderId}})
 await flush();const signal=getMyPayment.mock.calls[0][1].signal
 tossConfirm.mockResolvedValue(response({idempotencyKey:'fixture-new'}));rerender({orderId:'fixture-new'});await flush()
 expect(signal.aborted).toBe(true)
 await act(async()=>late(response({status:'COMPLETED'})))
 expect(result.current.payment.idempotencyKey).toBe('fixture-new');expect(result.current.payment.status).toBe('PENDING')
})
