import { useNavigate } from 'react-router-dom'
import ConcertPrice from './ConcertPrice'

function formatDate(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return d.toLocaleDateString('ko-KR', {
    year: 'numeric', month: '2-digit', day: '2-digit',
  })
}

/**
 * ConcertCard — Trending 그리드용 카드
 *
 * rank: TOP N 배지 번호 (생략 가능)
 */
export default function ConcertCard({ concert, rank, fallbackImage }) {
  const navigate = useNavigate()
  const { id, title, artist, eventAt, price, status, posterUrl, discountRate, discountedPrice } = concert
  const imageSrc       = posterUrl ?? fallbackImage
  const isPast         = eventAt && new Date(eventAt) < new Date()
  const effectiveStatus = isPast && status === 'OPEN' ? 'CLOSED' : status

  return (
    <div
      onClick={() => navigate(`/concerts/${id}`)}
      className="group cursor-pointer"
    >
      {/* 이미지 */}
      <div className="relative aspect-[3/4] rounded-xl overflow-hidden mb-4 shadow-md transition-all duration-300 group-hover:-translate-y-2 group-hover:shadow-2xl">
        {imageSrc ? (
          <img
            src={imageSrc}
            alt={title}
            className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-105"
          />
        ) : (
          <div className="w-full h-full bg-gradient-to-br from-gray-700 via-gray-800 to-gray-900 flex items-center justify-center">
            <span className="text-5xl opacity-20">🎵</span>
          </div>
        )}

        {/* TOP N 배지 */}
        {rank && (
          <div className="absolute top-3 left-3 bg-white/90 backdrop-blur px-2.5 py-1 rounded font-bold text-xs text-primary-600 shadow-sm">
            TOP {rank}
          </div>
        )}

        {/* 마감 딤 처리 */}
        {effectiveStatus !== 'OPEN' && (
          <div className="absolute inset-0 bg-black/25" />
        )}
      </div>

      {/* 텍스트 — 박스 없이 바로 */}
      <h4 className="font-bold font-jakarta text-sm leading-snug line-clamp-1 text-gray-900 group-hover:text-primary-600 transition-colors mb-1">
        {title}
      </h4>
      {artist && (
        <p className="text-xs text-gray-500 truncate mb-1">{artist}</p>
      )}
      <p className="text-[10px] text-gray-400 font-medium tracking-wide mb-1.5">
        {formatDate(eventAt)}
      </p>
      <ConcertPrice
        price={price}
        discountedPrice={discountedPrice}
        discountRate={discountRate}
        showDiscountRate
      />
    </div>
  )
}
