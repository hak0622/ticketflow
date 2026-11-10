import { useEffect, useState } from 'react'
import { useSearchParams, useNavigate, Link } from 'react-router-dom'
import { tossConfirm } from '../features/booking/api'
import useAuthStore from '../features/auth/store'
import LoadingSpinner from '../shared/ui/LoadingSpinner'

/**
 * Toss Payments 결제 성공 콜백 페이지
 * Toss 가 리다이렉트: /payment/success?paymentKey=...&orderId=...&amount=...
 * 우리가 추가한 쿼리: &concertId=...
 */
export default function PaymentSuccessPage() {
  const [searchParams] = useSearchParams()
  const navigate        = useNavigate()
  const { token }       = useAuthStore()

  const paymentKey = searchParams.get('paymentKey')
  const orderId    = searchParams.get('orderId')
  const amount     = Number(searchParams.get('amount'))
  const concertId  = searchParams.get('concertId')

  const [status, setStatus]   = useState('loading') // loading | success | error
  const [errorMsg, setErrorMsg] = useState('')

  useEffect(() => {
    if (!token) {
      navigate('/login')
      return
    }

    if (!paymentKey || !orderId || !amount || !concertId) {
      setStatus('error')
      setErrorMsg('잘못된 접근입니다.')
      return
    }

    tossConfirm(concertId, { paymentKey, orderId, amount })
      .then(({ data }) => {
        if (data?.status === 'COMPLETED') {
          setStatus('success')
        } else if (data?.status === 'CANCELED') {
          setStatus('canceled')
          setErrorMsg('결제가 취소되었습니다. 내 예매 목록에서 예약 상태를 확인해주세요.')
        } else if (data?.status === 'FAILED') {
          setStatus('error')
          setErrorMsg('결제가 실패했습니다. 내 예매 목록에서 현재 상태를 확인해주세요.')
        } else {
          setStatus('unresolved')
          setErrorMsg(data?.status === 'PENDING'
            ? (data?.recoveryPhase?.startsWith('CANCEL') ? '결제 취소 결과를 확인 중입니다. 아직 취소가 완료되지 않았습니다.' : '결제가 처리 중입니다. 아직 예매가 확정되지 않았습니다.')
            : '결제 결과를 확인하지 못했습니다. 내 예매 목록에서 확인해주세요.')
        }
      })
      .catch((err) => {
        setStatus('error')
        const httpStatus = err.response?.status
        const msg        = err.response?.data?.message ?? err.response?.data?.error
        if (!err.response) {
          setStatus('unresolved')
          setErrorMsg('연결 오류로 결제 결과를 확인하지 못했습니다. 내 예매 목록에서 확인해주세요.')
        } else if (httpStatus === 409) {
          setErrorMsg('이미 처리된 결제입니다. 내 예매 목록을 확인해주세요.')
        } else if (httpStatus === 400) {
          setErrorMsg(msg || '결제 정보가 올바르지 않습니다.')
        } else if (httpStatus === 403) {
          setErrorMsg(msg || '결제 권한이 없습니다.')
        } else {
          setStatus('unresolved')
          setErrorMsg('결제 결과를 확인하지 못했습니다. 내 예매 목록에서 확인해주세요.')
        }
      })
  }, []) // eslint-disable-line

  /* ── 로딩 ── */
  if (status === 'loading') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-4">
        <LoadingSpinner size="lg" />
        <p className="text-sm text-gray-500">결제 승인 처리 중입니다...</p>
      </div>
    )
  }

  /* ── 승인 완료 ── */
  if (status === 'success') {
    return (
      <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
        <div className="w-24 h-24 rounded-full ring-4 ring-green-200 bg-green-50 flex items-center justify-center text-5xl">
          🎫
        </div>
        <div>
          <p className="text-2xl font-black text-green-600 mb-2">결제 완료!</p>
          <p className="text-sm text-gray-500 leading-relaxed">
            예매가 확정되었습니다.<br />
            내 예매 목록에서 티켓을 확인하세요.
          </p>
        </div>
        <div className="flex flex-col gap-3 w-full max-w-xs">
          <Link
            to="/my-bookings"
            className="w-full py-3.5 bg-primary-500 hover:bg-primary-600 text-white font-bold text-center rounded-xl transition-colors"
          >
            내 예매 확인하기
          </Link>
          <Link
            to="/"
            className="w-full py-3 border-2 border-gray-200 hover:border-primary-300 text-gray-600 font-semibold text-center rounded-xl transition-colors"
          >
            홈으로
          </Link>
        </div>
      </div>
    )
  }

  /* ── 승인 실패 ── */
  return (
    <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
      <div className="w-24 h-24 rounded-full ring-4 ring-red-200 bg-red-50 flex items-center justify-center text-5xl">
        ⚠️
      </div>
      <div>
        <p className="text-xl font-bold text-red-600 mb-2">{status === 'canceled' ? '결제가 취소되었습니다' : status === 'unresolved' ? '결제 결과 확인이 필요합니다' : '결제 승인에 실패했습니다'}</p>
        <p className="text-sm text-gray-400 leading-relaxed">{errorMsg}</p>
      </div>
      <div className="flex flex-col gap-3 w-full max-w-xs">
        <Link to="/my-bookings" className="px-6 py-3 bg-primary-500 text-white font-semibold rounded-xl text-center">내 예매 상태 확인</Link>
        {concertId && status === 'error' && (
          <Link
            to={`/concerts/${concertId}/payment`}
            className="w-full py-3.5 bg-primary-500 hover:bg-primary-600 text-white font-bold text-center rounded-xl transition-colors"
          >
            다시 시도하기
          </Link>
        )}
        <Link
          to="/"
          className="w-full py-3 border-2 border-gray-200 hover:border-primary-300 text-gray-600 font-semibold text-center rounded-xl transition-colors"
        >
          홈으로
        </Link>
      </div>
    </div>
  )
}
