function formatPrice(price) {
  if (price == null) return '가격 미정'
  return `₩${Number(price).toLocaleString('ko-KR')}`
}

export default function ConcertPrice({
  price,
  discountedPrice,
  discountRate,
  className = '',
  discountedClassName = 'text-red-500 font-bold text-sm',
  originalClassName = 'text-gray-400 line-through text-xs',
  regularClassName = 'text-gray-900 font-bold text-sm',
  showDiscountRate = false,
  discountRateClassName = 'text-red-500 text-xs font-black',
}) {
  if (discountedPrice != null) {
    return (
      <div className={`flex items-center gap-2 flex-wrap ${className}`.trim()}>
        <span className={originalClassName}>{formatPrice(price)}</span>
        <span className={discountedClassName}>{formatPrice(discountedPrice)}</span>
        {showDiscountRate && discountRate != null && (
          <span className={discountRateClassName}>{discountRate}%</span>
        )}
      </div>
    )
  }

  return (
    <div className={className}>
      <span className={regularClassName}>{formatPrice(price)}</span>
    </div>
  )
}
