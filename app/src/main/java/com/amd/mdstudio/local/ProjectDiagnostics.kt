package com.amd.mdstudio.local

import java.io.File

/** Read-only Android/Gradle structural analysis. It never edits imported project files. */
object ProjectDiagnostics {
    data class Report(val projectRoot: File, val checks: List<Check>) {
        val failures: List<Check> get() = checks.filter { it.level == Level.FAIL }
        val warnings: List<Check> get() = checks.filter { it.level == Level.WARN }
        val summary: String get() = "${failures.size} أخطاء، ${warnings.size} تحذيرات، ${checks.count { it.level == Level.PASS }} نقاط سليمة"
        val readiness: String get() = when {
            failures.isNotEmpty() -> "BLOCKED"
            warnings.isNotEmpty() -> "READY_WITH_WARNINGS"
            else -> "NOT_VERIFIED"
        }
    }
    data class Check(val level: Level, val title: String, val detail: String)
    enum class Level { PASS, WARN, FAIL, INFO }

    fun analyze(project: File): Report {
        val root = project.canonicalFile
        val checks = mutableListOf<Check>()
        fun add(level: Level, title: String, detail: String) { checks += Check(level, title, detail) }
        fun first(vararg paths: String) = paths.map { File(root, it) }.firstOrNull { it.isFile }
        val settings = first("settings.gradle", "settings.gradle.kts")
        add(if (settings != null) Level.PASS else Level.FAIL, "إعداد Gradle", settings?.relativeTo(root)?.path ?: "settings.gradle أو settings.gradle.kts غير موجود")
        val rootBuild = first("build.gradle", "build.gradle.kts")
        add(if (rootBuild != null) Level.PASS else Level.FAIL, "ملف البناء الرئيسي", rootBuild?.relativeTo(root)?.path ?: "build.gradle أو build.gradle.kts غير موجود")
        val appBuild = first("app/build.gradle", "app/build.gradle.kts")
        add(if (appBuild != null) Level.PASS else Level.FAIL, "ملف وحدة app", appBuild?.relativeTo(root)?.path ?: "app/build.gradle أو app/build.gradle.kts غير موجود")
        val manifest = File(root, "app/src/main/AndroidManifest.xml")
        add(if (manifest.isFile) Level.PASS else Level.FAIL, "Android Manifest", if (manifest.isFile) "موجود" else "app/src/main/AndroidManifest.xml غير موجود")
        val wrapperProps = File(root, "gradle/wrapper/gradle-wrapper.properties")
        val wrapperJar = File(root, "gradle/wrapper/gradle-wrapper.jar")
        val gradlew = File(root, "gradlew")
        val gradlewBat = File(root, "gradlew.bat")
        listOf(
            "gradle/wrapper/gradle-wrapper.properties" to wrapperProps,
            "gradle/wrapper/gradle-wrapper.jar" to wrapperJar,
            "gradlew" to gradlew,
            "gradlew.bat" to gradlewBat
        ).forEach { (label, file) -> add(if (file.isFile) Level.PASS else Level.FAIL, "Gradle Wrapper", if (file.isFile) "$label موجود" else "$label غير موجود") }
        val sourceRoot = File(root, "app/src/main")
        val sourceFiles = if (sourceRoot.isDirectory) sourceRoot.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("kt", "java") }.toList() else emptyList()
        add(if (sourceFiles.isNotEmpty()) Level.PASS else Level.FAIL, "ملفات المصدر", "${sourceFiles.size} ملف Kotlin/Java داخل app/src/main")
        val buildFiles = root.walkTopDown().onEnter { dir -> dir == root || dir.name !in setOf(".git", ".gradle", "build", ".idea") }
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }.toList()
        add(if (buildFiles.isNotEmpty()) Level.PASS else Level.FAIL, "وحدات Gradle", "${buildFiles.size} ملف بناء تم العثور عليه")
        val catalog = File(root, "gradle/libs.versions.toml")
        add(if (catalog.isFile) Level.INFO else Level.INFO, "Version Catalog", if (catalog.isFile) "gradle/libs.versions.toml موجود" else "غير مستخدم أو غير موجود")
        val workflows = File(root, ".github/workflows")
        add(Level.INFO, "GitHub Actions", if (workflows.isDirectory) "${workflows.walkTopDown().count { it.isFile && it.extension.lowercase() in setOf("yml", "yaml") }} ملف workflow" else "لا توجد workflows")
        val localProperties = File(root, "local.properties")
        if (localProperties.isFile) add(Level.INFO, "إعداد SDK المحلي", "local.properties موجود؛ لا تفترض صلاحية المسار خارج جهاز صاحبه")
        add(Level.INFO, "مخرجات البناء القديمة", "build/ و.gradle/ وملفات APK/AAB تظل محفوظة في النسخة المستوردة؛ تجاهلها يخص مرحلة البناء فقط")
        add(Level.INFO, "التوقيع والأسرار", "تم الحفاظ على ملفات المشروع كما هي؛ يمنع رفع مفاتيح التوقيع وكلمات المرور والأسرار إلى GitHub")
        add(Level.INFO, "حدود التحليل", "تحليل بنيوي وقراءة ملفات فقط؛ لم يتم التحقق من توافق الإصدارات أو تنفيذ Gradle أو تشغيل APK")
        return Report(root, checks)
    }
}
