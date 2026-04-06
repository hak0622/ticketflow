import { useNavigate } from 'react-router-dom'

function formatDateShort(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return d.toLocaleDateString('ko-KR', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

/**
 * FeaturedCard — 히어로 벤토 그리드용 카드
 *
 * large: 좌측 대형 카드 (2열)
 * !large: 우측 소형 카드
 */
export default function FeaturedCard({ concert, fallbackImage, large = false, className = '' }) {
  const navigate = useNavigate()
  const { id, title, artist, eventAt, status, posterUrl, genre } = concert

  const isPast          = eventAt && new Date(eventAt) < new Date()
  const effectiveStatus = isPast && status === 'OPEN' ? 'CLOSED' : status
  const isOpen          = effectiveStatus === 'OPEN'
  const imageSrc        = posterUrl ?? fallbackImage

  return (
    <div
      onClick={() => navigate(`/concerts/${id}`)}
      className={`relative rounded-2xl overflow-hidden group cursor-pointer transition-transform duration-300 hover:scale-[1.015] shadow-lg ${className}`}
    >
      {/* 배경 이미지 */}
      {imageSrc ? (
        <img
          src={imageSrc}
          alt={title}
          className="absolute inset-0 w-full h-full object-cover transition-transform duration-500 group-hover:scale-105"
        />
      ) : (
        <div className="absolute inset-0 bg-gradient-to-br from-gray-700 via-gray-800 to-gray-900" />
      )}

      {/* 스크림 그라데이션 */}
      <div className="absolute inset-0 scrim-gradient" />

      {/* 콘텐츠 */}
      <div className={`absolute bottom-0 left-0 right-0 text-white ${large ? 'p-8 md:p-10' : 'p-5 md:p-6'}`}>

        {/* SOLD_OUT / CLOSED 배지 (대형만) */}
        {large && !isOpen && (
          <span className="inline-block bg-white/20 backdrop-blur-md text-white px-3 py-1 rounded text-[10px] font-bold uppercase tracking-widest mb-4">
            {effectiveStatus === 'SOLD_OUT' ? '매진' : '종료'}
          </span>
        )}

        {/* 제목 */}
        <h2 className={`font-jakarta font-black leading-tight ${large ? 'text-2xl md:text-4xl mb-3' : 'text-lg md:text-xl mb-1.5'}`}>
          {title}
        </h2>

        {/* 아티스트 */}
        {artist && (
          <p className={`opacity-85 font-medium ${large ? 'text-base mb-1' : 'text-xs mb-0.5'}`}>
            {artist}
          </p>
        )}

        {/* 장르 / 날짜 */}
        <p className={`opacity-60 uppercase tracking-widest font-sans ${large ? 'text-sm' : 'text-[10px]'}`}>
          {genre}{genre && eventAt ? ' · ' : ''}{formatDateShort(eventAt)}
        </p>
      </div>
    </div>
  )
}
