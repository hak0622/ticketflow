import { useState, useEffect, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { getPosterByConcert } from '../../constants/posterMap'

function formatDate(dateStr) {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  return d.toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

/**
 * HeroCarousel — 인터파크 스타일 가로 슬라이드 캐러셀
 *
 * - 데스크탑: 4개, 모바일: 2개 동시 노출
 * - 무한 루프 + 자동 슬라이드 (3.5s)
 * - 터치 스와이프 지원
 * - 포스터 이미지 + 스크림 overlay 텍스트
 */
export default function HeroCarousel({ concerts = [] }) {
  const navigate = useNavigate()
  const [current, setCurrent] = useState(0)
  const [animated, setAnimated] = useState(true)
  const [visible, setVisible] = useState(4)
  const touchX = useRef(null)
  const timerRef = useRef(null)

  const total = concerts.length

  /* 반응형 visible count */
  useEffect(() => {
    const update = () => setVisible(window.innerWidth >= 768 ? 4 : 2)
    update()
    window.addEventListener('resize', update)
    return () => window.removeEventListener('resize', update)
  }, [])

  /* 자동 슬라이드 */
  const startTimer = useCallback(() => {
    clearInterval(timerRef.current)
    timerRef.current = setInterval(() => {
      setCurrent((p) => p + 1)
      setAnimated(true)
    }, 3500)
  }, [])

  useEffect(() => {
    startTimer()
    return () => clearInterval(timerRef.current)
  }, [startTimer])

  /* clone 경계 도달 시 애니메이션 없이 점프 */
  const onTransitionEnd = () => {
    if (current >= total) {
      setAnimated(false)
      setCurrent(0)
    } else if (current < 0) {
      setAnimated(false)
      setCurrent(total - 1)
    }
  }

  /* 점프 후 애니메이션 복원 (double rAF) */
  useEffect(() => {
    if (!animated) {
      const id = requestAnimationFrame(() =>
        requestAnimationFrame(() => setAnimated(true)),
      )
      return () => cancelAnimationFrame(id)
    }
  }, [animated])

  /* 방향 이동 + 타이머 리셋 */
  const go = useCallback(
    (dir) => {
      setCurrent((p) => p + dir)
      setAnimated(true)
      startTimer()
    },
    [startTimer],
  )

  /* 터치 */
  const onTouchStart = (e) => {
    touchX.current = e.touches[0].clientX
  }

  const onTouchEnd = (e) => {
    if (touchX.current == null) return
    const dx = touchX.current - e.changedTouches[0].clientX
    if (Math.abs(dx) > 40) go(dx > 0 ? 1 : -1)
    touchX.current = null
  }

  /* 로딩/빈 데이터 처리 */
  if (total === 0) return null

  /* 무한 루프용 clone 배열: [마지막 N개, ...원본, 처음 N개] */
  const cloneN = visible
  const items = [
    ...concerts.slice(-cloneN),
    ...concerts,
    ...concerts.slice(0, cloneN),
  ]
  const idx = current + cloneN

  /* 트랙 전체 너비(%) / 이동 거리(%) */
  const trackWidthPct = (items.length / visible) * 100
  const translateXPct = -(idx / items.length) * 100

  /* 현재 실제 인덱스 (dot 표시용) */
  const dotActive = ((current % total) + total) % total

  return (
    <section
      className="relative overflow-hidden mb-12"
      onTouchStart={onTouchStart}
      onTouchEnd={onTouchEnd}
    >
      {/* 슬라이드 트랙 */}
      <div
        className="flex"
        style={{
          width: `${trackWidthPct}%`,
          transform: `translateX(${translateXPct}%)`,
          transition: animated
            ? 'transform 0.55s cubic-bezier(0.25, 0.46, 0.45, 0.94)'
            : 'none',
        }}
        onTransitionEnd={onTransitionEnd}
      >
        {items.map((concert, i) => {
          const img = getPosterByConcert(concert)

          return (
            <div
              key={`${concert.id}-${i}`}
              style={{ width: `${100 / items.length}%` }}
              className="flex-shrink-0 px-1.5 md:px-2"
            >
              <div
                className="relative rounded-2xl overflow-hidden shadow-md group cursor-pointer"
                style={{ aspectRatio: '3 / 4' }}
                onClick={() => navigate(`/concerts/${concert.id}`)}
              >
                {img ? (
                  <img
                    src={img}
                    alt={concert.title}
                    className="w-full h-full object-cover transition-transform duration-500 group-hover:scale-105"
                  />
                ) : (
                  <div className="w-full h-full bg-gradient-to-br from-gray-700 via-gray-800 to-gray-900" />
                )}

                <div className="absolute inset-0 scrim-gradient" />

                <div className="absolute bottom-0 left-0 right-0 p-4 md:p-5 text-white">
                  <h3 className="font-jakarta font-bold text-sm md:text-base leading-tight line-clamp-2 mb-0.5">
                    {concert.title}
                  </h3>
                  {concert.artist && (
                    <p className="text-[10px] md:text-xs opacity-80 truncate mb-0.5">
                      {concert.artist}
                    </p>
                  )}
                  <p className="text-[10px] opacity-60">
                    {formatDate(concert.eventAt)}
                  </p>
                </div>
              </div>
            </div>
          )
        })}
      </div>

      {/* 좌우 화살표 */}
      <button
        onClick={() => go(-1)}
        aria-label="이전"
        className="absolute left-2 top-1/2 -translate-y-1/2 z-10 w-9 h-9 rounded-full bg-white/85 backdrop-blur-sm shadow flex items-center justify-center text-gray-700 hover:bg-white transition-colors font-bold text-lg leading-none"
      >
        ‹
      </button>
      <button
        onClick={() => go(1)}
        aria-label="다음"
        className="absolute right-2 top-1/2 -translate-y-1/2 z-10 w-9 h-9 rounded-full bg-white/85 backdrop-blur-sm shadow flex items-center justify-center text-gray-700 hover:bg-white transition-colors font-bold text-lg leading-none"
      >
        ›
      </button>

      {/* 인디케이터 dots */}
      <div className="absolute bottom-2.5 left-1/2 -translate-x-1/2 flex gap-1.5 z-10">
        {concerts.map((_, i) => (
          <button
            key={i}
            onClick={() => {
              setCurrent(i)
              setAnimated(true)
              startTimer()
            }}
            aria-label={`슬라이드 ${i + 1}`}
            className={`h-1.5 rounded-full transition-all duration-300 ${
              dotActive === i ? 'bg-white w-4' : 'bg-white/50 w-1.5'
            }`}
          />
        ))}
      </div>
    </section>
  )
}