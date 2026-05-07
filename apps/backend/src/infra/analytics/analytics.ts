export type AnalyticsCaptureInput = {
  distinctId: string
  event: string
  properties?: Record<string, unknown>
  timestamp?: Date
  groups?: Record<string, string>
}

export interface AnalyticsClient {
  capture(input: AnalyticsCaptureInput): void
  shutdown(): Promise<void>
  enabled: boolean
}

export class NoopAnalyticsClient implements AnalyticsClient {
  readonly enabled = false
  capture(_input: AnalyticsCaptureInput): void {}
  async shutdown(): Promise<void> {}
}
