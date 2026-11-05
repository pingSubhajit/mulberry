import SwiftUI
#if canImport(ProductCore)
import ProductCore
#endif

/// A "What's new" note's body, laid out as Android lays it out: headings in the text colour,
/// paragraphs and lists in a quieter one, with bold, italics and links styled inline.
public struct WhatsNewMarkdownView: View {
    let blocks: [WhatsNewBlock]
    let headingFont: Font
    let bodyFont: Font
    let headingColor: Color
    let bodyColor: Color

    public init(blocks: [WhatsNewBlock], headingFont: Font = .headline, bodyFont: Font = .subheadline,
                headingColor: Color = .primary, bodyColor: Color = .secondary) {
        self.blocks = blocks; self.headingFont = headingFont; self.bodyFont = bodyFont
        self.headingColor = headingColor; self.bodyColor = bodyColor
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                switch block {
                case .heading(_, let text):
                    Self.inline(text).font(headingFont).foregroundStyle(headingColor)
                        .accessibilityAddTraits(.isHeader)
                case .paragraph(let text):
                    Self.inline(text).font(bodyFont).foregroundStyle(bodyColor)
                case .bullets(let items):
                    list(items.map { ("•", $0) })
                case .numbered(let start, let items):
                    list(items.enumerated().map { ("\(start + $0.offset).", $0.element) })
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .multilineTextAlignment(.leading)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func list(_ items: [(marker: String, text: String)]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(item.marker).monospacedDigit().accessibilityHidden(true)
                    Self.inline(item.text)
                }
                .font(bodyFont).foregroundStyle(bodyColor)
                .accessibilityElement(children: .combine)
            }
        }
    }

    /// Bold, italics, code and links, from inline markdown; the plain text if it doesn't parse.
    static func inline(_ markdown: String) -> Text {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        if let styled = try? AttributedString(markdown: markdown, options: options) { return Text(styled) }
        return Text(verbatim: markdown)
    }
}
