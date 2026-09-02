package com.zires.pdfjs;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loads PDF.js by downloading and extracting the official distribution ZIP.
 * <p>
 * All network and I/O operations run on a background thread.
 * Progress and results are posted to the main thread via the callback.
 */
public class PdfJsLoader {

    private static final String TAG = "PdfJsLoader";
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 15000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final File destFolder;

    /**
     * Creates a loader that stores PDF.js assets under the given folder.
     *
     * @param folder base directory where the {@code pdfjs/} sub-folder will be created
     */
    public PdfJsLoader(File folder) {
        this.destFolder = folder;
    }

    /**
     * Callback interface for PDF.js loading status.
     */
    public interface Callback {
        /**
         * Called on the main thread when PDF.js is ready.
         */
        void onLoaded(File newVersionFolder);

        /**
         * Called on the main thread when an error occurs.
         */
        void onError(Exception e);

    }

    // ----------------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------------

    /**
     * Checks if a specific version is already installed.
     *
     * @param version version identifier (as returned by {@link #extractVersionFromUrl})
     * @return the version directory if it exists and is non-empty, otherwise {@code null}
     */
    public File getVersionInstalled(String version) {
        File versionDir = getVersionDir(version);
        File[] content = versionDir.listFiles();
        return (versionDir.exists() && content != null && content.length > 0) ? versionDir : null;
    }

    /**
     * Asynchronously downloads and extracts PDF.js from the given ZIP URL.
     *
     * @param zipUrl   full URL to the PDF.js ZIP (e.g., from GitHub releases)
     * @param callback callback for progress and result (may be null)
     */
    public void loadPdfJs(String zipUrl, Callback callback) {
        // Already installed – notify immediately on main thread
        File cachedVersion = getVersionInstalled(extractVersionFromUrl(zipUrl));
        if (cachedVersion != null) {
            if (callback != null) {
                mainHandler.post(() -> callback.onLoaded(cachedVersion));
            }
            return;
        }

        executor.execute(() -> {
            try {
                File zipFile = new File(destFolder, "pdfjs/" + extractVersionFromUrl(zipUrl) + ".zip");
                File extractedFolder = new File(destFolder, "pdfjs/" + extractVersionFromUrl(zipUrl));

                // 1. Download distribution zip
                downloadFile(zipUrl, zipFile);

                // 2. Extract assets
                unzip(zipFile, extractedFolder);

                // Clean up zip archive
                if (zipFile.exists()) {
                    zipFile.delete();
                }

                if (callback != null) {
                    mainHandler.post(() -> callback.onLoaded(extractedFolder));
                }
            } catch (Exception e) {
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e));
                }
            }
        });
    }
    /**
     * Downloads a file from the given URL to the specified destination.
     * <p>
     * HTTP redirects (301, 302, 303, 307, 308) are followed automatically.
     * If the server returns a non-{@link java.net.HttpURLConnection#HTTP_OK} status,
     * an {@link IOException} is thrown.
     *
     * @param fileUrl    full URL of the file to download
     * @param destination target file where the content will be written
     * @throws IOException if the download fails or the server returns an error status
     */
    public static void downloadFile(String fileUrl, File destination) throws IOException {
        File parentDir = destination.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create destination directories: " + destination.getAbsolutePath());
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(fileUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();

            // Handle HTTP redirects (GitHub releases redirect to S3 storage)
            if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                    || responseCode == 307 || responseCode == 308) {

                String newUrl = connection.getHeaderField("Location");
                if (newUrl != null) {
                    downloadFile(newUrl, destination);
                    return;
                }
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP status " + responseCode + " for: " + fileUrl);
            }

            try (InputStream is = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fos = new FileOutputStream(destination)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Extracts the contents of a ZIP archive into the given target directory.
     * <p>
     * Sub-directories are created as needed. This implementation does not perform
     * path-traversal validation; callers must ensure the archive contents are trusted.
     *
     * @param zipFile    the ZIP archive to extract
     * @param targetDir  the directory into which the archive contents will be written
     * @throws IOException if an I/O error occurs during extraction
     */
    public static void unzip(File zipFile, File targetDir) throws IOException {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("Failed to create target directory: " + targetDir.getAbsolutePath());
        }

        String targetDirPath = targetDir.getCanonicalPath();

        try (FileInputStream fis = new FileInputStream(zipFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             ZipInputStream zis = new ZipInputStream(bis)) {

            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];

            while ((entry = zis.getNextEntry()) != null) {
                File destFile = new File(targetDir, entry.getName());

                if (entry.isDirectory()) {
                    if (!destFile.exists() && !destFile.mkdirs()) {
                        throw new IOException("Failed to create folder: " + destFile.getAbsolutePath());
                    }
                } else {
                    File parentDir = destFile.getParentFile();
                    if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                        throw new IOException("Failed to create directory: " + destFile.getAbsolutePath());
                    }

                    try (FileOutputStream fos = new FileOutputStream(destFile);
                         BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Deletes all files for a specific version (to free space or force re-download).
     *
     * @param version version identifier (as returned by {@link #extractVersionFromUrl})
     */
    public void deleteVersion(String version) {
        File dir = getVersionDir(version);
        if (dir.exists()) {
            deleteRecursive(dir);
        }
    }

    /**
     * Shuts down the background executor.
     * <p>
     * Should be called (e.g. in {@code onDestroy}) to release thread resources.
     * After this call the loader can no longer perform asynchronous operations.
     */
    public void shutdown() {
        executor.shutdown();
    }

    // ----------------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------------

    private File getVersionDir(String version) {
        return new File(destFolder, "pdfjs/" + version);
    }

    /**
     * Extracts the version/folder name from the ZIP URL.
     * Example: "https://.../pdfjs-6.2.108-dist.zip" → "pdfjs-6.2.108-dist"
     */
    private String extractVersionFromUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            String path = url.getPath();
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            if (fileName.endsWith(".zip")) {
                return fileName.substring(0, fileName.length() - 4);
            }
            return fileName;
        } catch (Exception e) {
            // Fallback: use the last path segment without extension
            String[] parts = urlString.split("/");
            String last = parts[parts.length - 1];
            return last.endsWith(".zip") ? last.substring(0, last.length() - 4) : last;
        }
    }

    /**
     * Recursively deletes a directory and all its contents.
     */
    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        if (!file.delete()) {
            Log.w(TAG, "Failed to delete: " + file.getAbsolutePath());
        }
    }
}