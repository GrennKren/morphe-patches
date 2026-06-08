import com.android.build.api.dsl.ApplicationExtension

dependencies {
    compileOnly(project(":extensions:shared:library"))
    compileOnly(libs.morphe.extensions.library)
}

configure<ApplicationExtension> {
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }
}
