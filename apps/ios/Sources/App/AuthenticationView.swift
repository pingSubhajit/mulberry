import SwiftUI

struct AuthenticationView: View {
    let isSigningIn: Bool
    let errorMessage: String?
    let signIn: () -> Void
    @Environment(\.dynamicTypeSize) private var textSize

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 0) {
                    Image("BrandMark")
                        .resizable().scaledToFit()
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
                        .foregroundStyle(Color(red: 111 / 255, green: 89 / 255, blue: 97 / 255))
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: 320)
                        .padding(.top, 12)
                    Spacer(minLength: 40)
                }
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
                Button(action: signIn) {
                    HStack(spacing: 10) {
                        if isSigningIn { ProgressView().tint(.black) }
                        else { Image("GoogleMark").resizable().scaledToFit().frame(width: 22, height: 22).accessibilityHidden(true) }
                        Text(isSigningIn ? "Signing in…" : "Continue with Google")
                            .font(.custom("Poppins-SemiBold", size: 14, relativeTo: .callout))
                    }
                    .foregroundStyle(.black)
                    .frame(maxWidth: .infinity, minHeight: 54)
                    .background(.white.opacity(isSigningIn ? 0.85 : 1), in: RoundedRectangle(cornerRadius: 16))
                }
                .buttonStyle(.plain)
                .disabled(isSigningIn)
                .accessibilityIdentifier("googleSignIn")
            }
            .padding(.horizontal, 20).padding(.bottom, 12)
        }
        // Android's owned landing artwork has one intentional light appearance.
        .preferredColorScheme(.light)
    }
}
