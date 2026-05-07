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
  week: StreakWeekDay[]
}

