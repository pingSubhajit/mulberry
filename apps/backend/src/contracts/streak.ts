export interface StreakWeekDay {
  day: string
  hasActivity: boolean
}

export interface StreakResponse {
  today: string
  currentStreakDays: number
  previousStreakDays: number
  hasActivityToday: boolean
  lastActivityDay: string | null
  /**
   * Whether this account has watched the bolt break for the streak that ended on
   * `lastActivityDay`, on any device. The apps play it once per lost streak.
   */
  lostStreakSeen: boolean
  week: StreakWeekDay[]
}

