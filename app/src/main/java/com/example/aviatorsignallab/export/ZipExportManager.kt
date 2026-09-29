package com.example.aviatorsignallab.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.aviatorsignallab.db.ResearchDatabase
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipExportManager(private val context: Context) {

    private val db = ResearchDatabase.getDatabase(context)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun exportAllAsZip(): File = withContext(Dispatchers.IO) {
        val exportDir = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val tempDir = File(exportDir, "temp_${System.currentTimeMillis()}").apply { mkdirs() }

        val rounds = db.roundDao().getAllRounds()
        val events = db.liveEventDao().getAllEvents()
        val features = db.featureDao().getAllFeatures()
        val patterns = db.featureDao().getAllPatterns()

        val fRounds = File(tempDir, "rounds.csv")
        val fEvents = File(tempDir, "live_events.csv")
        val fFeatures = File(tempDir, "round_features.csv")
        val fFields = File(tempDir, "protocol_fields.csv")
        val fPatterns = File(tempDir, "discovered_patterns.csv")
        val fManifest = File(tempDir, "manifest.json")

        CsvExporter.exportRounds(rounds, fRounds)
        CsvExporter.exportLiveEvents(events, fEvents)
        CsvExporter.exportFeatures(features, fFeatures)
        CsvExporter.exportProtocolFields(events, fFields)
        CsvExporter.exportDiscoveredPatterns(patterns, fPatterns)

        // Generate manifest
        val manifest = mapOf(
            "app" to "Aviator Signal Lab",
            "version" to "1.0.0",
            "exportedAt" to System.currentTimeMillis(),
            "totalRounds" to rounds.size,
            "totalEvents" to events.size,
            "totalFeatures" to features.size,
            "totalPatterns" to patterns.size,
            "preCrashWindows" to listOf("T_5.0s_TO_0.1s", "T_3.0s_TO_0.1s", "T_2.0s_TO_0.1s", "T_1.0s_TO_0.1s", "T_0.5s_TO_0.1s", "T_0.25s_TO_0.1s")
        )
        fManifest.writeText(gson.toJson(manifest))

        // Create Zip file
        val zipFile = File(exportDir, "AviatorSignalLab_Dataset_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val filesToZip = listOf(fRounds, fEvents, fFeatures, fFields, fPatterns, fManifest)
            for (file in filesToZip) {
                if (file.exists()) {
                    zos.putNextEntry(ZipEntry(file.name))
                    FileInputStream(file).use { fis ->
                        fis.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }
        }

        // Cleanup temp folder
        tempDir.deleteRecursively()

        zipFile
    }

    fun shareExportFile(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.name.endsWith(".zip")) "application/zip" else "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(shareIntent, "Export Dataset").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
