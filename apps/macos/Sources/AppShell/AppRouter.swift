import Foundation

@MainActor
final class AppRouter: ObservableObject {
    @Published var selectedRoute: AppRoute = .canvasHome
    @Published var path: [AppRoute] = []

    func open(_ route: AppRoute) {
        selectedRoute = route
        path.removeAll()
    }

    func push(_ route: AppRoute) {
        path.append(route)
    }

    func pop() {
        _ = path.popLast()
    }

    func goHome() {
        open(.canvasHome)
    }
}
