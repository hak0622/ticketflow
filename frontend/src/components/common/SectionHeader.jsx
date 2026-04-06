import { Link } from 'react-router-dom'

export default function SectionHeader({ label, count, href, linkText = '더보기' }) {
  return (
    <div className="flex items-center justify-between mb-4">
      <h2 className="text-lg font-bold text-gray-900">
        {label}
        {count != null && (
          <span className="ml-2 text-sm font-normal text-gray-400">{count}건</span>
        )}
      </h2>
      {href && (
        <Link to={href} className="text-sm text-primary-600 font-semibold hover:text-primary-700 transition-colors">
          {linkText}
        </Link>
      )}
    </div>
  )
}
