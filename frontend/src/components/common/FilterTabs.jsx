export default function FilterTabs({ tabs, activeId, onChange }) {
  return (
    <div className="flex gap-2 overflow-x-auto pb-1 scrollbar-none">
      {tabs.map((tab) => {
        const isActive = tab.id === activeId
        return (
          <button
            key={tab.id}
            onClick={() => onChange(tab.id)}
            className={`flex-shrink-0 text-xs font-semibold px-4 py-1.5 rounded-full border transition-colors ${
              isActive
                ? 'bg-primary-500 text-white border-primary-500'
                : 'bg-white text-gray-500 border-gray-200 hover:border-primary-300 hover:text-primary-600'
            }`}
          >
            {tab.label}
          </button>
        )
      })}
    </div>
  )
}
