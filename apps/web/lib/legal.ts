import fs from "node:fs"
import path from "node:path"

export type LegalBlock =
  | { type: "subheading"; text: string }
  | { type: "paragraph"; text: string }
  | { type: "list"; items: string[] }

export type LegalSection = {
  id: string
  title: string
  blocks: LegalBlock[]
}

export type LegalDocument = {
  title: string
  /** The "Label: value" lines under the title, such as the effective date and the contact. */
  details: Record<string, string>
  /** Paragraphs before the first numbered section. */
  intro: string[]
  sections: LegalSection[]
}

const DETAIL_LABELS = [
  "Effective date",
  "Last updated",
  "Operator",
  "Address",
  "Privacy contact",
  "Contact"
]

/**
 * The apps ship these documents as text, and the site shows the same text, so the policy can't
 * drift between them. Android's copy is the one the site reads; the iPhone and Mac copies match it.
 */
export function readLegalDocument(name: "privacy_policy" | "terms_of_use"): LegalDocument {
  const file = path.resolve(process.cwd(), `../mobile/app/src/main/res/raw/${name}.txt`)
  return parseLegalDocument(fs.readFileSync(file, "utf8"))
}

function parseLegalDocument(source: string): LegalDocument {
  const lines = source.split(/\r?\n/).map((line) => line.trim())
  const title = lines.find(Boolean) ?? ""
  const details: Record<string, string> = {}
  const intro: string[] = []
  const sections: LegalSection[] = []
  let currentSection: LegalSection | null = null
  let listItems: string[] = []

  const flushList = () => {
    if (listItems.length > 0 && currentSection) {
      currentSection.blocks.push({ type: "list", items: listItems })
      listItems = []
    }
  }

  for (const line of lines) {
    if (!line) {
      flushList()
      continue
    }
    if (line === title) continue

    const label = !currentSection && DETAIL_LABELS.find((candidate) => line.startsWith(`${candidate}:`))
    if (label) {
      details[label] = line.slice(label.length + 1).trim()
      continue
    }

    if (/^\d+\.\s/.test(line)) {
      flushList()
      currentSection = { id: slugify(line), title: line, blocks: [] }
      sections.push(currentSection)
      continue
    }

    if (!currentSection) {
      // The pages give the overview its own heading.
      if (line !== "Overview") intro.push(line)
      continue
    }

    if (line.startsWith("- ")) {
      listItems.push(line.slice(2))
      continue
    }

    flushList()
    if (/^\d+\.\d+\s/.test(line)) {
      currentSection.blocks.push({ type: "subheading", text: line })
    } else {
      currentSection.blocks.push({ type: "paragraph", text: line })
    }
  }
  flushList()

  return { title, details, intro, sections }
}

function slugify(value: string) {
  return value
    .toLowerCase()
    .replace(/^\d+\.\s*/, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/(^-|-$)/g, "")
}
