/** Extra fields sent beside `message`, such as a machine-readable `code`. */
export type HttpErrorDetails = Record<string, unknown>

export class HttpError extends Error {
  constructor(
    readonly statusCode: number,
    message: string,
    readonly details?: HttpErrorDetails,
  ) {
    super(message)
    this.name = "HttpError"
  }
}
