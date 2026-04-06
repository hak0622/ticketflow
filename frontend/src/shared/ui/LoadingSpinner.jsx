export default function LoadingSpinner({ size = 'md', className = '' }) {
  const sizeClass = size === 'sm' ? 'w-4 h-4' : size === 'lg' ? 'w-10 h-10' : 'w-7 h-7'
  return (
    <div
      className={`${sizeClass} border-2 border-gray-200 border-t-primary-500 rounded-full animate-spin ${className}`}
    />
  )
}
