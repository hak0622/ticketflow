INSERT INTO concert (
  artist, booked_count, booking_open_at, discount_rate,
  event_at, genre, poster_url, price, status,
  title, total_seats, venue, zone
) VALUES
(
  'ITZY', 8500, NULL, 10,
  '2026-05-17 17:00:00', '콘서트',
  'https://res.cloudinary.com/dblucouav/image/upload/v1776340632/%E1%84%91%E1%85%A9%E1%84%89%E1%85%B3%E1%84%90%E1%85%A56_wxqkaj.png',
  110000, 'OPEN',
  'CHECKMATE',
  10000, 'KSPO DOME', 'ALL'
),
(
  'Michelangelo', 0, '2026-04-25 11:00:00', 30,
  '2026-06-01 10:00:00', '전시/행사',
  'https://res.cloudinary.com/dblucouav/image/upload/v1776344846/%E1%84%8C%E1%85%A5%E1%86%AB%E1%84%89%E1%85%B5%E1%84%92%E1%85%AC%E1%84%91%E1%85%A9%E1%84%89%E1%85%B3%E1%84%90%E1%85%A5_wxs2gc.jpg',
  29000, 'OPEN',
  '미켈란젤로 특별전',
  5000, 'DDP Art Hall', 'ALL'
),
(
  'BEA', 3200, NULL, 15,
  '2026-06-01 19:30:00', '뮤지컬',
  'https://res.cloudinary.com/dblucouav/image/upload/v1776344467/%E1%84%91%E1%85%A9%E1%84%89%E1%85%B3%E1%84%90%E1%85%A53_fso0z0.png',
  80000, 'OPEN',
  'Hibea',
  2500, 'Charlotte Theater', 'ALL'
),
(
  'NCT', 0, '2026-05-01 10:00:00', 25,
  '2026-06-28 19:30:00', '콘서트',
  'https://res.cloudinary.com/dblucouav/image/upload/v1776344647/NCT%E1%84%91%E1%85%A9%E1%84%89%E1%85%B3%E1%84%90%E1%85%A5_lrzqfy.jpg',
  121000, 'OPEN',
  'NCT NATION',
  12000, 'KSPO DOME', 'ALL'
),
(
  'Pororo', 0, '2026-05-15 10:00:00', NULL,
  '2026-07-12 14:00:00', '아동/가족',
  'https://res.cloudinary.com/dblucouav/image/upload/v1776344916/%E1%84%88%E1%85%A9%E1%84%85%E1%85%A9%E1%84%85%E1%85%A9%E1%84%91%E1%85%A9%E1%84%89%E1%85%B3%E1%84%90%E1%85%A5_k4glz3.jpg',
  39000, 'OPEN',
  '뽀로로 극장판 슈퍼스타 대모험',
  3000, 'Olympic Gymnastics Arena', 'ALL'
),
(
  '번개맨', 850, NULL, 20,
  '2026-07-20 14:00:00', '아동/가족',
  'https://res.cloudinary.com/dblucouav/image/upload/v1776346201/%E1%84%87%E1%85%A5%E1%86%AB%E1%84%80%E1%85%A2%E1%84%86%E1%85%A2%E1%86%AB_%E1%84%91%E1%85%A9%E1%84%89%E1%85%B3%E1%84%90%E1%85%A5_lz88wp.gif',
  10000, 'OPEN',
  '번개맨 시즌2',
  2500, '어린이대공원 와팝홀', 'ALL'
),
(
  '국립극단', 0, '2026-05-20 10:00:00', NULL,
  '2026-08-05 19:30:00', '연극',
  'https://res.cloudinary.com/dblucouav/image/upload/v1776345050/%E1%84%91%E1%85%A9%E1%84%89%E1%85%B3%E1%84%90%E1%85%A5_y9hbzy.png',
  60000, 'OPEN',
  '암덕',
  800, '명동예술극장', 'SEAT'
),
(
  '부산국제무용제', 0, '2026-05-10 10:00:00', 20,
  '2026-08-15 19:00:00', '클래식/무용',
  'https://res.cloudinary.com/dblucouav/image/upload/v1776344751/%E1%84%83%E1%85%A2%E1%86%AB%E1%84%89%E1%85%B3_%E1%84%8F%E1%85%B3%E1%86%AF%E1%84%85%E1%85%A2%E1%84%89%E1%85%B5%E1%86%A8_%E1%84%91%E1%85%A9%E1%84%89%E1%85%B3%E1%84%90%E1%85%A5_tvob4x.jpg',
  50000, 'OPEN',
  '부산국제 댄스 그랑프리',
  1800, '예술의전당 오페라극장', 'SEAT'
),
(
  '킹키부츠', 0, '2026-05-25 10:00:00', NULL,
  '2026-08-22 19:30:00', '뮤지컬',
  'https://res.cloudinary.com/dblucouav/image/upload/v1776346118/%E1%84%8F%E1%85%B5%E1%86%BC%E1%84%8F%E1%85%B5%E1%84%87%E1%85%AE%E1%84%8E%E1%85%B3_%E1%84%91%E1%85%A9%E1%84%89%E1%85%B3%E1%84%90%E1%85%A5_q4gpmq.gif',
  90000, 'OPEN',
  '킹키부츠',
  1800, '충무아트센터 대극장', 'SEAT'
);