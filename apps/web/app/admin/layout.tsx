import type { Metadata } from "next"
import { Geist_Mono, Inter } from "next/font/google"

import AdminShell from "./AdminShell"

const adminSans = Inter({ subsets: ["latin"], display: "swap", variable: "--font-admin-sans" })
const adminMono = Geist_Mono({ subsets: ["latin"], display: "swap", variable: "--font-admin-mono" })

export const metadata: Metadata = {
  title: {
    default: "Admin | Mulberry",
    template: "%s | Admin | Mulberry"
  },
  description: "Mulberry admin tools.",
  robots: {
    index: false,
    follow: false,
    nocache: true
  }
}

export default function AdminLayout({
  children
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <div className={`${adminSans.variable} ${adminMono.variable} admin-root`}>
      <AdminShell>{children}</AdminShell>
    </div>
  )
}
