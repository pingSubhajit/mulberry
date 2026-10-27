import SwiftUI

struct OnboardingNameView: View {
    @ObservedObject var model: MulberryModel
    @State private var details = false
    @State private var privacy = false
    @FocusState private var nameFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                Image("BrandMark").resizable().scaledToFit().frame(width: 46, height: 46).accessibilityHidden(true)
                ScreenHeading(title: "Welcome to Mulberry!\nLet’s get you set up")
                OnboardingField(label: "What is your name?", placeholder: "Enter the name your partner calls you", value: $model.draft.displayName)
                    .focused($nameFocused).submitLabel(.next).onSubmit(continueToDetails)
                InlineError(message: model.errorMessage)
            }.padding(24).padding(.top, 20)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(MulberryDesign.background)
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 12) {
                PrimaryAction(title: "Alright, next", enabled: !model.draft.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, action: continueToDetails)
                    .accessibilityIdentifier("onboardingNext")
                Button { nameFocused = false; model.showInviteEntry = true } label: {
                    Text("Have a code? \(Text("Enter code").foregroundColor(MulberryDesign.accent))").foregroundStyle(MulberryDesign.muted)
                        .font(MulberryDesign.font(14)).frame(minHeight: 44)
                }
                Button("By continuing you agree to our Privacy Policy") { privacy = true }
                    .font(.footnote).foregroundStyle(MulberryDesign.muted).fixedSize(horizontal: false, vertical: true)
            }.padding(.horizontal, 24).padding(.bottom, 12).padding(.top, 12).background(MulberryDesign.background)
        }
        .navigationDestination(isPresented: $details) { OnboardingDetailsView(model: model) }
        .sheet(isPresented: $privacy) { LegalDocumentView(document: .privacy) }
        .onChange(of: model.draft) { _, _ in model.saveDraft() }
    }

    private func continueToDetails() {
        guard !model.draft.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        nameFocused = false; model.saveDraft(); details = true
    }
}

struct OnboardingDetailsView: View {
    @ObservedObject var model: MulberryModel
    @FocusState private var partnerFocused: Bool
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Welcome \(model.draft.displayName)").foregroundStyle(MulberryDesign.muted)
                    ScreenHeading(title: "A few more things")
                }
                OnboardingField(label: "What is your partner’s name?", placeholder: "Enter the name you call your partner", value: $model.draft.partnerDisplayName)
                    .focused($partnerFocused).submitLabel(.done)
                AnniversaryField(value: $model.draft.anniversaryDate)
                InlineError(message: model.errorMessage)
            }.padding(24).padding(.top, 12)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(MulberryDesign.background)
        .safeAreaInset(edge: .bottom) {
            PrimaryAction(title: "Let’s get going", loading: model.busy,
                          enabled: !model.draft.partnerDisplayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !model.draft.anniversaryDate.isEmpty) {
                partnerFocused = false
                Task { await model.submitProfile() }
            }.padding(24).background(MulberryDesign.background)
        }
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: model.draft) { _, _ in model.saveDraft() }
    }
}

struct AnniversaryField: View {
    @Binding var value: String
    @State private var choosing = false
    @State private var date = Date()

    private static let parser: DateFormatter = {
        let result = DateFormatter()
        result.locale = Locale(identifier: "en_US_POSIX")
        result.calendar = Calendar(identifier: .gregorian)
        result.dateFormat = "yyyy-MM-dd"
        return result
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Relationship anniversary").font(MulberryDesign.font(16, weight: "SemiBold"))
            Button {
                date = Self.parser.date(from: value) ?? Date()
                choosing = true
            } label: {
                HStack {
                    Text(Self.parser.date(from: value)?.formatted(date: .long, time: .omitted) ?? "Select date")
                        .foregroundStyle(value.isEmpty ? MulberryDesign.muted : MulberryDesign.ink)
                    Spacer()
                    Image(systemName: "calendar").foregroundStyle(MulberryDesign.muted)
                }.font(MulberryDesign.font(15)).padding(16)
                    .background(MulberryDesign.surface.opacity(0.4), in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(MulberryDesign.muted.opacity(0.25)))
            }.buttonStyle(.plain).accessibilityLabel("Relationship anniversary").accessibilityValue(value.isEmpty ? "Not selected" : value)
        }
        .sheet(isPresented: $choosing) {
            NavigationStack {
                DatePicker("Relationship anniversary", selection: $date, in: ...Date(), displayedComponents: .date)
                    .datePickerStyle(.graphical).padding()
                    .navigationTitle("Anniversary").navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { choosing = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Done") { value = ServerDate.localDay(date); choosing = false }.bold()
                        }
                    }
            }.presentationDetents([.medium, .large])
        }
    }
}

enum LegalDocument: String, Identifiable {
    case privacy = "privacy_policy", terms = "terms_of_use"
    var id: String { rawValue }
    var title: String { self == .privacy ? "Privacy Policy" : "Terms of Use" }
}

struct LegalDocumentView: View {
    let document: LegalDocument
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                Text(contents).font(.body).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding(24)
            }.navigationTitle(document.title).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
    private var contents: String {
        guard let url = Bundle.main.url(forResource: document.rawValue, withExtension: "txt", subdirectory: "Legal"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return "This document could not be loaded. Please visit mulberry.my." }
        return text
    }
}
