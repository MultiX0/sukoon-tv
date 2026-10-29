import java.awt.Color
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.util.Properties
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

plugins {
    id("com.android.application")
}

// The product name lives here and nowhere else. Renaming the launcher means
// changing these two lines; strings, the launcher label and the HTTP user
// agent are all generated from them.
val brandName = "Sukoon"
val brandNameAr = "سكون"

// Releases take their version from the git tag (the workflow passes -PversionName=1.2.0). The version code is
// derived from it (1.2.0 -> 10200), so every newer tag installs over the older one.
val appVersionName = (project.findProperty("versionName") as String?)?.removePrefix("v") ?: "1.0.0"
val appVersionCode = appVersionName.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    .let { (it + listOf(0, 0, 0)).take(3) }
    .also { require(it[1] < 100 && it[2] < 100) { "minor and patch must stay below 100: $appVersionName" } }
    .let { it[0] * 10000 + it[1] * 100 + it[2] }

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.multix.tvhome"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.multix.tvhome"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        resValue("string", "brand_name", brandName)
        resValue("string", "brand_name_ar", brandNameAr)
        buildConfigField("String", "BRAND", "\"$brandName\"")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += listOf("META-INF/*.version", "META-INF/**/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.palette:palette:1.0.0")
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
}

/**
 * Copies the curated wallpaper sets from tv-box/wallpapers/<set>/ into the APK
 * assets. Large photos are scaled down to the TV resolution here, once, so the
 * box never has to decode a 4K JPEG. When no set exists yet, a small generated
 * placeholder set is bundled instead so the launcher still has something calm
 * to show.
 */
abstract class PrepareWallpapers : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input
    abstract val rootPath: Property<String>

    @get:Input
    abstract val maxWidth: Property<Int>

    @get:Input
    abstract val maxHeight: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        System.setProperty("java.awt.headless", "true")
        val root = File(rootPath.get())
        val out = outputDir.get().asFile.resolve("wallpapers")
        out.deleteRecursively()
        out.mkdirs()

        val exts = setOf("jpg", "jpeg", "png", "webp")
        val sets = (root.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("_") }
            .filter { dir -> (dir.listFiles() ?: emptyArray()).any { it.extension.lowercase() in exts } }
            .sortedBy { it.name.lowercase() }

        if (sets.isEmpty()) {
            logger.lifecycle("wallpapers: no sets in ${root.path}, bundling the placeholder set")
            writePlaceholders(out.resolve("placeholder"))
            return
        }

        var count = 0
        for (set in sets) {
            val target = out.resolve(set.name)
            target.mkdirs()
            val images = (set.listFiles() ?: emptyArray())
                .filter { it.isFile && it.extension.lowercase() in exts }
                .sortedBy { it.name.lowercase() }
            for (img in images) {
                prepare(img, target)
                count++
            }
        }
        for (name in listOf("default.txt", "sets.json", "ATTRIBUTION.md")) {
            val f = root.resolve(name)
            if (f.isFile) f.copyTo(out.resolve(name), overwrite = true)
        }
        logger.lifecycle("wallpapers: bundled $count images in ${sets.size} sets")
    }

    private fun prepare(src: File, targetDir: File) {
        val image = try { ImageIO.read(src) } catch (e: Exception) { null }
        if (image == null) {
            // Formats ImageIO cannot read (webp, CMYK jpeg) are copied untouched;
            // the launcher samples them down when it decodes them.
            src.copyTo(targetDir.resolve(src.name), overwrite = true)
            return
        }
        val mw = maxWidth.get()
        val mh = maxHeight.get()
        val scale = maxOf(mw.toDouble() / image.width, mh.toDouble() / image.height)
        val isJpeg = src.extension.lowercase() in setOf("jpg", "jpeg")
        val aspectOk = Math.abs(image.width.toDouble() / image.height - mw.toDouble() / mh) < 0.01
        val outFile = targetDir.resolve(src.nameWithoutExtension + ".jpg")
        if (scale >= 1.0 && isJpeg && aspectOk) {
            src.copyTo(outFile, overwrite = true)
            return
        }
        val s = minOf(scale, 1.0)
        val sw = Math.max(mw, Math.round(image.width * s).toInt())
        val sh = Math.max(mh, Math.round(image.height * s).toInt())
        val scaled = resize(toRgb(image), sw, sh)
        val cw = minOf(mw, scaled.width)
        val ch = minOf(mh, scaled.height)
        val cropped = scaled.getSubimage((scaled.width - cw) / 2, (scaled.height - ch) / 2, cw, ch)
        writeJpeg(cropped, outFile)
    }

    private fun toRgb(src: BufferedImage): BufferedImage {
        if (src.type == BufferedImage.TYPE_INT_RGB) return src
        val rgb = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val g = rgb.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, src.width, src.height)
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return rgb
    }

    // Halving with bilinear steps and a final bicubic pass keeps fine detail
    // without the aliasing of a single large bilinear jump.
    private fun resize(src: BufferedImage, w: Int, h: Int): BufferedImage {
        var img = src
        var cw = img.width
        var ch = img.height
        while (cw / 2 >= w && ch / 2 >= h) {
            cw /= 2
            ch /= 2
            img = draw(img, cw, ch, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        }
        return if (cw == w && ch == h) img else draw(img, w, h, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    }

    private fun draw(src: BufferedImage, w: Int, h: Int, hint: Any): BufferedImage {
        val dst = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return dst
    }

    private fun writeJpeg(img: BufferedImage, file: File) {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = 0.92f
        ImageIO.createImageOutputStream(file).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
    }

    private fun writePlaceholders(dir: File) {
        dir.mkdirs()
        val w = maxWidth.get()
        val h = maxHeight.get()
        // Three quiet gradients: dusk, deep sea and ember.
        val palettes = listOf(
            listOf(Color(0x0B1026), Color(0x2B2F77), Color(0xC0587E), Color(0x141852)),
            listOf(Color(0x03151E), Color(0x0B4F5C), Color(0x3FA7A0), Color(0x062A33)),
            listOf(Color(0x150A0A), Color(0x5A1F1B), Color(0xE0894A), Color(0x2A0F12)),
        )
        palettes.forEachIndexed { i, p ->
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = p[0]
            g.fillRect(0, 0, w, h)
            fun blob(cx: Float, cy: Float, r: Float, c: Color) {
                val paint = RadialGradientPaint(
                    Point2D.Float(cx, cy), r, floatArrayOf(0f, 1f),
                    arrayOf(Color(c.red, c.green, c.blue, 230), Color(c.red, c.green, c.blue, 0))
                )
                g.paint = paint
                g.fillRect(0, 0, w, h)
            }
            blob(w * 0.78f, h * 0.30f, w * 0.62f, p[1])
            blob(w * 0.62f, h * 0.85f, w * 0.40f, p[2])
            blob(w * 0.10f, h * 0.95f, w * 0.45f, p[3])
            g.dispose()
            writeJpeg(img, dir.resolve("placeholder-${i + 1}.jpg"))
        }
    }
}

val wallpaperRoot: File = rootProject.file("../wallpapers")

val prepareWallpapers = tasks.register<PrepareWallpapers>("prepareWallpapers") {
    rootPath.set(wallpaperRoot.absolutePath)
    sources.from(fileTree(wallpaperRoot))
    // The box renders at 1280x720, so nothing bigger is worth shipping.
    maxWidth.set(1280)
    maxHeight.set(720)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareWallpapers, PrepareWallpapers::outputDir)
    }
}
