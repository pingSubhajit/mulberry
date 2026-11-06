import { Permanent_Marker, Poppins } from "next/font/google"

/** The share image's fonts, for drawing the preview on a canvas. */
export const captionFont = Permanent_Marker({ weight: "400", subsets: ["latin"], display: "block" })
export const lockScreenFont = Poppins({ weight: ["500", "600"], subsets: ["latin"], display: "block" })
