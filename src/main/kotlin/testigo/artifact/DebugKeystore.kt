package testigo.artifact

import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * La debug key con la que bundletool firma los APKs que genera desde un AAB cuando no hay `app.signing`.
 * bundletool la busca en `.android/debug.keystore` dentro de `ANDROID_SDK_HOME`, de `user.home` y de `HOME`. Sin ella
 * genera APKs sin firmar, que Android no instala. Android Studio la crea la primera vez que compila: en una máquina
 * que nunca compiló una app, no existe.
 */
object DebugKeystore {

    fun locate(env: Map<String, String> = System.getenv(), userHome: String? = System.getProperty("user.home")): Path? =
        listOfNotNull(env["ANDROID_SDK_HOME"], userHome, env["HOME"])
            .filter { it.isNotBlank() }
            .map { Path.of(it, ".android", "debug.keystore") }
            .firstOrNull { it.isRegularFile() }

    /** Lo mismo que hace Android Studio: alias `androiddebugkey` y clave `android`, públicas por diseño. */
    const val CREATE_COMMAND =
        "mkdir -p ~/.android && keytool -genkeypair -keystore ~/.android/debug.keystore -alias androiddebugkey " +
            "-storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname \"CN=Android Debug,O=Android,C=US\""

    /** El aviso de bundletool cuando no encontró con qué firmar. */
    internal const val UNSIGNED_WARNING = "won't be signed"
}
