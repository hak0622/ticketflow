const variants = {
  primary: 'bg-primary-500 hover:bg-primary-600 text-white',
  outline: 'border-2 border-primary-500 text-primary-600 hover:bg-primary-50',
  ghost:   'text-gray-600 hover:bg-gray-100',
  danger:  'bg-red-500 hover:bg-red-600 text-white',
}

const sizes = {
  sm: 'px-3 py-1.5 text-sm',
  md: 'px-5 py-3 text-sm',
  lg: 'px-6 py-4 text-base',
  full: 'w-full py-4 text-base',
}

export default function Button({
  children,
  variant = 'primary',
  size = 'md',
  className = '',
  disabled = false,
  ...props
}) {
  return (
    <button
      disabled={disabled}
      className={`
        inline-flex items-center justify-center font-semibold rounded-xl
        transition-colors duration-150 active:scale-[0.98]
        disabled:opacity-40 disabled:cursor-not-allowed
        ${variants[variant]} ${sizes[size]} ${className}
      `}
      {...props}
    >
      {children}
    </button>
  )
}
