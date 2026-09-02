package com.zires.pdfjs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AbsoluteLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A lightweight PDF Viewer using Mozilla's PDF.js via WebView.
 * - SMALL SIZE: No native .so libraries.
 * - SIGNATURES: Supports complex annotations and signatures perfectly.
 * - FREE: Apache 2.0 License (Safe for proprietary apps).
 */
public class ZiresPdfView extends WebView {

    private static final String FAKE_BASE_URL = "mss.hovitaandroidplatforms.net";
    private static final String FILES_PATH = "/files/";
    private static final String CACHE_PATH = "/cache/";
    private static final String ASSETS_PATH = "/assets/";
    private static final String JS_INTERFACE_NAME = "AndroidCallback";
    private Boolean loaded = false;
    private boolean canZoom = true;
    private File tempFolder;
    private String olderVersionUrl;
    private File filesFolder;
    private File pdfFile;
    private PdfListener extarnalPdfListener;
    private ProgressBar progressBar;

    private final AtomicBoolean hasErrorHandled = new AtomicBoolean(false);
    private final PdfListener javaScriptInterface = new PdfListener() {
        @JavascriptInterface
        @Override
        public void onPagesCountReady(int pageCount) {
            new Handler(Looper.getMainLooper()).post(ZiresPdfView.this::hideProgress);
            if (extarnalPdfListener != null) {
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> {
                    if (extarnalPdfListener != null) {
                        extarnalPdfListener.onPagesCountReady(pageCount);
                    }
                });
            }
        }

