package app.betterdesk.core

/** Navigation can leave our own app; remote taps can never authorize a request. */
object AndroidInputPolicy {
    fun allows(owner: String, ownPackage: String, action: String): Boolean {
        if (owner.isEmpty() || owner.contains("permissioncontroller") || owner == "com.android.settings" || owner == "com.android.systemui") return false
        return owner != ownPackage || action == "home" || action == "back"
    }
}
