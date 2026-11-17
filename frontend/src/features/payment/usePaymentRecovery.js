import { useEffect, useRef, useState, useCallback } from 'react'
import { tossConfirm, getMyPayment, getBookingDetail } from '../booking/api'

const approvals = new Map()
function approveOnce(key, concertId, body) {
  if (!approvals.has(key)) {
    const promise = tossConfirm(concertId, body)
    approvals.set(key, promise)
    const remove = () => { if (approvals.get(key) === promise) approvals.delete(key) }
    promise.then(remove, remove)
  }
  return approvals.get(key)
}
const positive = (n) => Number.isSafeInteger(n) && n > 0
const temporary = (error) => !error.response || [408, 409, 429].includes(error.response.status) || error.response.status >= 500
const initial = { payment: null, booking: null, checking: true, paused: false, reason: null, lastUpdatedAt: null }

export function usePaymentRecovery({ concertId, orderId, paymentKey, amountText, user, token }) {
  const [state, setState] = useState(initial)
  const owner = useRef(user)
  const resumeRef = useRef(null)
  useEffect(() => {
    let alive = true, busy = false, paused = false, finished = false, failures = 0
    let timer, deadlineTimer, deadline = 0, controller, payment = null, booking = null, identity = null
    const publish = (change) => { if (alive) setState(previous => ({ ...previous, payment, booking, ...change })) }
    const stopTimers = () => { clearTimeout(timer); clearTimeout(deadlineTimer) }
    const pause = (reason) => { paused = true; stopTimers(); publish({ checking: false, paused: true, reason }) }
    const beginWindow = () => {
      stopTimers(); deadline = Date.now() + 600000
      deadlineTimer = setTimeout(() => { controller?.abort(); pause('deadline') }, 600000)
    }
    const schedule = (ms) => { if (Date.now() >= deadline) pause('deadline'); else timer = setTimeout(read, ms) }
    const invalid = () => { const e = new Error('Payment response mismatch'); e.invalid = true; throw e }
    const applyPayment = (data) => {
      const amount = Number(amountText)
      if (!data || Number(data.concertId) !== Number(concertId) || data.idempotencyKey !== orderId || data.amount !== amount
          || !positive(data.paymentId) || !positive(data.bookingId) || !['PENDING','COMPLETED','CANCELED','FAILED'].includes(data.status)) invalid()
      if (identity && (identity.paymentId !== data.paymentId || identity.bookingId !== data.bookingId)) invalid()
      identity = { paymentId: data.paymentId, bookingId: data.bookingId }
      payment = data
      publish({ lastUpdatedAt: Date.now() })
    }
    const applyBooking = (data) => {
      if (!data || data.bookingId !== identity.bookingId || Number(data.concertId) !== Number(concertId)
          || !['PENDING_PAYMENT','CANCELLED'].includes(data.status)) invalid()
      booking = data; publish({ lastUpdatedAt: Date.now() })
    }
    const settle = () => {
      if (payment?.status === 'COMPLETED' || booking?.status === 'CANCELLED') {
        finished = true; stopTimers(); publish({ checking: false, paused: false, reason: null }); return true
      }
      return false
    }
    const onFailure = (error) => {
      const status = error.response?.status
      const absent = status === 400 && (error.response.data?.message ?? error.response.data?.error) === '결제 내역이 없습니다.'
      if (error.invalid) pause('mismatch')
      else if (status === 401 || status === 403) pause(status === 401 ? 'auth' : 'forbidden')
      else if (temporary(error) || absent) {
        failures++
        if (failures >= 3) pause(absent ? 'absent' : 'network')
        else { publish({ checking: true, reason: absent ? 'absent' : 'network' }); schedule(10000) }
      } else pause('request')
    }
    async function read() {
      if (!alive || finished || busy || paused) return
      busy = true; controller = new AbortController()
      try {
        if (payment && ['CANCELED','FAILED'].includes(payment.status)) {
          const response = await getBookingDetail(concertId, { signal: controller.signal })
          if (!alive || paused) return
          applyBooking(response.data)
        } else {
          const response = await getMyPayment(concertId, { signal: controller.signal })
          if (!alive || paused) return
          applyPayment(response.data)
        }
        failures = 0
        if (!settle()) { publish({ checking: true, reason: null }); schedule(5000) }
      } catch (error) {
        if (alive && !paused && !controller.signal.aborted && error.code !== 'ERR_CANCELED') onFailure(error)
      } finally { busy = false }
    }
    resumeRef.current = () => {
      if (!alive || busy || !paused || finished || owner.current !== user) return
      paused = false; failures = 0; beginWindow(); publish({ checking: true, paused: false, reason: null }); read()
    }
    async function start() {
      if (!alive) return
      if (!token || owner.current !== user) { pause('identity'); return }
      const amount = Number(amountText)
      if (!positive(Number(concertId)) || !/^\d+$/.test(amountText ?? '') || !positive(amount) || amount > 2147483647
          || !orderId || orderId.length < 6 || orderId.length > 64 || !paymentKey?.trim() || paymentKey.length > 200) { pause('invalid'); return }
      try {
        const key = JSON.stringify([user, token, concertId, orderId, paymentKey, amount])
        const response = await approveOnce(key, concertId, { paymentKey, orderId, amount })
        if (!alive) return
        beginWindow(); applyPayment(response.data)
        if (!settle()) { publish({ checking: true, paused: false, reason: null }); read() }
      } catch (error) {
        if (!alive) return
        beginWindow()
        if (!error.invalid && temporary(error)) read()
        else onFailure(error)
      }
    }
    Promise.resolve().then(start)
    return () => { alive = false; stopTimers(); controller?.abort(); resumeRef.current = null }
  }, [concertId, orderId, paymentKey, amountText, user, token])
  const resume = useCallback(() => resumeRef.current?.(), [])
  return { ...state, resume }
}
