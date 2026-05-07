import type { FastifyRequest } from "fastify"
import { HttpError } from "./HttpError.js"

export async function readRequiredImageUpload(request: FastifyRequest): Promise<{
  filename: string
  contentType: string
  data: Buffer
}> {
  for await (const part of request.parts()) {
    if (part.type === "file" && part.fieldname === "image") {
      return {
        filename: part.filename,
        contentType: part.mimetype,
        data: await part.toBuffer(),
      }
    }
  }
  throw new HttpError(400, "image is required")
}

