import SwiftUI

struct AuthenticationView: View {
    let busy: Bool
    let signingInWith: SignInMethod?
    let errorMessage: String?
    let signIn: (SignInMethod) -> Void
    @Environment(\.dynamicTypeSize) private var textSize

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 0) {
                    Image("BrandMark")
                        .renderingMode(.template)
                        .resizable().scaledToFit()
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 66)
                        .accessibilityHidden(true)
                        .padding(.top, 24)
                    Text("Connect with your significant other")
                        .font(.custom("Poppins-Bold", size: 37, relativeTo: .largeTitle))
                        .foregroundStyle(Color(red: 23 / 255, green: 16 / 255, blue: 22 / 255))
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 24)
                        .accessibilityAddTraits(.isHeader)
                    Text("Share a canvas with your partner that lives on your wallpaper")
                        .font(.body)
                        .foregroundStyle(Color(red: 23 / 255, green: 16 / 255, blue: 22 / 255))
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: 320)
                        .padding(.top, 12)
                    Spacer(minLength: 40)
                }
                .readableColumn(480)
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity, minHeight: geometry.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .background {
            GeometryReader { geometry in
                Image("AuthBackground").resizable().scaledToFill()
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .clipped()
                    .overlay { if textSize.isAccessibilitySize { Color(red: 1, green: 0.96, blue: 0.93).opacity(0.94) } }
            }.ignoresSafeArea()
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 12) {
                if let errorMessage {
                    Text(errorMessage).font(.callout)
                        .foregroundStyle(Color(red: 0.7, green: 0.07, blue: 0.16))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(.white, in: RoundedRectangle(cornerRadius: 16))
                        .accessibilityIdentifier("signInError")
                }
                ForEach(SignInMethod.allCases, id: \.self) { method in
                    SignInButton(method: method, signingIn: signingInWith == method) { signIn(method) }
                        .disabled(busy)
                }
            }
            .readableColumn(480)
            .padding(.horizontal, 20).padding(.bottom, 12)
        }
        // Android's owned landing artwork has one intentional light appearance.
        .preferredColorScheme(.light)
        .analyticsStep(.signIn)
    }
}

/// Google's white button, and Apple's black one under it, the same size.
private struct SignInButton: View {
    let method: SignInMethod
    let signingIn: Bool
    let action: () -> Void

    private var foreground: Color { method == .google ? .black : .white }
    private var background: Color { method == .google ? .white : .black }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if signingIn { ProgressView().tint(foreground) }
                else { mark.frame(width: 22, height: 22).accessibilityHidden(true) }
                Text(signingIn ? "Signing in…" : method == .google ? "Continue with Google" : "Continue with Apple")
                    .font(.custom("Poppins-SemiBold", size: 14, relativeTo: .callout))
            }
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity, minHeight: 54)
            .background(background.opacity(signingIn ? 0.85 : 1), in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(method == .google ? "googleSignIn" : "appleSignIn")
    }

    @ViewBuilder private var mark: some View {
        switch method {
        case .google: Image("GoogleMark").resizable().scaledToFit()
        case .apple: Image(systemName: "apple.logo").font(.system(size: 19, weight: .medium)).offset(y: -1)
        }
    }
}
