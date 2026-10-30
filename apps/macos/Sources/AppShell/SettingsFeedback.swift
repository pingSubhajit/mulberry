import AppKit
import ProductCore
import ProductUI
import SwiftUI
import WebKit

/// The iPhone's feedback board, pushed inside Settings as System Settings pushes a detail page:
/// Canny's web view, signed in as the Mulberry account. Links that leave the board open in the
/// browser.
struct FeedbackPage: View {
    let api: () -> BackendClient

    var body: some View {
        FeedbackView(api: api)
            .clipShape(.rect(cornerRadius: 14, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(.primary.opacity(0.08))
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
    }
}

private struct FeedbackView: View {
    let api: () -> BackendClient
    @State private var url: URL?
    @State private var error: String?

    var body: some View {
        Group {
            if let error {
                VStack(spacing: 14) {
                    Text(error)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button("Try Again") { Task { await load() } }
                        .buttonStyle(.settingsProminent)
                }
                .padding(32)
            } else if let url {
                FeedbackWebView(url: url, error: $error)
            } else {
                DelayedProgressView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task { await load() }
    }

    private func load() async {
        error = nil
        url = nil
        do {
            let token = try await api().cannyToken()
            var components = URLComponents(string: "https://webview.canny.io")!
            components.queryItems = [
                URLQueryItem(name: "boardToken", value: "6a631c96-1dd3-3c43-0653-b53d1edd0828"),
                URLQueryItem(name: "ssoToken", value: token),
                URLQueryItem(name: "theme", value: "auto")
            ]
            url = components.url
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// The board, with nothing kept between visits, as on the iPhone.
private struct FeedbackWebView: NSViewRepresentable {
    let url: URL
    @Binding var error: String?

    func makeCoordinator() -> Coordinator { Coordinator(error: $error) }

    func makeNSView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.uiDelegate = context.coordinator
        view.load(URLRequest(url: url))
        return view
    }

    func updateNSView(_ view: WKWebView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        @Binding var error: String?

        init(error: Binding<String?>) { _error = error }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            self.error = "Feedback could not load. Please try again."
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            self.error = "Feedback could not load. Please try again."
        }

        /// A link meant for a new window leaves the board for the browser.
        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if let url = navigationAction.request.url { NSWorkspace.shared.open(url) }
            return nil
        }
    }
}
