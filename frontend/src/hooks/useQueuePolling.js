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
 *  ADMITTED      - 입장 가능 (예매 페이지로 이동 가능)
 *  BOOKED        - 이미 예매 완료
 *  NOT_IN_QUEUE  - 대기열 없음 또는 만료
 */
export function useQueuePolling(concertId, interval = 5000) {
  const [data, setData]       = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState(null)
  const timerRef              = useRef(null)

  const poll = useCallback(async () => {
    try {
      const res = await getQueueStatus(concertId)
      setData(res.data)
      setError(null)
    } catch (err) {
      setError(err)
    } finally {
      setLoading(false)
    }
  }, [concertId])

  useEffect(() => {
    poll() // 즉시 1회 호출
    timerRef.current = setInterval(poll, interval)
    return () => clearInterval(timerRef.current)
  }, [poll, interval])

  // ADMITTED / BOOKED 상태가 되면 폴링 중단
  useEffect(() => {
    const terminal = ['ADMITTED', 'BOOKED']
    if (data && terminal.includes(data.status)) {
      clearInterval(timerRef.current)
    }
  }, [data])

  return { data, loading, error }
}
