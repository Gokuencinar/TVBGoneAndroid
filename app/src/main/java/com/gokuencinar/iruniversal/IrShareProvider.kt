package com.gokuencinar.iruniversal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

class IrShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "text/plain"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Solo lectura")
        val file = resolveFile(uri)
        if (!file.isFile) throw FileNotFoundException(file.name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val file = resolveFile(uri)
        val requested = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val supported = requested.filter {
            it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE
        }
        val cursor = MatrixCursor(supported.toTypedArray(), 1)
        val row = cursor.newRow()
        supported.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(file.name)
                OpenableColumns.SIZE -> row.add(file.length())
            }
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun resolveFile(uri: Uri): File {
        val appContext = context ?: throw FileNotFoundException("Contexto no disponible")
        val requestedName = uri.lastPathSegment?.let(Uri::decode)
            ?.takeIf { it.isNotBlank() }
            ?: throw FileNotFoundException("Nombre no válido")
        val safeName = File(requestedName).name
        val root = File(appContext.cacheDir, "shared-ir").apply { mkdirs() }.canonicalFile
        val file = File(root, safeName).canonicalFile
        if (file.parentFile != root) throw FileNotFoundException("Ruta no válida")
        return file
    }
}
