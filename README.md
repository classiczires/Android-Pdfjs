# Android-Pdfjs
PDF viewer for Android based on Mozilla Pdfjs.

[![](https://jitpack.io/v/classiczires/Android-Pdfjs.svg)](https://jitpack.io/#classiczires/Android-Pdfjs)


## Installation

### Gradle
Add this to the root build.gradle at the end of repositories (**WARNING:** Make sure you add this under **allprojects** not under buildscript):
```Gradle
allprojects {
        repositories {
                ...
                maven { url 'https://jitpack.io' }
        }
}
```

Add the dependency to the project build.gradle:
```Gradle
dependencies {
	        implementation 'com.github.classiczires:Android-Pdfjs:1.0.0'
}
```

## Usage

### XML
```xml
<com.zires.pdfjs.ZiresPdfView
    android:id="@+id/pdfView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

### Kotlin
```kotlin
class PdfActivity : AppCompatActivity() {

    private lateinit var pdfView: ZiresPdfView
    private lateinit var tempFolder: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf)
        pdfView = findViewById(R.id.pdfView)

        // 1. Set up a temporary folder for caching PDF.js assets
        tempFolder = cacheDir

        // 2. Optionally provide a fallback PDF.js ZIP URL (used when the bundled version fails)
        pdfView.initialize(tempFolder, "https://github.com/mozilla/pdf.js/releases/download/v2.5.207/pdfjs-2.5.207-es5-dist.zip")

        // 3. Define a listener for PDF events
        val listener = object : ZiresPdfView.PdfListener {
            override fun onPagesCountReady(pageCount: Int) { /* ... */ }
            override fun onPageChanged(currentPage: Int, pageCount: Int) { /* ... */ }
            override fun onPageDimensions(width: Int, height: Int) { /* ... */ }
            override fun onPdfError(errorMessage: String?) { /* handle error */ }
        }

        // 4. Load a PDF file from internal storage
        val pdfFile = File(filesDir, "sample.pdf")
        pdfView.setPdfFile(pdfFile, listener)

        // OR load from an InputStream
        // pdfView.setPdfStream(inputStream, listener)

        // 5. (Optional) enable pinch-to-zoom
        pdfView.enableZoom()
    }
}
```

### Available methods

| Method | Description |
|---|---|
| `initialize(tempFolder, olderVersionUrl?)` | Sets temp folder and optional fallback PDF.js URL. Returns `this` for chaining. |
| `setPdfFile(file, listener)` | Loads a PDF from a `File`. |
| `setPdfStream(inputStream, listener)` | Loads a PDF from an `InputStream`. |
| `enableZoom()` / `disableZoom()` | Toggles pinch-to-zoom. |
| `resetZoom()` | Resets zoom to fit page width. |
| `setZoomH(scale)` | Sets a specific zoom scale (e.g. `1.5f` = 150%). |
| `nextPage()` / `previousPage()` | Navigates one page forward or back. |
| `goToPage(pageNumber)` | Jumps to a specific **0-based** page index. |
| `setProgressTint(color)` | Tints the loading spinner color. |
