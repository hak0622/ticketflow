const CONFIG = {
  OPEN:     { label: '예매가능', className: 'bg-primary-100 text-primary-700' },
  SOLD_OUT: { label: '매진',     className: 'bg-gray-200 text-gray-500' },
  CLOSED:   { label: '종료',     className: 'bg-gray-100 text-gray-400' },
}

export default function StatusBadge({ status, className = '' }) {
  const cfg = CONFIG[status] ?? CONFIG.CLOSED
  return (
    <span className={`inline-flex items-center text-[11px] font-bold px-2 py-0.5 rounded-sm tracking-wide ${cfg.className} ${className}`}>
      {cfg.label}
    </span>
  )
}
