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

export async function readRequiredPartnerProfileUpdateWithPhoto(
  request: FastifyRequest,
): Promise<{
  partnerDisplayName: string
  anniversaryDate: string
  image: { filename: string; contentType: string; data: Buffer }
}> {
  let partnerDisplayName: string | null = null
  let anniversaryDate: string | null = null
  let image: { filename: string; contentType: string; data: Buffer } | null = null

  for await (const part of request.parts()) {
    if (part.type === "file") {
      if (part.fieldname !== "image") continue
      if (image) {
        throw new HttpError(400, "Only one image can be uploaded")
      }
      image = {
        filename: part.filename,
        contentType: part.mimetype,
        data: await part.toBuffer(),
      }
      continue
    }

    if (part.type === "field") {
      const value = typeof part.value === "string" ? part.value : String(part.value)
      if (part.fieldname === "partnerDisplayName") {
        partnerDisplayName = value
      } else if (part.fieldname === "anniversaryDate") {
        anniversaryDate = value
      }
    }
  }

  if (!partnerDisplayName?.trim()) {
    throw new HttpError(400, "partnerDisplayName is required")
  }
  if (!anniversaryDate?.trim()) {
    throw new HttpError(400, "anniversaryDate is required")
  }
  if (!image) {
    throw new HttpError(400, "image is required")
  }

  return { partnerDisplayName, anniversaryDate, image }
}
