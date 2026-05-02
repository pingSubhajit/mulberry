import type { Metadata } from "next"

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
  return children
}

