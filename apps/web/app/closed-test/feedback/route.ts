import { NextResponse } from "next/server";

const feedbackFormUrl = "https://forms.gle/2BFkWXLCEbkb89n27";

export function GET() {
  return NextResponse.redirect(feedbackFormUrl);
}
