import SwiftUI

/// Semantic native tokens. Setup follows appearance; Ride Mode requests dark appearance.
enum RideDesign {
    static let xs: CGFloat = 4
    static let sm: CGFloat = 8
    static let md: CGFloat = 12
    static let lg: CGFloat = 16
    static let xl: CGFloat = 24
    static let xxl: CGFloat = 32
    static let touch: CGFloat = 48
    static let rideTouch: CGFloat = 72
    static let radius: CGFloat = 16
    static let background = Color(uiColor: .systemGroupedBackground)
    static let surface = Color(uiColor: .secondarySystemGroupedBackground)
    static let primary = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.55, green: 0.87, blue: 0.81, alpha: 1)
            : UIColor(red: 0, green: 0.42, blue: 0.35, alpha: 1)
    })
    static let onPrimary = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark ? .black : .white
    })
    static let ptt = Color(red: 0.55, green: 0.87, blue: 0.81)
    static let pttActive = Color(red: 0.95, green: 0.80, blue: 0.50)
    static let warning = Color(uiColor: .systemOrange)
    static let error = Color(uiColor: .systemRed)
}

extension View {
    func rideSurface() -> some View {
        padding(RideDesign.lg)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RideDesign.surface, in: RoundedRectangle(cornerRadius: RideDesign.radius))
    }
}

/// GestureState resets on cancellation as well as release. Accessibility uses explicit start/stop.
struct PushToTalkControl: View {
    let available: Bool
    let muted: Bool
    let held: Bool
    let onHeld: (Bool) -> Void
    @GestureState private var pressing = false

    var body: some View {
        Button {} label: {
            Text(held ? "Push to Talk held · Release to stop" : "Hold to talk")
                .font(.headline).foregroundStyle(available && !muted ? Color.black : Color.secondary)
                .frame(maxWidth: .infinity, minHeight: RideDesign.rideTouch)
        }
        .buttonStyle(.borderedProminent)
        .tint(held ? RideDesign.pttActive : RideDesign.ptt)
        .disabled(!available || muted)
        .simultaneousGesture(DragGesture(minimumDistance: 0).updating($pressing) { _, value, _ in value = true })
        .onChange(of: pressing) { _, value in onHeld(value && available && !muted) }
        .onChange(of: available) { _, value in if !value { onHeld(false) } }
        .onChange(of: muted) { _, value in if value { onHeld(false) } }
        .onDisappear { onHeld(false) }
        .accessibilityAction(named: Text("Start talking")) { if available && !muted { onHeld(true) } }
        .accessibilityAction(named: Text("Stop talking")) { onHeld(false) }
    }
}
