import { NextResponse } from "next/server";
import {GOOGLE_PLAY_DOWNLOAD_URL} from "@/lib/constants";

export function GET() {
	return NextResponse.redirect(GOOGLE_PLAY_DOWNLOAD_URL);
}
