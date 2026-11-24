package com.amd.mdstudio.local

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * ZIP intake for MD Studio. The imported tree is immutable; a separate working
 * copy is activated only after extraction, integrity checks and read-only analysis.
 * Archive contents (including build outputs, hidden files and signing files) are
 * retained. Build/upload filters are applied by their own stages, never here.
 */
class ProjectArchiveManager(private val context: Context) {
    private val workspace = File(context.filesDir, "projects").apply { mkdirs() }
    private val originals = File(context.filesDir, "imported-originals").apply { mkdirs() }
    private val stagingRoot = File(context.cacheDir, "mdstudio-zip-staging").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("mdstudio_workspace", Context.MODE_PRIVATE)

    data class ImportReport(
        val original: File,
        val workingCopy: File,
        val archiveEntries: Int,
        val extractedFiles: Int,
        val directories: Int,
        val extractedBytes: Long,
        val sha256ByPath: Map<String, String>,
        val skipped: List<String>,
        val warnings: List<String>,
        val missingRequired: List<String>,
        val readiness: String
    )

    @Volatile var lastImportReport: ImportReport? = null
        private set

    fun currentProject(): File? = prefs.getString("current_project", null)?.let(::File)?.takeIf { it.isDirectory }

    fun activateProject(project: File): Result<File> = runCatching {
        val canonical = project.canonicalFile
        check(canonical.isDirectory) { "مجلد المشروع غير موجود" }
        check(File(canonical, "settings.gradle.kts").isFile || File(canonical, "settings.gradle").isFile) {
            "المجلد ليس مشروع Gradle صالحًا"
        }
        prefs.edit().putString("current_project", canonical.absolutePath).apply()
        canonical
    }

    fun importZip(uri: Uri): Result<File> = runCatching {
        val stagedZip = File(stagingRoot, "incoming-${System.nanoTime()}.zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(stagedZip).buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            }
        } ?: error("تعذر فتح ملف ZIP")
        try { importZipFile(stagedZip) } finally { stagedZip.delete() }
    }

    fun importZip(zipFile: File): Result<File> = runCatching { importZipFile(zipFile) }

