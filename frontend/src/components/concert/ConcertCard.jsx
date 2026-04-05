import { useNavigate } from 'react-router-dom'

const STATUS_LABEL = {
  OPEN:     { text: '예매 가능', cls: 'bg-primary-100 text-primary-700' },
  SOLD_OUT: { text: '매진',     cls: 'bg-red-100 text-red-600' },
  CLOSED:   { text: '마감',     cls: 'bg-gray-100 text-gray-500' },
}

function formatDate(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return d.toLocaleDateString('ko-KR', {
    year:   'numeric',
    month:  'long',
    day:    'numeric',
    weekday: 'short',
  })
}

function formatPrice(price) {
  if (price == null) return '가격 미정'
  return `₩${Number(price).toLocaleString('ko-KR')}`
}

export default function ConcertCard({ concert }) {
  const navigate = useNavigate()
  const { id, title, artist, eventAt, price, status, posterUrl, totalSeats, bookedCount } = concert
  const remaining = totalSeats - bookedCount
  const badge = STATUS_LABEL[status] ?? STATUS_LABEL.CLOSED

  return (
    <article
      onClick={() => navigate(`/concerts/${id}`)}
      className="group cursor-pointer bg-white rounded-2xl overflow-hidden shadow-sm hover:shadow-md border border-gray-100 transition-shadow duration-200"
    >
      {/* 포스터 이미지 */}
      <div className="relative aspect-[3/4] bg-gray-100 overflow-hidden">
        {posterUrl ? (
          <img
            src={posterUrl}
            alt={title}
            className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
          />
        ) : (
          <div className="w-full h-full flex flex-col items-center justify-center gap-2 bg-gradient-to-br from-gray-100 to-gray-200">
            <span className="text-4xl">🎵</span>
            <span className="text-xs text-gray-400">포스터 없음</span>
          </div>
        )}

        {/* 상태 배지 */}
        <span
          className={`absolute top-2 left-2 text-xs font-semibold px-2 py-0.5 rounded-full ${badge.cls}`}
        >
          {badge.text}
        </span>
      </div>

      {/* 정보 영역 */}
      <div className="p-3">
        {artist && (
          <p className="text-xs text-primary-600 font-semibold truncate mb-0.5">{artist}</p>
        )}
        <h3 className="font-bold text-gray-900 text-sm leading-snug truncate mb-1">{title}</h3>

        <p className="text-xs text-gray-500 truncate">
          {formatDate(eventAt)}
        </p>

        <div className="mt-2 flex items-center justify-between">
          <span className="text-sm font-bold text-gray-900">{formatPrice(price)}</span>
          {status === 'OPEN' && (
            <span className="text-xs text-gray-400">잔여 {remaining}석</span>
          )}
        </div>
      </div>
    </article>
  )
}
