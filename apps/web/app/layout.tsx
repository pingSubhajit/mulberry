import type { Metadata, Viewport } from "next"
import { Poppins } from "next/font/google"

import "./globals.css"

const poppins = Poppins({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  display: "swap",
  variable: "--font-poppins"
})

export const metadata: Metadata = {
  title: "Mulberry | A shared canvas for your lock screen",
  description:
    "Mulberry lets couples share a private canvas that appears on each other's Android lock screen.",
  metadataBase: new URL("https://mulberry.my"),
  openGraph: {
    title: "Mulberry",
    description:
      "Draw something small. Let it appear on your partner's lock screen.",
    images: ["/brand/banner.png"]
  },
  icons: {
    icon: "/icon.svg"
  }
}

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#fffaf7" },
    { media: "(prefers-color-scheme: dark)", color: "#101010" }
  ],
  colorScheme: "light dark"
}

export default function RootLayout({
  children
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="en" className={poppins.variable}>
      <body>{children}</body>
    </html>
  )
}
