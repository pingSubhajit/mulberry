import SwiftUI
#if canImport(ProductCore)
import ProductCore
#endif

extension View {
    /// Records the onboarding step each time this screen appears, not each time it redraws.
    public func analyticsStep(_ step: OnboardingStep) -> some View {
        onAppear { AppAnalytics.track(.onboardingStepViewed(step)) }
    }

    /// Records a setup step as viewed each time this screen appears.
    public func analyticsStep(_ step: WallpaperSetupStep, on surface: PresenceSurfaceType) -> some View {
        onAppear { AppAnalytics.track(.wallpaperSetupStep(surface: surface, step: step, outcome: .viewed)) }
    }
}
