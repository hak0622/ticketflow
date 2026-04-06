import { useState, useEffect, useRef, useCallback } from 'react'
import { getQueueStatus } from '../api/booking'

/**
 * 대기열 상태를 5초마다 폴링하는 훅
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
export function useQueuePolling(concertId, { interval = 5000, onAdmitted } = {}) {
  const [data, setData]       = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState(null)
  const timerRef              = useRef(null)
  const stoppedRef            = useRef(false)   // 중단 플래그
  const errorCountRef         = useRef(0)        // 연속 에러 횟수
  const MAX_ERRORS            = 3
  const onAdmittedRef         = useRef(onAdmitted)

  // onAdmitted가 렌더마다 새 함수여도 stale closure 방지
  useEffect(() => { onAdmittedRef.current = onAdmitted }, [onAdmitted])

  const stopPolling = useCallback(() => {
    stoppedRef.current = true
    clearInterval(timerRef.current)
  }, [])

  const poll = useCallback(async () => {
    if (stoppedRef.current) return
    try {
      const res = await getQueueStatus(concertId)
      const resData = res.data
      setData(resData)
      setError(null)
      errorCountRef.current = 0

      // 터미널 상태 → 즉시 폴링 중단
      if (resData.status === 'ADMITTED') {
        stopPolling()
        onAdmittedRef.current?.()   // 콜백 즉시 실행
      } else if (resData.status === 'BOOKED' || resData.status === 'NOT_IN_QUEUE') {
        stopPolling()
      }
    } catch (err) {
      errorCountRef.current += 1
      if (errorCountRef.current >= MAX_ERRORS) {
        stopPolling()
      }
      setError(err)
    } finally {
      setLoading(false)
    }
  }, [concertId, stopPolling])

  useEffect(() => {
    stoppedRef.current = false
    poll()  // 즉시 1회 호출
    timerRef.current = setInterval(poll, interval)
    return () => {
      stoppedRef.current = true
      clearInterval(timerRef.current)
    }
  }, [poll, interval])

  return { data, loading, error }
}
