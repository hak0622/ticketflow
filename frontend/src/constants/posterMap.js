import poster1 from '../assets/포스터1.png'
import poster2 from '../assets/포스터2.png'
import poster3 from '../assets/포스터3.png'
import poster4 from '../assets/포스터4.png'
import poster5 from '../assets/포스터5.png'
import poster6 from '../assets/포스터6.png'
import poster7 from '../assets/포스터7.png'

export const posterMap = {
  1: poster1,
  2: poster2,
  3: poster3,
  4: poster4,
  5: poster5,
  6: poster6,
  7: poster7,
}

export function getPosterByConcert(concert) {
  return concert?.posterUrl || posterMap[concert?.id] || poster1
}