        @JavascriptInterface
        @Override
        public void onPageChanged(int currentPage, int pageCount) {
            if (extarnalPdfListener != null) {
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> {
                    if (extarnalPdfListener != null) {
                        extarnalPdfListener.onPageChanged(currentPage, pageCount);
                    }
                });
            }
        }

        @JavascriptInterface
        @Override
        public void onPageDimensions(int width, int height) {
            if (extarnalPdfListener != null) {
                Handler h = new Handler(Looper.getMainLooper());
                h.post(() -> {
                    if (extarnalPdfListener != null) {
                        extarnalPdfListener.onPageDimensions(width, height);
                    }
                });
            }
        }

        @JavascriptInterface
        @Override
        public void onPdfError(String errorMessage) {
            if (hasErrorHandled.getAndSet(true)) {
                return;
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                stopLoading();
                if (pdfJsLoader == null) {
                    pdfJsLoader = new PdfJsLoader(filesFolder);
                    if (olderVersionUrl != null){
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                runOlderVersion();
                            }
                        }, 0);
                    } else {
                        hideProgress();
                        if (extarnalPdfListener != null) {
                            extarnalPdfListener.onPdfError(errorMessage);
                        }
                    }
                } else {
                    hideProgress();
                    if (extarnalPdfListener != null) {
                        extarnalPdfListener.onPdfError(errorMessage);
                    }
                }
            });
        }
    };
    private PdfJsLoader pdfJsLoader;

    /**
     * Creates a {@code ZiresPdfView} with the given Android context.
     *
     * @param context the context used to initialize the view
     */
    public ZiresPdfView(@NonNull Context context) {
        super(context);
        init(context);
    }

    /**
     * Creates a {@code ZiresPdfView} by inflating from XML.
     *
     * @param context the context used to initialize the view
     * @param attrs   the XML attributes declared in the layout file
     */
    public ZiresPdfView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    /**
     * Creates a {@code ZiresPdfView} by inflating from XML with a default style.
     *
     * @param context    the context used to initialize the view
     * @param attrs      the XML attributes declared in the layout file
     * @param defStyleAttr the default style attribute resource to apply
     */
    public ZiresPdfView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void init(Context context) {
        progressBar = new ProgressBar(context);
        progressBar.setIndeterminate(true); // circular spinner
        progressBar.setVisibility(View.GONE);
        addView(progressBar);
        clearWebViewStorage();
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);
        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
    }

    /**
     * Initializes the loader with a temporary folder.
     * <p>
     * If PDF.js assets are missing at render time, the {@code olderVersionUrl}
     * from {@link #initialize(File, String)} is used to download a compatible version.
     *
     * @param tempFolder directory used to cache pdf file and downloaded pdf-js ZIP
     * @return this view for chaining
     */
    public ZiresPdfView initialize(File tempFolder){
        initialize(tempFolder, null);
        return this;
    }

    /**
     * Initializes the loader with a temporary folder and a fallback PDF.js version URL.
     *
     * @param tempFolder      directory used to cache downloaded ZIP and extracted PDF.js assets
     * @param olderVersionUrl fallback URL for an older PDF.js distribution (downloaded if the bundled version fails)
     * @return this view for chaining
     */
    public ZiresPdfView initialize(File tempFolder, String olderVersionUrl){
        this.tempFolder = tempFolder;
        this.olderVersionUrl = olderVersionUrl;
        return this;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        try {
            if (!canZoom) {
                // consume multi-finger events so children (WebView) never see pinch gestures
                return true;
            }
        } catch (Exception ignored) {
        }
        return super.dispatchTouchEvent(ev);
    }


    /**
     * Loads a PDF from an {@link InputStream} and renders it inside the view.
     * <p>
     * The stream is written to a temp file so that PDF.js can load it via the
     * {@link WebViewAssetLoader} scheme. Progress is shown while loading and
     * errors are routed to the provided listener.
     *
     * @param inputStream        source stream of the PDF document (must not be {@code null})
     * @param extarnalPdfListener listener receiving page count, page changes, dimensions and errors
     */
    public void setPdfStream(InputStream inputStream, PdfListener extarnalPdfListener) {
        try {
            if (inputStream == null) return;

            showProgress();

            clearWebViewStorage();
            filesFolder = getContext().getFilesDir();
            File pdfFile = saveToTempFile(inputStream);
            this.extarnalPdfListener = extarnalPdfListener;
            this.pdfFile = pdfFile;
            loaded = false;

            final WebViewAssetLoader assetLoader =
                    new WebViewAssetLoader.Builder()
                            .setDomain(FAKE_BASE_URL)
                            .addPathHandler(FILES_PATH, new WebViewAssetLoader.InternalStoragePathHandler(getContext(), filesFolder))
                            .addPathHandler(CACHE_PATH, new WebViewAssetLoader.InternalStoragePathHandler(getContext(), tempFolder))
                            .addPathHandler(ASSETS_PATH, new WebViewAssetLoader.AssetsPathHandler(getContext()))
                            .build();
            setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (!loaded) {
                        loaded = true;
                    }
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(
                        WebView view, WebResourceRequest request) {
                    return assetLoader.shouldInterceptRequest(request.getUrl());
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    // Destroy the broken WebView instance
                    if (view != null) {
                        view.destroy();
                    }
                    javaScriptInterface.onPdfError("Render process crashed. Did it run out of memory? "
                            + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? detail.didCrash() : "onRenderProcessGone"));

                    return true;
                }
            });

            addJavascriptInterface(javaScriptInterface, JS_INTERFACE_NAME);


            //runOlderVersion();
            String pdfUrl = "https://" + FAKE_BASE_URL + CACHE_PATH + pdfFile.getAbsolutePath().replace(tempFolder.getAbsolutePath() + "/", "");
            String url = "https://" + FAKE_BASE_URL + ASSETS_PATH + "pdfjs-5.4.449/web/viewer.html" +
                    "?file=" + android.net.Uri.encode(pdfUrl) + "#zoom=page-actual";
            loadUrl(url);
        } catch (IOException e) {
            hideProgress();
            if (extarnalPdfListener != null) {
                extarnalPdfListener.onPdfError(e.getMessage());
            }
        }
    }

    /**
     * Loads a PDF from a {@link File} and renders it inside the view.
     * <p>
     * Internally opens a {@link FileInputStream} and delegates to
     * {@link #setPdfStream(InputStream, PdfListener)}.
     *
     * @param pdfFile            the PDF file to display (must exist and be readable)
     * @param extarnalPdfListener listener receiving page count, page changes, dimensions and errors
     */
    public void setPdfFile(File pdfFile, PdfListener extarnalPdfListener) {
        if (pdfFile == null || !pdfFile.exists()) return;

        try {
            InputStream inputStream = new FileInputStream(pdfFile);
            setPdfStream(inputStream, extarnalPdfListener);
        } catch (FileNotFoundException e) {
            if (extarnalPdfListener != null) {
                extarnalPdfListener.onPdfError("File not found.");
            }
        }
    }

    private void runOlderVersion() {
        showProgress();
        pdfJsLoader.loadPdfJs(olderVersionUrl, new PdfJsLoader.Callback() {
            @Override
            public void onLoaded(File newVersionFolder) {

                String pdfUrl = "https://" + FAKE_BASE_URL + CACHE_PATH + pdfFile.getAbsolutePath().replace(tempFolder.getAbsolutePath() + "/", "");

                String url = "https://" + FAKE_BASE_URL + FILES_PATH + newVersionFolder.getAbsolutePath().replace(filesFolder.getAbsolutePath() + "/", "") + "/web/viewer.html" +
                        "?file=" + android.net.Uri.encode(pdfUrl);
                loadUrl(url);
            }

            @Override
            public void onError(Exception e) {
                hideProgress();
                if (extarnalPdfListener != null) {
                    extarnalPdfListener.onPdfError(e.getMessage());
                }
            }
        });
    }

    private File saveToTempFile(InputStream inputStream) throws IOException {
        deleteTempFiles();
        // Create a unique temp file (prefix "temp", suffix ".tmp") inside app cache
        File tempFile = new File(tempFolder, "temp_ziresPdfview_" + System.currentTimeMillis() + ".pdf");
        if (tempFile.exists()) {
            tempFile.delete();
        }
        if (tempFile.createNewFile()) {
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192]; // 8 KB buffer
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
        }

        return tempFile;
    }

    private void deleteTempFiles() {
        if (tempFolder == null || !tempFolder.exists()) return;

        File[] tempFiles = tempFolder.listFiles((dir, name) ->
                name.startsWith("temp_ziresPdfview_")
        );

        if (tempFiles != null) {
            for (File file : tempFiles) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
    }


    /**
     * Clears all WebView storage including cache, cookies, form data, and history.
     * <p>
     * This resets the embedded PDF.js viewer state and loads a blank page.
     */
    public void clearWebViewStorage() {
        stopLoading();
        // 1. Wipes HTML5 localStorage & sessionStorage (where PDF.js stores zoom history)
        WebStorage.getInstance().deleteAllData();

        getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);

        // 2. Clears disk and memory cache files
        clearCache(true);

        // 3. Clears internal form data and navigation history
        clearFormData();
        clearHistory();

        // 4. Optionally clear cookies if any exist
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        loadUrl("about:blank");
    }

    /*
    private void setupPageChangeListener() {
        String js = "(function() {\n" +
                "  const bridge = window." + JS_INTERFACE_NAME + ";\n" +
                "  if (!bridge) {\n" +
                "    console.warn('[PDF] Bridge not found');\n" +
                "    return;\n" +
                "  }\n\n" +

                "  // Helper that will be called whenever we want to report dimensions\n" +
                "  const reportDimensions = function(pageNumber) {\n" +
                "    try {\n" +
                "      const pageView = window.PDFViewerApplication.pdfViewer.getPageView(pageNumber - 1);\n" +
                "      if (!pageView || !pageView.pdfPage) {\n" +
                "        // If pdfPage not ready yet, try again shortly\n" +
                "        setTimeout(() => reportDimensions(pageNumber), 50);\n" +
                "        return;\n" +
                "      }\n" +
                "      const vp = pageView.pdfPage.getViewport({scale: 1});\n" +
                "      const w = Math.round(vp.width);\n" +
                "      const h = Math.round(vp.height);\n" +
                "      console.log('[PDF] Dimensions for page', pageNumber, ':', w, 'x', h);\n" +
                "      bridge.onPageDimensions(w, h);\n" +
                "    } catch (e) {\n" +
                "      console.error('[PDF] reportDimensions error:', e);\n" +
                "    }\n" +
                "  };\n\n" +

                "  const attachListeners = function(app) {\n" +
                "    console.log('[PDF] Attaching page event listeners');\n" +

                "    // --- ERROR LISTENER ---\n" +
                "    app.eventBus.on('documenterror', function(evt) {\n" +
                "      console.error('[PDF] Document Error caught:', evt);\n" +
                "      let msg = (evt.reason && evt.reason.message) ? evt.reason.message : 'Failed to load PDF';\n" +
                "      bridge.onPdfError(msg);\n" +
                "    });\n" +
                "    // ---------------------------\n\n" +

                "    // pagechanging – called when current page changes\n" +
                "    app.eventBus.on('pagechanging', function(evt) {\n" +
                "      const total = app.pdfDocument ? app.pdfDocument.numPages : 0;\n" +
                "      console.log('[PDF] onPageChanged:', evt.pageNumber, '/', total);\n" +
                "      bridge.onPageChanged(evt.pageNumber, total);\n" +
                "      reportDimensions(evt.pageNumber);\n" +
                "    });\n\n" +

                "    // pagerendered – called after a page view is rendered\n" +
                "    app.eventBus.on('pagerendered', function(evt) {\n" +
                "      console.log('[PDF] Page', evt.pageNumber, 'rendered.');\n" +
                "      reportDimensions(evt.pageNumber);\n" +
                "    });\n\n" +

                "    // pagesinit – fired once all pages have been loaded (good place to get first page dimensions)\n" +
                "    app.eventBus.on('pagesinit', function() {\n" +
                "      console.log('[PDF] pagesinit fired');\n" +
                "      const current = app.pdfViewer.currentPageNumber;\n" +
                "      reportDimensions(current);\n" +
                "    });\n\n" +

                "    // Also report dimensions for the very first page immediately (in case pagesinit hasn't fired yet)\n" +
                "    const firstPage = app.pdfViewer.currentPageNumber;\n" +
                "    reportDimensions(firstPage);\n" +
                "  };\n\n" +

                "  const startTime = Date.now();\n" +
                "  const maxWait = 10000; // 10 s\n\n" +

                "  const poll = setInterval(function() {\n" +
                "    const app = window.PDFViewerApplication;\n" +
                "    if (app && app.pdfViewer && app.eventBus) {\n" +
                "      clearInterval(poll);\n" +
                "      attachListeners(app);\n" +
                "    }\n" +
                "    if (Date.now() - startTime > maxWait) {\n" +
                "      clearInterval(poll);\n" +
                "      console.warn('[PDF] setupPageChangeListener timed out after', maxWait, 'ms');\n" +
                "    }\n" +
                "  }, 200);\n\n" +
                "})();\n";

        evaluateJavascript(js, null);
    }


    private void setupScrollMode(WebView view, ScrollMode scrollMode) {
        String forceSinglePageJs =
                "   if (window.PDFViewerApplication && window.PDFViewerApplication.pdfViewer) {" +
                        "       window.PDFViewerApplication.pdfViewer.scrollMode = " + scrollMode.getValue() + ";" +
                        "       window.PDFViewerApplication.pdfViewer.spreadMode = 0;" +
                        "   }";

        // Execute using evaluateJavascript
        view.evaluateJavascript(forceSinglePageJs, null);
    }
     */

    /**
     * Enables pinch-to-zoom and built-in zoom controls on the WebView.
     */
    public void enableZoom() {
        WebSettings s = getSettings();
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        canZoom = true;
    }

    /**
     * Disables zoom gestures and hides built-in zoom controls.
     */
    public void disableZoom() {
        WebSettings s = getSettings();
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        canZoom = false;
    }

    /**
     * Resets the zoom level to fit the entire page width.
     */
    public void resetZoom() {
        evaluateJavascript("PDFViewerApplication.pdfViewer.currentScaleValue = 'page-width';", null);
    }

    /**
     * Sets the zoom scale of the rendered PDF.
     *
     * @param scale the zoom factor (e.g. {@code 1.0} = 100%, {@code 2.0} = 200%)
     */
    public void setZoomH(float scale) {
        // scale: 0.5 = 50%, 1.0 = 100%, 2.0 = 200%, etc.
        evaluateJavascript("PDFViewerApplication.pdfViewer.currentScale = " + scale + ";", null);
    }

    private void showProgress() {
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
        }
    }

    private void hideProgress() {
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
    }

    /**
     * Sets the tint color of the progress spinner.
     *
     * @param color the color int (e.g. {@code 0xFF000000} for opaque black)
     */
    public void setProgressTint(int color) {
        ColorStateList tint = ColorStateList.valueOf(color);
        // Tints the spinner (indeterminate) drawable
        progressBar.setIndeterminateTintList(tint);
        // Tints the bar (determinate) drawable, if you ever switch modes
        progressBar.setProgressTintList(tint);
        progressBar.setSecondaryProgressTintList(tint);
    }

    /**
     * Navigates to the next page in the PDF document.
     */
    public void nextPage() {
        evaluateJavascript("PDFViewerApplication.page++", null);
    }

    /**
     * Navigates to the previous page in the PDF document.
     */
    public void previousPage() {
        evaluateJavascript("PDFViewerApplication.page--", null);
    }

    /**
     * Navigates to a specific page in the PDF document.
     *
     * @param pageNumber the zero-based page index (0 = first page)
     */
    public void goToPage(int pageNumber) {
        evaluateJavascript("PDFViewerApplication.page = " + (pageNumber + 1), null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        loadUrl("about:blank");
        destroy();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);

        if (progressBar != null) {
            // Measure the progress bar to know its dimensions
            progressBar.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            int pbWidth = progressBar.getMeasuredWidth();
            int pbHeight = progressBar.getMeasuredHeight();

            // Calculate center
            int left = (w - pbWidth) / 2;
            int top = (h - pbHeight) / 2;

            // Apply absolute positioning
            AbsoluteLayout.LayoutParams params = new AbsoluteLayout.LayoutParams(
                    pbWidth, pbHeight, left, top);
            progressBar.setLayoutParams(params);
        }
    }
    /**
     * Listener interface for receiving PDF rendering events and errors.
     */
    public interface PdfListener {
        /**
         * Called on the main thread when the total page count is known.
         *
         * @param pageCount the total number of pages in the document
         */
        void onPagesCountReady(int pageCount);

        /**
         * Called on the main thread when the current page changes.
         *
         * @param currentPage the 1-based index of the new current page
         * @param pageCount   the total number of pages in the document
         */
        void onPageChanged(int currentPage, int pageCount);

        /**
         * Called on the main thread with the dimensions of a rendered page.
         *
         * @param width  the page width in CSS pixels
         * @param height the page height in CSS pixels
         */
        void onPageDimensions(int width, int height);

        /**
         * Called on the main thread when PDF.js reports an error.
         *
         * @param errorMessage a human-readable description of the error
         */
        void onPdfError(String errorMessage);
    }
}