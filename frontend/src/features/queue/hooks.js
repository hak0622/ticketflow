import { useState, useEffect, useRef, useCallback } from 'react'
import { getQueueStatus } from './api'

const jitter = (ms) => ms + Math.floor(Math.random() * 501) - 250
const valid = (data) => data && (data.status === 'QUEUED'
  ? Number.isInteger(data.position) && data.position > 0
  : ['ADMITTED', 'BOOKED', 'NOT_IN_QUEUE'].includes(data.status))

export function useQueuePolling(concertId, { onAdmitted } = {}) {
  const [state, setState] = useState({ data: null, loading: true, error: null,
    connection: 'initial', reason: null, lastUpdatedAt: null })
  const callback = useRef(onAdmitted)
  const resumeRef = useRef(null)
  useEffect(() => { callback.current = onAdmitted }, [onAdmitted])
  useEffect(() => {
    let active = true, finished = false, busy = false, failures = 0, timer, controller
    let paused = false
    const publish = (change) => { if (active) setState((previous) => ({ ...previous, ...change })) }
    const schedule = (delay) => { clearTimeout(timer); timer = setTimeout(poll, jitter(delay)) }
    async function poll() {
      if (!active || finished || busy) return
      busy = true
      controller = new AbortController()
      try {
        const { data } = await getQueueStatus(concertId, { signal: controller.signal })
        if (!active) return
        if (!valid(data)) {
          paused = true
          publish({ loading: false, connection: 'paused', reason: 'invalid', error: new Error('Invalid queue response') })
          return
        }
        failures = 0; paused = false
        finished = data.status !== 'QUEUED'
        publish({ data, loading: false, error: null, reason: null,
          connection: finished ? 'finished' : 'polling', lastUpdatedAt: Date.now() })
        if (finished) { if (data.status === 'ADMITTED') callback.current?.() }
        else schedule(Number.isFinite(data.nextPollMs) && data.nextPollMs > 0
          ? data.nextPollMs : data.position <= 1000 ? 5000 : 10000)
      } catch (error) {
        if (!active || controller.signal.aborted || error.code === 'ERR_CANCELED') return
        const code = error.response?.status
        const retryable = !code || code === 408 || code === 429 || code >= 500
        failures++
        paused = !retryable || failures >= 3
        const reason = code === 401 ? 'auth' : code === 403 ? 'forbidden' : retryable ? 'network' : 'request'
        publish({ loading: false, error, reason,
          connection: code === 401 ? 'auth_error' : paused ? 'paused' : 'retrying' })
        if (!paused) schedule(10000)
      } finally { busy = false }
    }
    resumeRef.current = () => {
      if (!active || finished || busy || !paused) return
      clearTimeout(timer); failures = 0; paused = false
      publish({ connection: 'checking', reason: null, error: null })
      poll()
    }
    // StrictMode cleanup runs before this initial request can start.
    Promise.resolve().then(poll)
    return () => { active = false; clearTimeout(timer); controller?.abort(); resumeRef.current = null }
  }, [concertId])
  const resume = useCallback(() => resumeRef.current?.(), [])
  return { ...state, resume }
}
