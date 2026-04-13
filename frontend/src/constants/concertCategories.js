export const CONCERT_CATEGORIES = [
  { label: '전체', queryValue: '', genre: null },
  { label: '콘서트', queryValue: 'music', genre: '콘서트' },
  { label: '뮤지컬', queryValue: 'musical', genre: '뮤지컬' },
  { label: '연극', queryValue: 'play', genre: '연극' },
  { label: '클래식/무용', queryValue: 'dance', genre: '클래식/무용' },
  { label: '전시/행사', queryValue: 'exhibition', genre: '전시/행사' },
  { label: '아동/가족', queryValue: 'family', genre: '아동/가족' },
]

export const CATEGORY_BY_QUERY = Object.fromEntries(
  CONCERT_CATEGORIES.map((item) => [item.queryValue, item]),
)
