/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        /*
         * primary = Interpark-style blue (#2454C1)
         * 포인트 컬러로만 사용 (CTA, active 탭, 배지, 링크)
         * 카드/배경에는 사용 금지
         */
        primary: {
          50:  '#eff4ff',
          100: '#dce8ff',
          200: '#b8d0ff',
          300: '#7aaaff',
          400: '#4d81e8',
          500: '#2454c1',   /* 메인 인터파크 블루 */
          600: '#1d47a8',
          700: '#163990',
          800: '#0e2b6d',
          900: '#071c48',
        },
      },
      fontFamily: {
        sans:    ['Inter', '"Noto Sans KR"', 'sans-serif'],
        jakarta: ['"Plus Jakarta Sans"', '"Noto Sans KR"', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
