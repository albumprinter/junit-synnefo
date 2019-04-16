package albelli.junit.synnefo.runtime

import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal class ZipHelper{
    companion object {
        fun unzip(fileZip: ByteArray, dest: String) {
            unzip(ByteArrayInputStream(fileZip), dest)
        }

        fun unzip(fileZip: InputStream, dest: String) {
            val destDir = File(dest)
            destDir.mkdirs()
            val buffer = ByteArray(1024)
            val zis = ZipInputStream(fileZip)
            var zipEntry: ZipEntry? = zis.nextEntry
            while (zipEntry != null) {
                val newFile = newFile(destDir, zipEntry)
                val fos = FileOutputStream(newFile)
                var len: Int
                len = zis.read(buffer)
                while (len > 0) {
                    fos.write(buffer, 0, len)
                    len = zis.read(buffer)
                }
                fos.close()
                zipEntry = zis.nextEntry
            }
            zis.closeEntry()
            zis.close()
        }

        private fun newFile(destinationDir: File, zipEntry: ZipEntry): File {
            val destFile = File(destinationDir, zipEntry.name)

            val destDirPath = destinationDir.canonicalPath
            val destFilePath = destFile.canonicalPath

            if (!destFilePath.startsWith(destDirPath + File.separator)) {
                throw IOException("Entry is outside of the target dir: " + zipEntry.name)
            }

            return destFile
        }
    }
}