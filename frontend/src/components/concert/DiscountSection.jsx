import { useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { getPosterByConcert } from '../../constants/posterMap'

function formatDate(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  if (isNaN(d.getTime())) return ''
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')}`
}

function formatPrice(price) {
  if (price == null) return '가격 미정'
  return `₩${Number(price).toLocaleString('ko-KR')}`
}

function DiscountCard({ concert }) {
  const navigate = useNavigate()
  const { id, title, venue, eventAt, price, posterUrl, discountRate } = concert
  const imageSrc = posterUrl ?? getPosterByConcert(concert)
  const discountedPrice = Math.round((price * (1 - discountRate / 100)) / 100) * 100

  return (
    <div
      onClick={() => navigate(`/concerts/${id}`)}
      className="w-full cursor-pointer group"
    >
      {/* 포스터 + 할인율 배지 */}
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
        <div className="absolute bottom-2 right-2 bg-red-500 text-white text-sm font-black px-2 py-1 rounded leading-none">
          {discountRate}%
        </div>
      </div>

      {/* 제목 */}
      <h4 className="font-bold font-jakarta text-sm leading-snug line-clamp-1 text-gray-900 group-hover:text-primary-600 transition-colors mb-1">
        {title}
      </h4>

      {/* 공연장 */}
      {venue && (
        <p className="text-xs text-gray-500 truncate mb-1">{venue}</p>
      )}

      {/* 날짜 */}
      <p className="text-[11px] text-gray-400 font-medium mb-2">
        {formatDate(eventAt)}
      </p>

      {/* 가격 */}
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-gray-400 line-through text-xs">{formatPrice(price)}</span>
        <span className="text-red-500 font-bold text-sm">{formatPrice(discountedPrice)}</span>
      </div>
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

export default function DiscountSection({ concerts }) {
  const scrollRef = useRef(null)

  const discountItems = concerts.filter((c) => {
    if (typeof c.price !== 'number' || isNaN(c.price) || c.price <= 0) return false
    if (typeof c.discountRate !== 'number' || c.discountRate <= 0 || c.discountRate > 100) return false
    return true
  })

  const scroll = (dir) => {
    if (!scrollRef.current) return
    scrollRef.current.scrollBy({ left: dir * scrollRef.current.clientWidth, behavior: 'smooth' })
  }

  return (
    <section className="mb-14">
      <div className="text-center mb-7">
        <h2 className="text-2xl font-black font-jakarta text-gray-900">🔥할인중🔥</h2>
      </div>

      {discountItems.length === 0 ? (
        <p className="text-center text-sm text-gray-400 py-10">현재 할인 공연이 없습니다</p>
      ) : (
        <div className="relative">
          <ArrowBtn dir="left"  onClick={() => scroll(-1)} label="이전" />

          <div
            ref={scrollRef}
            className="flex gap-3 overflow-x-auto scrollbar-none pb-3"
          >
            {discountItems.map((concert) => (
              <div
                key={concert.id}
                className="flex-shrink-0 min-w-[120px]"
                style={{ width: 'calc((100% - 60px) / 6)' }}
              >
                <DiscountCard concert={concert} />
              </div>
            ))}
          </div>

          <ArrowBtn dir="right" onClick={() => scroll(1)}  label="다음" />
        </div>
      )}
    </section>
  )
}
