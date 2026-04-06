export default function EmptyState({ icon = '🎵', title, description }) {
  return (
    <div className="flex flex-col items-center justify-center py-20 text-center">
      <span className="text-5xl mb-4">{icon}</span>
      {title && <p className="text-base font-semibold text-gray-600 mb-1">{title}</p>}
      {description && <p className="text-sm text-gray-400">{description}</p>}
    </div>
  )
}
