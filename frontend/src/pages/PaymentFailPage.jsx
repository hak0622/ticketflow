import { useSearchParams, Link, useNavigate } from 'react-router-dom'

/**
 * Toss Payments 결제 실패/취소 콜백 페이지
 * Toss 가 리다이렉트: /payment/fail?code=...&message=...&orderId=...
 * 우리가 추가한 쿼리: &concertId=...
 */
export default function PaymentFailPage() {
  const [searchParams] = useSearchParams()
  const navigate        = useNavigate()

  const code      = searchParams.get('code')
  const message   = searchParams.get('message')
  const concertId = searchParams.get('concertId')

  const isUserCancel = code === 'PAY_PROCESS_CANCELED'

  return (
    <div className="min-h-[calc(100svh-56px)] flex flex-col items-center justify-center gap-5 px-4 text-center pb-20 md:pb-0">
      <div className="w-24 h-24 rounded-full ring-4 ring-red-200 bg-red-50 flex items-center justify-center text-5xl">
        {isUserCancel ? '🔙' : '⚠️'}
      </div>

      <div>
        <p className="text-xl font-bold text-gray-800 mb-2">
          {isUserCancel ? '결제를 취소했습니다' : '결제에 실패했습니다'}
        </p>
        {message && !isUserCancel && (
          <p className="text-sm text-gray-400 leading-relaxed">{message}</p>
        )}
        {isUserCancel && (
          <p className="text-sm text-gray-400">언제든지 다시 시도할 수 있습니다.</p>
        )}
      </div>

      <div className="flex flex-col gap-3 w-full max-w-xs">
        {concertId && (
          <button
            onClick={() => navigate(`/concerts/${concertId}/payment`)}
            className="w-full py-3.5 bg-primary-500 hover:bg-primary-600 text-white font-bold rounded-xl transition-colors"
          >
            다시 시도하기
          </button>
        )}
        <Link
          to={concertId ? `/concerts/${concertId}` : '/'}
          className="w-full py-3 border-2 border-gray-200 hover:border-primary-300 text-gray-600 font-semibold text-center rounded-xl transition-colors"
        >
          공연 페이지로 돌아가기
        </Link>
      </div>
    </div>
  )
}
