import { useState, useEffect, useRef, useCallback } from 'react'
import { getQueueStatus } from './api'

const FAST_POLL_INTERVAL_MS = 5000
const DEFAULT_POLL_INTERVAL_MS = 10000

function getNextPollInterval(status, position) {
  if (status !== 'QUEUED') {
    return null
  }

  if (typeof position !== 'number' || !Number.isFinite(position) || position <= 0) {
    return DEFAULT_POLL_INTERVAL_MS
  }

  return position <= 1000 ? FAST_POLL_INTERVAL_MS : DEFAULT_POLL_INTERVAL_MS
}

/**
 * 대기열 서버 상태를 순번에 따라 차등 폴링하는 훅
 *
 * 반환값:
 *  - data   : { concertId, status, position?, total?, message? }
 *  - loading: 첫 응답 전 true
 *  - error  : 네트워크/서버 오류
 *
 * status 값:
 *  QUEUED        - 대기 중 (position: 내 순번, total: 전체 대기 수)
 *  ADMITTED      - 입장 가능 → onAdmitted 콜백 즉시 호출
 *  BOOKED        - 이미 예매 완료
 *  NOT_IN_QUEUE  - 대기열 없음 또는 만료
 */
export function useQueuePolling(concertId, { onAdmitted } = {}) {
  const [data, setData]       = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState(null)
  const timerRef              = useRef(null)
  const stoppedRef            = useRef(false)
  const errorCountRef         = useRef(0)
  const MAX_ERRORS            = 3
  const onAdmittedRef         = useRef(onAdmitted)

  useEffect(() => { onAdmittedRef.current = onAdmitted }, [onAdmitted])

  const stopPolling = useCallback(() => {
    stoppedRef.current = true
    clearTimeout(timerRef.current)
  }, [])

  const scheduleNextPoll = useCallback((delay) => {
    if (stoppedRef.current || delay == null) return

    clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      pollRef.current?.()
    }, delay)
  }, [])

  const pollRef = useRef(null)

  const poll = useCallback(async () => {
    if (stoppedRef.current) return
    try {
      const res = await getQueueStatus(concertId)
      const resData = res.data
      setData(resData)
      setError(null)
      errorCountRef.current = 0

      // 서버가 더 이상 대기열 진행 상태가 아니라고 응답하면 폴링 종료
      if (resData.status === 'ADMITTED') {
        stopPolling()
        onAdmittedRef.current?.()
      } else if (resData.status === 'BOOKED' || resData.status === 'NOT_IN_QUEUE') {
        stopPolling()
      } else {
        scheduleNextPoll(getNextPollInterval(resData.status, resData.position))
      }
    } catch (err) {
      errorCountRef.current += 1
      if (errorCountRef.current >= MAX_ERRORS) {
        stopPolling()
      } else {
        scheduleNextPoll(DEFAULT_POLL_INTERVAL_MS)
      }
      setError(err)
    } finally {
      setLoading(false)
    }
  }, [concertId, scheduleNextPoll, stopPolling])

  useEffect(() => {
    pollRef.current = poll
  }, [poll])

  useEffect(() => {
    stoppedRef.current = false
    setLoading(true)
    setError(null)
    errorCountRef.current = 0
    poll()
    return () => {
      stoppedRef.current = true
      clearTimeout(timerRef.current)
    }
  }, [poll])

  return { data, loading, error }
}