    private fun importZipFile(zipFile: File): File {
        require(zipFile.isFile) { "ملف ZIP غير موجود" }
        require(zipFile.name.endsWith(".zip", ignoreCase = true)) { "الصيغة غير مدعومة؛ ارفع ملف ZIP فقط" }
        val id = "${System.currentTimeMillis()}-${zipFile.length()}"
        val staging = File(stagingRoot, id).apply { deleteRecursively(); mkdirs() }
        val original = File(originals, id)
        val working = File(workspace, "import-$id")
        val skipped = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val hashes = linkedMapOf<String, String>()
        var count = 0; var dirs = 0; var total = 0L
        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.isNotEmpty()) { "ملف ZIP فارغ" }
                val exactNames = HashSet<String>()
                val foldedNames = HashMap<String, String>()
                // Validate the complete central directory before writing any extracted file.
                entries.forEach { entry ->
                    val raw = entry.name
                    validateEntryName(raw)
                    require(exactNames.add(raw)) { "اسم ملف مكرر داخل ZIP: $raw" }
                    val folded = raw.lowercase(Locale.ROOT)
                    val previous = foldedNames.putIfAbsent(folded, raw)
                    require(previous == null || previous == raw) { "تعارض أسماء باختلاف حالة الأحرف: $previous / $raw" }
                    if (!entry.isDirectory) {
                        require(entry.size >= 0 && entry.compressedSize >= 0) { "بيانات حجم غير صالحة: $raw" }
                        require(entry.size <= 2L * 1024 * 1024 * 1024) { "حجم ملف غير آمن داخل الأرشيف: $raw" }
                        if (entry.size > 10L * 1024 * 1024 && entry.compressedSize > 0) {
                            require(entry.size.toDouble() / entry.compressedSize.toDouble() <= 1000.0) { "اشتباه ZIP Bomb: $raw" }
                        }
                    }
                }
                entries.forEach { entry ->
                    val out = File(staging, entry.name).canonicalFile
                    require(out.path.startsWith(staging.canonicalPath + File.separator) || out == staging.canonicalFile) {
                        "مسار خارج مجلد الاستخراج: ${entry.name}"
                    }
                    if (entry.isDirectory) { out.mkdirs(); dirs++ }
                    else {
                        out.parentFile?.mkdirs()
                        val digest = MessageDigest.getInstance("SHA-256")
                        var written = 0L
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(out).buffered().use { output ->
                                val buffer = ByteArray(128 * 1024)
                                while (true) {
                                    val n = input.read(buffer); if (n < 0) break
                                    written += n; total += n
                                    require(total <= 2L * 1024 * 1024 * 1024) { "الحجم الإجمالي بعد فك الضغط تجاوز حد الأمان 2 GB" }
                                    require(written <= entry.size) { "حجم مستخرج أكبر من المعلن: ${entry.name}" }
                                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                                }
                            }
                        }
                        require(written == entry.size) { "حجم الملف بعد الاستخراج غير مطابق: ${entry.name}" }
                        hashes[entry.name] = digest.digest().joinToString("") { "%02x".format(it) }
                        count++
                    }
                }
                require(count > 0) { "الأرشيف لا يحتوي على ملفات" }
            }
            val root = findProjectRoot(staging) ?: error("لم يتم العثور على جذر مشروع يحتوي settings.gradle أو settings.gradle.kts")
            // Preserve the full extracted archive tree as the immutable imported original.
            check(staging.renameTo(original)) { "تعذر حفظ النسخة الأصلية المستوردة" }
            val relativeRoot = root.relativeTo(staging).path
            val preservedRoot = if (relativeRoot.isBlank()) original else File(original, relativeRoot)
            check(preservedRoot.isDirectory) { "تعذر تحديد جذر النسخة الأصلية" }
            preservedRoot.copyRecursively(working, overwrite = false)
            check(working.isDirectory) { "فشل إنشاء نسخة العمل" }
            val missing = requiredFiles(working)
            val readiness = when {
                missing.isNotEmpty() -> "BLOCKED"
                warnings.isNotEmpty() -> "READY_WITH_WARNINGS"
                else -> "READY_WITH_WARNINGS" // Gradle/SDK compatibility and an actual build are not verified at import time.
            }
            if (!File(working, "gradlew").isFile) warnings += "gradlew غير موجود"
            if (!File(working, "gradlew.bat").isFile) warnings += "gradlew.bat غير موجود"
            if (!File(working, "gradle/wrapper/gradle-wrapper.jar").isFile) warnings += "gradle-wrapper.jar غير موجود"
            if (!File(working, "gradle/wrapper/gradle-wrapper.properties").isFile) warnings += "gradle-wrapper.properties غير موجود"
            val finalReadiness = if (missing.isNotEmpty()) "BLOCKED" else "READY_WITH_WARNINGS"
            lastImportReport = ImportReport(original, working, hashes.size + dirs, count, dirs, total, hashes, skipped, warnings, missing, finalReadiness)
            prefs.edit().putString("current_project", working.absolutePath).apply()
            return working
        } catch (t: Throwable) {
            staging.deleteRecursively(); working.deleteRecursively()
            throw t
        }
    }

    private fun validateEntryName(name: String) {
        require(name.isNotBlank() && '\u0000' !in name) { "اسم مسار فارغ أو غير آمن" }
        require(!name.startsWith('/') && !name.startsWith('\\') && !Regex("^[A-Za-z]:").containsMatchIn(name)) { "مسار مطلق مرفوض: $name" }
        val normalized = name.replace('\\', '/')
        require(normalized.split('/').none { it == ".." || it == "." }) { "مسار يحتوي على traversal مرفوض: $name" }
        require(!normalized.contains(':')) { "رمز مسار غير آمن: $name" }
    }

    private fun requiredFiles(root: File): List<String> {
        val missing = mutableListOf<String>()
        if (!File(root, "settings.gradle").isFile && !File(root, "settings.gradle.kts").isFile) missing += "settings.gradle أو settings.gradle.kts"
        if (!File(root, "build.gradle").isFile && !File(root, "build.gradle.kts").isFile) missing += "build.gradle أو build.gradle.kts"
        if (!File(root, "app/build.gradle").isFile && !File(root, "app/build.gradle.kts").isFile) missing += "app/build.gradle أو app/build.gradle.kts"
        if (!File(root, "app/src/main/AndroidManifest.xml").isFile) missing += "app/src/main/AndroidManifest.xml"
        if (!File(root, "gradle/wrapper/gradle-wrapper.properties").isFile) missing += "gradle/wrapper/gradle-wrapper.properties"
        if (!File(root, "gradlew").isFile) missing += "gradlew"
        if (!File(root, "gradlew.bat").isFile) missing += "gradlew.bat"
        if (!File(root, "gradle/wrapper/gradle-wrapper.jar").isFile) missing += "gradle/wrapper/gradle-wrapper.jar"
        val source = File(root, "app/src/main").walkTopDown().any { it.isFile && it.extension.lowercase() in setOf("java", "kt") }
        if (!source) missing += "app/src/main/java/... أو app/src/main/kotlin/..."
        return missing
    }

    private fun findProjectRoot(root: File): File? {
        fun isRoot(dir: File) = File(dir, "settings.gradle").isFile || File(dir, "settings.gradle.kts").isFile
        if (isRoot(root)) return root
        val candidates = root.walkTopDown().onEnter { dir -> dir == root || dir.relativeTo(root).invariantSeparatorsPath.split('/').size <= 3 }
            .filter { it.isDirectory && isRoot(it) }.toList()
        require(candidates.size <= 1) { "تم العثور على أكثر من جذر Gradle داخل ZIP؛ لا يمكن اختيار واحد تلقائيًا" }
        return candidates.singleOrNull()
    }

    /** Copies an AI file workspace into the build-project area only after finding a Gradle root. */
    fun importWorkspaceDirectory(source: File): Result<File> = runCatching {
        val sourceRoot = source.canonicalFile
        check(sourceRoot.isDirectory) { "مساحة الملفات غير موجودة" }
        val gradleRoot = findProjectRoot(sourceRoot) ?: error("المساحة لا تحتوي settings.gradle أو settings.gradle.kts؛ لا يمكن إرسالها للبناء")
        val destination = File(workspace, "workspace-project-${System.currentTimeMillis()}").canonicalFile
        check(destination.path.startsWith(workspace.canonicalPath + File.separator))
        gradleRoot.copyRecursively(destination, overwrite = false)
        check(destination.isDirectory) { "فشل نسخ مساحة العمل" }
        activateProject(destination).getOrThrow()
    }

    fun createProjectZip(project: File, destination: File): Result<File> = runCatching {
        check(project.isDirectory) { "لا يوجد مشروع صالح لتصديره" }
        destination.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            project.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(project).invariantSeparatorsPath
                val lowerName = file.name.lowercase()
                if (relative.startsWith(".gradle/") || relative.startsWith(".git/") ||
                    relative.split('/').any { it in setOf("build", ".idea", "legacy-reference", ".mdstudio-repair-backups", ".mdstudio-checkpoints") } ||
                    lowerName in setOf("local.properties", "secrets.properties", "google-services.json", ".npmrc", ".mdstudio-ai-workspace.json") ||
                    lowerName.startsWith(".env") || lowerName.startsWith("service-account") ||
                    listOf(".jks", ".keystore", ".p12", ".pfx", ".pem", ".apk", ".aab", ".gguf").any { lowerName.endsWith(it) }) return@forEach
                zip.putNextEntry(ZipEntry(relative)); file.inputStream().buffered().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        destination
    }
}
