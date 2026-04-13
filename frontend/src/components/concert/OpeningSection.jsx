import { useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { getPosterByConcert } from '../../constants/posterMap'

const DAYS = ['일', '월', '화', '수', '목', '금', '토']

function formatOpeningLabel(bookingOpenAt) {
  if (!bookingOpenAt) return ''
  const d = new Date(bookingOpenAt)
  if (isNaN(d.getTime())) return ''
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day   = String(d.getDate()).padStart(2, '0')
  const dow   = DAYS[d.getDay()]
  const hh    = String(d.getHours()).padStart(2, '0')
  const mm    = String(d.getMinutes()).padStart(2, '0')
  return `${month}.${day}(${dow}) ${hh}:${mm}`
}

function OpeningCard({ concert }) {
  const navigate = useNavigate()
  const { id, title, venue, bookingOpenAt, posterUrl } = concert
  const imageSrc = posterUrl ?? getPosterByConcert(concert)
  const openingLabel = formatOpeningLabel(bookingOpenAt)

  return (
    <div
      onClick={() => navigate(`/concerts/${id}`)}
      className="w-full cursor-pointer group"
    >
      {/* 포스터 이미지 */}
      <div className="relative aspect-[3/4] rounded-xl overflow-hidden mb-3 shadow-md transition-all duration-300 group-hover:-translate-y-1 group-hover:shadow-xl">
        {imageSrc ? (
          <img
            src={imageSrc}
            alt={title}
            className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-105"
          />
        ) : (
          <div className="w-full h-full bg-gradient-to-br from-gray-700 via-gray-800 to-gray-900 flex items-center justify-center">
            <span className="text-4xl opacity-20">🎵</span>
          </div>
        )}
      </div>

      {/* 오픈 날짜/시간 */}
      <p className="text-primary-600 font-bold text-sm mb-1 group-hover:text-primary-700 transition-colors">
        {openingLabel}
      </p>

      {/* 공연 제목 */}
      <h4 className="font-bold font-jakarta text-sm leading-snug line-clamp-2 text-gray-900 mb-1 group-hover:text-primary-600 transition-colors">
        {title}
      </h4>

      {/* 공연장 */}
      {venue && (
        <p className="text-xs text-gray-500 truncate">{venue}</p>
      )}
    </div>
  )
}

function ArrowBtn({ dir, onClick, label }) {
  return (
    <button
      onClick={onClick}
      className={`absolute ${dir === 'left' ? 'left-0 -translate-x-1/2' : 'right-0 translate-x-1/2'}
                 top-1/2 -translate-y-1/2 z-10
                 w-9 h-9 bg-white border border-gray-200 rounded-full shadow-md
                 flex items-center justify-center text-lg text-gray-500
                 hover:border-primary-400 hover:text-primary-600 hover:shadow-lg transition-all`}
      aria-label={label}
    >
      {dir === 'left' ? '‹' : '›'}
    </button>
  )
}

export default function OpeningSection({ concerts }) {
  const scrollRef = useRef(null)

  const now = new Date()
  const items = concerts
    .filter((c) => {
      if (c.bookingOpenAt == null) return false
      const date = new Date(c.bookingOpenAt)
      if (isNaN(date.getTime())) return false
      return date > now
    })
    .sort((a, b) => new Date(a.bookingOpenAt) - new Date(b.bookingOpenAt))

  const scroll = (dir) => {
    if (!scrollRef.current) return
    scrollRef.current.scrollBy({ left: dir * scrollRef.current.clientWidth, behavior: 'smooth' })
  }

  return (
    <section className="mb-14">
      <div className="text-center mb-6">
        <h2 className="text-2xl font-black font-jakarta text-gray-900">오픈 예정</h2>
      </div>

      {items.length === 0 ? (
        <p className="text-center text-sm text-gray-400 py-10">현재 오픈 예정 공연이 없습니다</p>
      ) : (
        <div className="relative">
          <ArrowBtn dir="left"  onClick={() => scroll(-1)} label="이전" />

          <div
            ref={scrollRef}
            className="flex gap-4 overflow-x-auto scrollbar-none pb-3"
          >
            {items.map((concert) => (
              <div
                key={concert.id}
                className="flex-shrink-0 min-w-[140px]"
                style={{ width: 'calc((100% - 64px) / 5)' }}
              >
                <OpeningCard concert={concert} />
              </div>
            ))}
          </div>

          <ArrowBtn dir="right" onClick={() => scroll(1)}  label="다음" />
        </div>
      )}
    </section>
  )
}
