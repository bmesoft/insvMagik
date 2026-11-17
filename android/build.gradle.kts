// Same plugin versions as mobiCut (../mobicut/platform/android), so the two share the Gradle cache
// and code can move between them.
plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
